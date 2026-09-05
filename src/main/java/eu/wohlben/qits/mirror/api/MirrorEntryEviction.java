package eu.wohlben.qits.mirror.api;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * The one write behind {@code /mirror/api} — dropping a single cached entry so the next request
 * fetches it again.
 *
 * <p>{@link MirrorExplorer} is the read half and says the surface has no write. It had none until
 * 2026-09-05, and the reason it had none was good: what this service holds is decided by what
 * somebody pulled and what it drops is decided by a window and a clock, so a button that evicted on
 * demand would be a second eviction policy with no record of why it ran.
 *
 * <p><b>That argument is about policy. This is about repair, and the two are not the same thing.</b>
 * A cached {@code quarkus-proxy-registry-3.34.6.pom} answered {@code 500} to every request for four
 * days: the bytes were fine, upstream was fine, and the row itself had gone bad in a way no read
 * could route around. Nothing on this service could clear it. The window for {@code maven-proxy} is
 * {@code P90D} and the entry was cached on 2026-09-01, so the sweep would not have looked at it
 * until December; the sweep is a clock and not a hand; and while it stood, every build on the
 * platform that resolved that coordinate failed. <b>A cache with no way to drop one entry is a cache
 * whose faults are permanent.</b>
 *
 * <h2>What keeps this a repair</h2>
 *
 * <ul>
 *   <li><b>One entry, by its exact path.</b> No prefix, no version, no repository, no "clear the
 *       cache". A caller who wants ten entries gone asks ten times and can say afterwards which ten.
 *   <li><b>Caches only.</b> {@code maven_artifact} is one table for two maven types, so a path is
 *       all that separates a cached row from a published jar — the same trap {@code
 *       MavenRegistryService.evictProxiedArtifact} guards, restated here because this door is
 *       reachable from a browser. A repository that is not a pull-through is a {@code 409} naming
 *       what it is.
 *   <li><b>The bytes are not touched.</b> Blobs are content-addressed and shared across every
 *       repository and every type; what may be unlinked is the sweep's question and never a
 *       caller's. Evicting an entry frees nothing on disk today, and that is correct.
 *   <li><b>It undoes itself.</b> What this removes comes back on the next request for it. That is
 *       the whole difference from {@code collect}, which removes a coordinate this platform
 *       published and cannot get back.
 * </ul>
 *
 * <h2>Why the removal is a bulk delete and not a load-then-delete</h2>
 *
 * <p>{@code MavenRegistryCollection.evictProxiedArtifact} — the door the nightly sweep uses — reads
 * the row with {@code findOne} and deletes the entity it got. That is right for the sweep, which is
 * working from a plan it built by enumerating rows that it therefore knows it can load. <b>It is the
 * wrong shape for a repair, because the entries worth repairing are the ones a load cannot be
 * trusted on.</b> The 2026-09-05 fault was a row whose {@code accessed_at} write raised {@code
 * duplicate key value violates unique constraint "maven_artifact_pkey"} — an {@code UPDATE} that
 * touches no key column cannot do that unless the table holds more than one heap tuple for that key
 * and the index no longer agrees with the heap. A {@code findOne} on such a key returns one tuple or
 * none, and deleting the entity it returned would leave the other one exactly where it was, so the
 * door would report success and heal nothing.
 *
 * <p>A bulk {@code delete … where repository = ? and path = ?} removes <b>every</b> matching tuple
 * and reports how many there were. That count is not decoration: {@code 2} on a table whose primary
 * key is {@code (repository, path)} is a statement about the store that an operator needs to see,
 * and it is what tells a reader the entry was genuinely corrupt rather than merely cold.
 */
@ApplicationScoped
public class MirrorEntryEviction {

  private static final Logger LOG = Logger.getLogger(MirrorEntryEviction.class);

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenProxyMetadataRepository metadata;

  /**
   * What one eviction did.
   *
   * @param repository the cache root the entry was dropped from
   * @param path the entry, exactly as the caller spelled it
   * @param kind {@code file} or {@code metadata} — which of the two things a maven cache holds this
   *     was, so a reader can tell an evicted {@code maven-metadata.xml} from an evicted jar
   * @param rowsRemoved how many rows went. <b>One is the ordinary answer.</b> More than one means
   *     the primary key was not holding, which is the fault this door exists to clear and the number
   *     worth putting in front of whoever ran it.
   */
  public record Evicted(String repository, String path, String kind, long rowsRemoved) {}

  /**
   * Drops one cached entry, or says exactly why it did not.
   *
   * @throws WebApplicationException {@code 404} no such repository or nothing cached at that path,
   *     {@code 409} the repository is not a pull-through cache, {@code 400} the path is empty or not
   *     repository-relative — each with a plain-text body naming what went wrong, because the caller
   *     is a person clearing a fault and a bare status leaves them guessing
   */
  @Transactional
  public Evicted evict(String repositoryName, String path) {
    String clean = requireRelativePath(path);
    ArtifactRepository repository = requireCacheRoot(repositoryName);

    if (MavenProxyProfile.KEY.equals(repository.type)) {
      return evictMavenEntry(repository.name, clean);
    }
    // npm and OCI are caches too and will want this door. They are not here yet, and guessing their
    // spelling would be worse than saying so: an npm entry is a package AND a version, an OCI entry
    // is an image AND either a tag or a digest, and neither is a `path` — so each needs a parameter
    // of its own and a test that proves the eviction reaches the right one of two tables. A 409 that
    // names the type is an honest "not yet"; a 404 would read as "no such entry" and send whoever is
    // debugging a poisoned image to the wrong service.
    throw refuse(
        Response.Status.CONFLICT,
        "'"
            + repository.name
            + "' is a "
            + RepositoryTypeProfile.wireNameOf(repository.type)
            + " cache, and this door only evicts maven-proxy entries so far — an npm or OCI entry is"
            + " not spelled by a path and needs a parameter of its own");
  }

