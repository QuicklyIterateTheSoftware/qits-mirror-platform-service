package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.MavenProxyMetadata;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciMirrorTagCheck;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorTagCheckRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/**
 * A cache small enough to reason about, shaped like the two hazards eviction has: an entry whose
 * document is cold while its bytes are warm, and content shared between two identities.
 *
 * <p><b>Repository rows survive the wipe, and that is deliberate.</b> {@code artifact_repository}
 * carries V1's three mirror namespaces, which other suites in this profile assert are there — and a
 * cache root with no cached rows in it contributes nothing to any plan, so leaving them costs these
 * cases nothing.
 *
 * <p>Blobs are backdated past the store's grace window by the seeders, because the window is read
 * off {@code blob.stored_at} and a test's blobs are always seconds old. Backdating is the honest way
 * round: it exercises the same clock comparison a real run makes rather than configuring the window
 * away. A case that means to test the window says so by backdating less, or not at all.
 */
abstract class EvictionFixture {

  static final String NPM_CACHE = "npmjs";
  static final String MAVEN_CACHE = "central";
  static final String MIRROR_REPO = "quay";
  static final String MIRROR_IMAGE = "quarkus/ubi9-quarkus-mandrel-builder-image";

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;
  @Inject MirrorBlobCensus census;

  /**
   * The blob tables, reached directly. They are not mapped entities — the store writes them with
   * plain JDBC — so a wipe and a backdate are SQL here as they are in the library's own suite. It is
   * the same datasource the rows above are on, which is what {@code
   * qits.artifacts.blobs-datasource=mirror} says.
   */
  @Inject
  @DataSource("mirror")
  AgroalDataSource blobs;

  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository ociManifests;
  @Inject OciTagRepository ociTags;
  @Inject OciMirrorTagCheckRepository mirrorTagChecks;
  @Inject NpmVersionRepository npmVersions;
  @Inject NpmDistTagRepository npmDistTags;
  @Inject NpmVersionTombstoneRepository npmVersionTombstones;
  @Inject NpmProxyPackumentRepository npmProxyPackuments;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject MavenProxyMetadataRepository mavenProxyMetadata;

