package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The run as a whole: every type planned against one census, then the rows, then the bytes.
 *
 * <p>What the per-format suites cannot ask is here — that a blob released by one type and still
 * named by another survives, that the unlink loop frees what nothing reaches, and that a type whose
 * configuration is missing takes its own line down rather than the run.
 */
@QuarkusTest
class CacheEvictionSweepTest extends EvictionFixture {

  @Inject CacheEvictionSweep sweep;
  @Inject EvictionConfig config;
  @Inject Instance<CacheEvictor> evictors;

  @Test
  void oneRunEvictsEveryTypesColdRowsAndUnlinksWhatNothingNamesAnyMore() throws Exception {
    NpmCache npm = seedNpmCache();
    MavenCache maven = seedMavenCache();
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertFalse(report.dryRun());
    // npm: the cold version and its cold document; the warm package keeps both of its rows.
    assertEquals(2, report.type(NpmProxyProfile.KEY).deleted());
    assertEquals(2, report.type(NpmProxyProfile.KEY).kept());
    // maven: the cold file; the warm file and the document above it stay.
    assertEquals(1, report.type(MavenProxyProfile.KEY).deleted());
    assertEquals(2, report.type(MavenProxyProfile.KEY).kept());
    // OCI: the cold tag and the child manifest no tag names.
    assertEquals(2, report.type(OciMirrorProfile.KEY).deleted());
    assertEquals(Duration.ofDays(90), report.type(MavenProxyProfile.KEY).window());

    assertFalse(blobStore.exists(npm.coldTarball()), "the cold tarball lost its last row");
    assertFalse(blobStore.exists(maven.coldJar()), "and so did the cold jar");
    assertTrue(blobStore.exists(npm.warmTarball()), "the warm tarball is still served");
    assertTrue(blobStore.exists(maven.warmJar()));
    assertEquals(2, report.blobs().unlinked());

    // The index row outlived the tag that named it — a tagged manifest is never a candidate of its
    // own — so everything it reaches is still referenced and the sweep leaves it alone. The next
    // run, with the index untagged and cold, is the one that takes it.
    assertTrue(blobStore.exists(mirror.index()), "the untagged index row still names its bytes");
    assertTrue(blobStore.exists(mirror.layer()));
    assertTrue(report.blobs().stillReferenced() > 0);
  }

  @Test
  void bytesTwoTypesShareSurviveTheOneThatLetsGo() throws Exception {
    // The reason a blob sweep exists at all. The store is content-addressed and dedupes globally, so
    // an npm tarball and a cached maven file can be the same bytes; the npm row is cold and the
    // maven row was resolved yesterday. An evictor that freed its own blobs would delete a file the
    // maven cache still serves.
    String shared = store(filled(64, (byte) 42));
    backdate(shared, Duration.ofDays(30));
    Instant longAgo = Instant.now().minus(Duration.ofDays(200));
    npmVersionRow("shared-pkg", "1.0.0", shared, longAgo, longAgo);
    mavenFileRow(
        "eu/wohlben/shared/1.0.0/shared-1.0.0.jar",
        shared,
        64,
        longAgo,
        Instant.now().minus(Duration.ofDays(1)));

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertEquals(1, report.type(NpmProxyProfile.KEY).deleted(), "the cold npm row is gone");
    assertEquals(0, report.blobs().unlinked(), "and its bytes are not, because maven still has them");
    assertTrue(blobStore.exists(shared));
  }

  @Test
  void everyRegisteredEvictorHasAWindowAndAnUnconfiguredTypeIsARefusal() {
    // A type with no window does not fall back to a number: guessing a window is guessing what may
    // be deleted. The loop is what would notice a new evictor shipping without its line of config.
    for (CacheEvictor evictor : evictors) {
      assertTrue(
          config.requireWindow(evictor.typeKey()).toDays() > 0,
          "no window configured for " + evictor.typeKey());
    }
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> config.requireWindow("OCI_IMAGES"));
    assertTrue(refused.getMessage().contains("qits.mirror.eviction.window.oci-images"),
        refused.getMessage());
  }

  @Test
  void theRunLeavesOneLineNamingWhatEachTypeDid() throws Exception {
    seedNpmCache();

    String line = CacheEvictionSweep.summary(sweep.run());

    assertTrue(line.startsWith("Cache eviction:"), line);
    assertTrue(line.contains(NpmProxyProfile.KEY + "=2 condemned/2 deleted/2 kept"), line);
    assertTrue(line.contains("blobs=1 unlinked"), line);
    assertTrue(line.contains(Integer.toString(NPM_COLD_TARBALL) + " bytes"), line);
  }
}