  /**
   * The maven half: two kinds of cached thing under one path space, told apart the way the wire
   * tells them apart — by name.
   *
   * <p>{@code maven-metadata.xml} is a {@code maven_proxy_metadata} row with a TTL, everything else
   * is a {@code maven_artifact} row with a blob. A caller does not have to know which; the filename
   * already says, and asking them to pick would be asking them to know the schema.
   *
   * <p>A checksum sibling of the metadata ({@code maven-metadata.xml.sha1}) is <b>not</b> a cached
   * entry at all — the proxy derives it from the document it is serving — so the entry to evict is
   * the document, and that is what this resolves it to rather than answering "nothing cached
   * there". Upstream's own {@code .sha1} beside a jar is a different matter: that one IS cached, as
   * an ordinary immutable path, and is evicted as the file it is.
   */
  private Evicted evictMavenEntry(String repository, String path) {
    String file = MavenLayout.fileOf(path);
    if (MavenLayout.isMetadata(file) || MavenLayout.metadataChecksumAlgorithm(file) != null) {
      String document =
          MavenLayout.isMetadata(file)
              ? path
              : MavenLayout.directoryOf(path) + "/" + MavenLayout.METADATA;
      long removed = metadata.delete("repository = ?1 and path = ?2", repository, document);
      if (removed == 0) {
        throw refuse(
            Response.Status.NOT_FOUND,
            "no cached metadata at " + document + " in '" + repository + "'");
      }
      return report(new Evicted(repository, document, "metadata", removed));
    }

    long removed = artifacts.delete("repository = ?1 and path = ?2", repository, path);
    if (removed == 0) {
      // Deliberately a 404 and never a quiet 204. "Nothing was cached there" and "the entry is gone
      // now" are different answers, and an operator clearing a poisoned coordinate has to be able to
      // tell a successful eviction from a mistyped path — which is the same rule this service keeps
      // on the wire, where a miss and a failure must not share a status.
      throw refuse(
          Response.Status.NOT_FOUND, "nothing cached at " + path + " in '" + repository + "'");
    }
    return report(new Evicted(repository, path, "file", removed));
  }

  /** Logged at INFO, always: an eviction is a hand on the store and belongs in the record. */
  private static Evicted report(Evicted evicted) {
    if (evicted.rowsRemoved() > 1) {
      LOG.warnf(
          "Evicted %s '%s' from %s — %d ROWS for one primary key, so the entry was corrupt and not"
              + " merely cached",
          evicted.kind(), evicted.path(), evicted.repository(), evicted.rowsRemoved());
    } else {
      LOG.infof(
          "Evicted %s '%s' from %s; the next request for it fetches from upstream",
          evicted.kind(), evicted.path(), evicted.repository());
    }
    return evicted;
  }

  /**
   * A refusal with its reason in the body. {@code WebApplicationException("msg", status)} carries the
   * message in the JVM and answers an EMPTY body, which is the whole of why this exists: the caller
   * is an operator clearing a fault, and "409" on its own does not tell them the repository is an
   * OCI mirror.
   */
  private static WebApplicationException refuse(Response.Status status, String message) {
    return new WebApplicationException(
        Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build());
  }

  /**
   * The repository row, or a 404 that names the listing rather than inventing a namespace — the
   * rule {@code MavenRegistryService.requireMavenRepository} keeps on the wire.
   */
  private ArtifactRepository requireCacheRoot(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null) {
      throw refuse(
          Response.Status.NOT_FOUND,
          "no such cache root '" + name + "'; GET /mirror/api/repositories lists them");
    }
    return repository;
  }

  /**
   * The path, as the wire would have received it: repository-relative, no leading slash, no
   * traversal.
   *
   * <p>A leading slash is tolerated and stripped because that is what somebody copying a URL
   * produces, and refusing it would be a puzzle rather than a defence. A {@code ..} segment is
   * refused outright: nothing this store holds is named that, so its only purpose here would be to
   * try to mean a row it should not.
   */
  private static String requireRelativePath(String path) {
    String clean = path == null ? "" : path.trim();
    while (clean.startsWith("/")) {
      clean = clean.substring(1);
    }
    if (clean.isEmpty()) {
      throw refuse(
          Response.Status.BAD_REQUEST,
          "?path= is required and names one cached entry, exactly as the wire serves it"
              + " (io/quarkus/quarkus-proxy-registry/3.34.6/quarkus-proxy-registry-3.34.6.pom)");
    }
    if (clean.contains("..")) {
      throw refuse(Response.Status.BAD_REQUEST, "not a cached entry path: " + clean);
    }
    return clean;
  }
}
