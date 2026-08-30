package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.control.BlobDiskIndex;
import eu.wohlben.qits.artifacts.control.OciManifestFootprints;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which blobs the store's rows still reach, per repository type, beside what the store holds.
 *
 * <p>The type split is what makes per-type eviction safe. A blob dedupes globally, so "is this blob
 * garbage" is never a question one type can answer — but "which blobs does <em>my</em> type still
 * reach" is, and the reconciliation across all of them belongs to {@link MirrorBlobSweep}.
 *
 * <p>Liveness per type, and where it is read from:
 *
 * <ul>
 *   <li>{@code OCI_MIRROR} — the manifest closure ({@link OciManifestFootprints}, which walks an
 *       index's children, so a child manifest of a live index is live). Sizes are the {@code size}
 *       fields inside the manifest documents.
 *   <li>{@code NPM_PROXY} — {@code npm_version.tarball_blob_id}, sized from the store because there
 *       is no size column.
 *   <li>{@code MAVEN_PROXY} — {@code maven_artifact.blob_id}, sized from the row: that table is the
 *       one protocol table whose size was free at stage time.
 * </ul>
 *
 * <p>{@code artifact_record} is read too, and this service writes none: every type registered here
 * is a protocol type, so the validating upload path is refused for all three. Reading the table
 * anyway is what keeps the census honest if that ever changes, and costs one indexed query per
 * repository.
 *
 * <p><b>The types are open, so this class enumerates none of them.</b> It reads each repository
 * row's own type key and files that repository's blobs under it. A table a repository has no rows in
 * simply contributes nothing.
 *
 * <p><b>What this census cannot see is not garbage — it is untouchable.</b> A blob no row names
 * appears in {@link Census#rowless()}, and no plan may ever release one: a blob can only become
 * sweepable by <em>losing</em> its last row, so a blob that never had one is out of reach of the
 * whole mechanism by construction.
 */
@ApplicationScoped
public class MirrorBlobCensus {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository manifests;
  @Inject NpmVersionRepository versions;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject OciManifestFootprints footprints;
  @Inject BlobDiskIndex diskIndex;

  /**
   * One reading of the store: every stored blob, and every blob each type still reaches.
   *
   * <p>A value object, so a caller can hold it across a plan without the store shifting under it —
   * which is also why a sweep re-takes it immediately before removing anything.
   *
   * @param takenAt when the reading was taken
   * @param onDisk blob id (bare hex) to stored bytes, for every promoted blob. The NAME outlived the
   *     disk: the bytes are {@code blob_chunk} rows now, and the census contract is unchanged by it
   * @param liveByType stored type key ({@code OCI_MIRROR}) to blob id to the size that type knows
   *     for it
   */
  public record Census(
      Instant takenAt, Map<String, Long> onDisk, Map<String, Map<String, Long>> liveByType) {

    public Census {
      onDisk = Map.copyOf(onDisk);
      Map<String, Map<String, Long>> copy = new HashMap<>();
      liveByType.forEach((type, live) -> copy.put(type, Map.copyOf(live)));
      liveByType = Map.copyOf(copy);
    }

    /** Every blob this type still reaches, with the size that type knows for it. */
    public Map<String, Long> live(String typeKey) {
      return liveByType.getOrDefault(typeKey, Map.of());
    }

    /** Every blob any type reaches. */
    public Set<String> referenced() {
      Set<String> referenced = new HashSet<>();
      liveByType.values().forEach(live -> referenced.addAll(live.keySet()));
      return referenced;
    }

    /** Blobs no row of any type names — the untouchable pool. */
    public Set<String> rowless() {
      Set<String> referenced = referenced();
      Set<String> rowless = new HashSet<>();
      onDisk.keySet().stream().filter(id -> !referenced.contains(id)).forEach(rowless::add);
      return rowless;
    }

    /** What removing these blobs would free. A blob the store does not hold frees nothing. */
    public long bytesOnDisk(Collection<String> blobIds) {
      long total = 0;
      for (String blobId : blobIds) {
        total += onDisk.getOrDefault(blobId, 0L);
      }
      return total;
    }
  }

  /** Takes a fresh reading: one indexed query over the blob table and one pass over the rows. */
  public Census take() {
    Map<String, Long> onDisk = diskIndex.sizes();
    Map<String, Map<String, Long>> live = new HashMap<>();

    for (ArtifactRepository repository : repositories.<ArtifactRepository>listAll()) {
      // Every table is read for every repository and attributed to that repository's OWN type key.
      // A repository of the wrong type simply has no rows in a table — an npm root holds no
      // manifests — so nothing here needs to know which types exist. The tables answer.
      Map<String, Long> blobs = live.computeIfAbsent(repository.type, type -> new HashMap<>());
      for (String image : manifests.listImageNames(repository.name)) {
        footprints.union(manifests.listByImage(repository.name, image)).forEach(blobs::putIfAbsent);
      }
      for (Object[] blob : records.listDistinctBlobs(repository.name)) {
        blobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
      for (Object[] blob : mavenArtifacts.listDistinctBlobs(repository.name)) {
        blobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
      for (String blobId : versions.listTarballBlobIds(List.of(repository.name))) {
        blobs.putIfAbsent(blobId, onDisk.getOrDefault(blobId, 0L));
      }
    }

    return new Census(Instant.now(), onDisk, live);
  }
}