  /** Wipes every cached row and every stored blob before each case, so each one starts empty. */
  @BeforeEach
  void reset() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              mirrorTagChecks.deleteAll();
              ociTags.deleteAll();
              ociManifests.deleteAll();
              npmDistTags.deleteAll();
              npmVersions.deleteAll();
              npmVersionTombstones.deleteAll();
              npmProxyPackuments.deleteAll();
              mavenArtifacts.deleteAll();
              mavenProxyMetadata.deleteAll();
              records.deleteAll();
            });
    // blob first, then blob_content: the identity row is what points at the content, and removing
    // the content cascades to every chunk. Neither table has a key into the rows above.
    execute("delete from blob");
    execute("delete from blob_content");
  }

  // === npm ======================================================================================

  static final String NPM_COLD_PACKAGE = "left-pad";
  static final String NPM_WARM_PACKAGE = "chalk";
  static final int NPM_COLD_TARBALL = 70;
  static final int NPM_WARM_TARBALL = 80;

  /** What {@link #seedNpmCache()} built. */
  record NpmCache(String coldTarball, String warmTarball) {}

  /**
   * Two cached packages, one cold and one warm, each with its cached packument.
   *
   * <p>The warm one is the case the staleness rule exists for: its <b>document</b> was last
   * revalidated upstream long ago, while its tarball was pulled yesterday. A packument judged on
   * {@code fetched_at} alone would evict the document of a package something is actively installing,
   * and the next install would pay upstream for it again.
   */
  NpmCache seedNpmCache() {
    repositoryService.ensure(NPM_CACHE, NpmProxyProfile.KEY);
    String cold = store(filled(NPM_COLD_TARBALL, (byte) 11));
    String warm = store(filled(NPM_WARM_TARBALL, (byte) 12));
    Instant longAgo = Instant.now().minus(Duration.ofDays(200));
    Instant yesterday = Instant.now().minus(Duration.ofDays(1));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              npmVersions.persist(cachedVersion(NPM_COLD_PACKAGE, "1.3.0", cold, longAgo, longAgo));
              npmVersions.persist(
                  cachedVersion(NPM_WARM_PACKAGE, "5.3.0", warm, longAgo, yesterday));
              npmProxyPackuments.persist(cachedPackument(NPM_COLD_PACKAGE, longAgo));
              npmProxyPackuments.persist(cachedPackument(NPM_WARM_PACKAGE, longAgo));
            });
    for (String blobId : List.of(cold, warm)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new NpmCache(cold, warm);
  }

  /** One cached version with both timestamps under the case's control. */
  void npmVersionRow(
      String packageName, String version, String blobId, Instant createdAt, Instant accessedAt) {
    repositoryService.ensure(NPM_CACHE, NpmProxyProfile.KEY);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                npmVersions.persist(
                    cachedVersion(packageName, version, blobId, createdAt, accessedAt)));
  }

  private static NpmVersion cachedVersion(
      String packageName, String version, String blobId, Instant createdAt, Instant accessedAt) {
    NpmVersion row = new NpmVersion();
    row.repository = NPM_CACHE;
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = blobId;
    row.manifestJson = "{}";
    row.createdAt = createdAt;
    row.accessedAt = accessedAt;
    return row;
  }

  private static NpmProxyPackument cachedPackument(String packageName, Instant fetchedAt) {
    NpmProxyPackument row = new NpmProxyPackument();
    row.repository = NPM_CACHE;
    row.packageName = packageName;
    row.doc = "{\"name\":\"" + packageName + "\",\"versions\":{}}";
    row.etag = "\"seed\"";
    row.fetchedAt = fetchedAt;
    return row;
  }

  // === maven ====================================================================================

  static final String MAVEN_ARTIFACT_DIR = "org/slf4j/slf4j-api";
  static final String MAVEN_COLD_PATH = MAVEN_ARTIFACT_DIR + "/1.7.36/slf4j-api-1.7.36.jar";
  static final String MAVEN_WARM_PATH = MAVEN_ARTIFACT_DIR + "/2.0.13/slf4j-api-2.0.13.jar";
  static final String MAVEN_METADATA_PATH = MAVEN_ARTIFACT_DIR + "/maven-metadata.xml";
  static final int MAVEN_COLD = 90;
  static final int MAVEN_WARM = 95;

  /** What {@link #seedMavenCache()} built. */
  record MavenCache(String coldJar, String warmJar) {}

  /**
   * Two cached files of one upstream artifact — one cold, one warm — and the cached {@code
   * maven-metadata.xml} beside them.
   *
   * <p>The document was last revalidated upstream long ago while the 2.0.13 jar under it was
   * resolved yesterday: a document judged on {@code fetched_at} alone would be evicted out from
   * under an artifact something is actively building against. Both files sit under {@link
   * #MAVEN_ARTIFACT_DIR}, which is the document's directory, and that prefix relationship is what
   * the evictor folds.
   */
  MavenCache seedMavenCache() {
    repositoryService.ensure(MAVEN_CACHE, MavenProxyProfile.KEY);
    String cold = store(filled(MAVEN_COLD, (byte) 13));
    String warm = store(filled(MAVEN_WARM, (byte) 14));
    Instant longAgo = Instant.now().minus(Duration.ofDays(200));
    Instant yesterday = Instant.now().minus(Duration.ofDays(1));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              mavenProxyMetadata.persist(cachedMetadata(MAVEN_METADATA_PATH, longAgo));
              mavenArtifacts.persist(
                  cachedFile(MAVEN_COLD_PATH, cold, MAVEN_COLD, longAgo, longAgo));
              mavenArtifacts.persist(
                  cachedFile(MAVEN_WARM_PATH, warm, MAVEN_WARM, longAgo, yesterday));
            });
    for (String blobId : List.of(cold, warm)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new MavenCache(cold, warm);
  }

  /** One cached file with its blob and both timestamps under the case's control. */
  void mavenFileRow(
      String path, String blobId, long size, Instant createdAt, Instant accessedAt) {
    repositoryService.ensure(MAVEN_CACHE, MavenProxyProfile.KEY);
    QuarkusTransaction.requiringNew()
        .run(() -> mavenArtifacts.persist(cachedFile(path, blobId, size, createdAt, accessedAt)));
  }

  private static MavenArtifact cachedFile(
      String path, String blobId, long size, Instant createdAt, Instant accessedAt) {
    MavenArtifact row = new MavenArtifact();
    row.repository = MAVEN_CACHE;
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.createdAt = createdAt;
    row.accessedAt = accessedAt;
    return row;
  }

  private static MavenProxyMetadata cachedMetadata(String path, Instant fetchedAt) {
    MavenProxyMetadata row = new MavenProxyMetadata();
    row.repository = MAVEN_CACHE;
    row.path = path;
    row.doc = "<metadata><artifactId>slf4j-api</artifactId></metadata>";
    row.etag = "\"seed\"";
    row.fetchedAt = fetchedAt;
    return row;
  }

  // === OCI ======================================================================================

  static final int MIRROR_CONFIG = 11;
  static final int MIRROR_LAYER = 700;
  static final int ABSENT_CHILD = 900;

  /** What {@link #seedMirror()} built. */
  record MirrorStore(String config, String layer, String child, String index, String absentChild) {}

  /**
   * One cached multi-arch image in a mirror namespace, <b>with one child that was never fetched</b>.
   *
   * <p>That missing child is the point. A pull arrives index-first: the mirror binds the index and
   * fetches children lazily, each on its own miss, so it never pays upstream for an architecture
   * nobody pulled. A mirror index referencing a child with no local row is therefore the normal
   * state of a partially-pulled image, not a corruption, and every reader that walks manifests has
   * to survive it.
   */
  MirrorStore seedMirror() {
    repositoryService.ensure(MIRROR_REPO, OciMirrorProfile.KEY);

    String config = store(filled(MIRROR_CONFIG, (byte) 6));
    String layer = store(filled(MIRROR_LAYER, (byte) 7));
    byte[] childBytes = imageManifest(config, Map.of(layer, (long) MIRROR_LAYER), MIRROR_CONFIG);
    String child = store(childBytes);
    // Never stored and never rowed: the architecture nobody pulled.
    String absentChild = "a".repeat(64);
    byte[] indexBytes =
        indexManifest(Map.of(child, (long) childBytes.length, absentChild, (long) ABSENT_CHILD));
    String index = store(indexBytes);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(
                  mirrorManifest(child, childBytes.length, OciMediaTypes.OCI_MANIFEST_V1));
              ociManifests.persist(
                  mirrorManifest(index, indexBytes.length, OciMediaTypes.OCI_INDEX_V1));
              ociTags.persist(mirrorTag("jdk-25", index));
            });

    for (String blobId : List.of(config, layer, child, index)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new MirrorStore(config, layer, child, index, absentChild);
  }

  /**
   * Ages every mirror row — tags and manifests, both timestamps each — so the eviction window bites.
   *
   * <p>The blob <em>files</em> are backdated by {@link #seedMirror()}; this is the other half, and
   * the two are deliberately separate. A file's mtime drives the grace window, a row's timestamps
   * drive the eviction rule, and a case that could not move one without the other could not tell
   * the two mechanisms apart.
   */
  void ageMirrorRows(Duration age) {
    Instant at = Instant.now().minus(age);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociTags.update(
                  "updatedAt = ?1, accessedAt = ?1 where repository = ?2", at, MIRROR_REPO);
              ociManifests.update(
                  "createdAt = ?1, accessedAt = ?1 where repository = ?2", at, MIRROR_REPO);
            });
  }

  /** Moves one mirrored tag's {@code accessed_at}, as a pull through the mirror would. */
  void touchMirrorTag(String tag, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ociTags.update(
                    "accessedAt = ?1 where repository = ?2 and imageName = ?3 and tag = ?4",
                    at,
                    MIRROR_REPO,
                    MIRROR_IMAGE,
                    tag));
  }

  /** The freshness row the miss path writes beside a tag, and what eviction must clear with it. */
  void mirrorTagCheck(String tag, Instant checkedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciMirrorTagCheck check = new OciMirrorTagCheck();
              check.repository = MIRROR_REPO;
              check.imageName = MIRROR_IMAGE;
              check.tag = tag;
              check.checkedAt = checkedAt;
              mirrorTagChecks.persist(check);
            });
  }

  private static OciManifest mirrorManifest(String digest, long size, String mediaType) {
    OciManifest row = new OciManifest();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.digest = digest;
    row.mediaType = mediaType;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private static OciTag mirrorTag(String name, String digest) {
    OciTag row = new OciTag();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = Instant.now();
    return row;
  }

  // === blobs ====================================================================================

  /** Stores bytes in the blob store, as every wire route does, and answers their digest. */
  String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  /** Ages a stored blob past the store's grace window. */
  void backdate(String blobId, Duration age) {
    update(
        "update blob set stored_at = ? where id = ?",
        statement -> {
          statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
          statement.setString(2, blobId);
        });
  }

  private void execute(String sql) {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  /** Fills in a prepared statement's parameters, the way JDBC makes you. */
  private interface Binding {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private void update(String sql, Binding binding) {
    try (Connection connection = blobs.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binding.bind(statement);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  /** A real OCI image manifest — the footprint parser reads these bytes, so a stub proves nothing. */
  static byte[] imageManifest(String configDigest, Map<String, Long> layers, int configSize) {
    List<String> descriptors = new ArrayList<>();
    layers.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_MANIFEST_V1
            + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
            + "\"digest\":\"sha256:"
            + configDigest
            + "\",\"size\":"
            + configSize
            + "},\"layers\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** A real OCI index — its children are manifests, not blobs, which is what makes the walk recurse. */
  static byte[] indexManifest(Map<String, Long> children) {
    List<String> descriptors = new ArrayList<>();
    children.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\""
                    + OciMediaTypes.OCI_MANIFEST_V1
                    + "\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_INDEX_V1
            + "\",\"manifests\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** The identities of a judged list, sorted, which is what every case here compares. */
  static List<String> identities(List<JudgedIdentity> identities) {
    return identities.stream().map(JudgedIdentity::identity).sorted().toList();
  }
}
