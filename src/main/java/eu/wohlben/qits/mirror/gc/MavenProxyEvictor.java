package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The maven cache's facts: two kinds of cached identity, when each was last wanted, and how a row
 * goes.
 *
 * <h2>Two identities, because the cache holds two things</h2>
 *
 * <ul>
 *   <li>A <b>cached file</b> — a {@code maven_artifact} row with its blob, spelled by its path. A
 *       jar, a pom, a sources jar, and upstream's own {@code .sha1}/{@code .md5} siblings, which are
 *       immutable paths like any other here.
 *   <li>A <b>cached metadata document</b> — a {@code maven_proxy_metadata} row, spelled {@code
 *       <path> (metadata)} so it can never be mistaken for a file. A path already ends in {@code
 *       maven-metadata.xml}, and no filename contains a space, so the two spellings cannot collide.
 * </ul>
 *
 * <h2>A PATH is the identity here</h2>
 *
 * <p>qits-artifacts folds its hosted maven rows into coordinates because half a published version is
 * a broken resolve that nothing can repair: the bytes are gone. A cache repairs itself — the next
 * request for an evicted path fetches it through again — so there is no half-version to prevent, and
 * making a coordinate the unit here would only withhold files nothing has asked for in months
 * because one sibling is warm. The rule is per identity, and an identity here is a file.
 *
 * <h2>Staleness</h2>
 *
 * <p>A file's effective access is {@code max(created_at, accessed_at)}, moved by every stored-file
 * GET, with creation counting as the first access.
 *
 * <p>A document's is {@code max(fetched_at, the newest access among the files cached under its
 * directory)}. Both halves are needed and neither alone is honest, the npm packument's argument
 * restated: {@code fetched_at} only says when the document was last revalidated upstream, which a
 * TTL moves on its own, while an artifact whose jars are still being resolved is plainly in use.
 * Folding the files in also makes the two die together, so a warm artifact always keeps the document
 * a resolver reads its versions from.
 *
 * <h2>Eviction is not a collection</h2>
 *
 * <p>Rows leave through {@code MavenRegistryCollection.evictProxiedArtifact}/{@code
 * evictProxiedMetadata}, which refuse any repository that is not a {@code maven-proxy}. The hosted
 * door ({@code collect}) is qits-artifacts' and is never reached from here.
 */
@Singleton
public class MavenProxyEvictor implements CacheEvictor {

  /**
   * What distinguishes a cached document's identity from a cached file's. A space cannot appear in a
   * maven path segment this store accepts, so no path can collide with this spelling.
   */
  public static final String METADATA = " (metadata)";

  @Inject CacheRoots roots;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenProxyMetadataRepository metadata;
  @Inject MavenRegistryCollection maven;

  @Override
  public String typeKey() {
    return MavenProxyProfile.KEY;
  }

  @Override
  public List<CachedIdentity> enumerate() {
    List<CachedIdentity> candidates = new ArrayList<>();
    for (String repository : roots.of(typeKey())) {
      collect(repository, candidates);
    }
    return List.copyOf(candidates);
  }

  /** The caption this type's line carries — {@link NpmProxyEvictor#note()}'s argument verbatim. */
  @Override
  public String note() {
    return "cached maven-metadata.xml documents are text columns, not files: "
        + metadata.totalDocLength(roots.of(typeKey()))
        + " characters are cached as this report was produced. Evicting one frees its row for reuse"
        + " and reclaims 0 bytes on disk — PostgreSQL returns the space to the table rather than to"
        + " the filesystem, which only a VACUUM FULL does and nothing here runs one. The reclaimed"
        + " bytes on this line count cached files and nothing else.";
  }

  /**
   * Files first, then documents — a document whose files are still being deleted is the shape a
   * reader expects. Either order is correct: the next request re-fetches whatever is missing.
   */
  @Override
  public Applied delete(EvictionPlan plan, GraceWindow grace) {
    List<JudgedIdentity> deleted = new ArrayList<>();
    List<JudgedIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (JudgedIdentity dead : plan.dead()) {
      if (!isMetadata(dead)) {
        deleteFile(dead, grace, deleted, withheld, errors);
      }
    }
    for (JudgedIdentity dead : plan.dead()) {
      if (isMetadata(dead)) {
        deleteMetadata(dead, deleted, errors);
      }
    }
    return new Applied(deleted, withheld, errors);
  }

  /** One cache root: every cached file, then every cached document. */
  private void collect(String repository, List<CachedIdentity> candidates) {
    // Sorted, so a document's directory prefix is a range rather than a scan of everything. A
    // Central cache is thousands of paths and a document per artifact, which is the one place in
    // this class where the naive shape would be quadratic.
    TreeMap<String, Instant> accessByPath = new TreeMap<>();
    for (MavenArtifact row : artifacts.<MavenArtifact>list("repository = ?1", repository)) {
      Instant access = latest(row.createdAt, row.accessedAt);
      accessByPath.put(row.path, access);
      candidates.add(new CachedIdentity(repository, row.path, access, Set.of(row.blobId)));
    }
    for (Object[] row : metadata.listCached(repository)) {
      String path = (String) row[0];
      Instant fetchedAt = (Instant) row[1];
      candidates.add(
          new CachedIdentity(
              repository,
              path + METADATA,
              later(fetchedAt, newestUnder(accessByPath, MavenLayout.directoryOf(path))),
              // No blob: a metadata document is a database row, not a file.
              Set.of()));
    }
  }

  /**
   * The newest access among the files cached under a directory — a prefix range on the sorted map,
   * so this costs the entries it reads rather than the whole cache per document.
   */
  private static Instant newestUnder(TreeMap<String, Instant> accessByPath, String directory) {
    String prefix = directory.isEmpty() ? "" : directory + "/";
    Instant newest = null;
    for (Map.Entry<String, Instant> entry : accessByPath.tailMap(prefix, true).entrySet()) {
      if (!entry.getKey().startsWith(prefix)) {
        break;
      }
      newest = later(newest, entry.getValue());
    }
    return newest;
  }

  private static boolean isMetadata(JudgedIdentity identity) {
    return identity.identity().endsWith(METADATA);
  }

  private void deleteFile(
      JudgedIdentity dead,
      GraceWindow grace,
      List<JudgedIdentity> deleted,
      List<JudgedIdentity> withheld,
      List<String> errors) {
    try {
      MavenArtifact row = artifacts.findOne(dead.repository(), dead.identity()).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such cached row — the store moved since planning");
        return;
      }
      if (grace.withinGrace(row.blobId)) {
        withheld.add(dead);
        return;
      }
      maven.evictProxiedArtifact(dead.repository(), dead.identity());
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * A document names no blob, so nothing can be inside the grace window and it is never withheld.
   * The window exists to stop a row deletion from stranding a young file; there is no file.
   */
  private void deleteMetadata(
      JudgedIdentity dead, List<JudgedIdentity> deleted, List<String> errors) {
    String path = dead.identity().substring(0, dead.identity().length() - METADATA.length());
    try {
      maven.evictProxiedMetadata(dead.repository(), path);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /** Creation counts as the first access, so a file cached minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static Instant later(Instant one, Instant other) {
    if (one == null) {
      return other;
    }
    return other == null || other.isBefore(one) ? one : other;
  }
}
