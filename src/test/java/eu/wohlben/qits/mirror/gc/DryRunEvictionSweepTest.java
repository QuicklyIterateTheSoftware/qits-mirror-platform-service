package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mode a deployment watches the counts in before it agrees with them: plan, report, delete
 * nothing.
 */
@QuarkusTest
@TestProfile(DryRunEvictionSweepTest.Reporting.class)
class DryRunEvictionSweepTest extends EvictionFixture {

  public static class Reporting implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.mirror.eviction.dry-run", "true");
    }
  }

  @Inject CacheEvictionSweep sweep;

  @Test
  void aDryRunSaysWhatWouldGoAndLeavesEveryRowAndEveryByteWhereItIs() throws Exception {
    NpmCache npm = seedNpmCache();

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertTrue(report.dryRun());
    assertEquals(2, report.type(NpmProxyProfile.KEY).condemned(), "the cold version and document");
    assertEquals(0, report.type(NpmProxyProfile.KEY).deleted(), "and nothing was deleted");
    assertEquals(1, report.blobPlan().blobCount(), "one tarball would be reclaimed");
    assertEquals(NPM_COLD_TARBALL, report.blobPlan().reclaimableBytes());
    assertEquals(0, report.blobs().unlinked());

    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isPresent());
    assertTrue(npmProxyPackuments.findOne(NPM_CACHE, NPM_COLD_PACKAGE).isPresent());
    assertTrue(blobStore.exists(npm.coldTarball()));
  }

  @Test
  void theLineSaysItWasADryRunSoNobodyReadsItAsWorkDone() throws Exception {
    seedNpmCache();

    String line = CacheEvictionSweep.summary(sweep.run());

    assertTrue(line.startsWith("Cache eviction (dry run):"), line);
    assertTrue(line.contains("blobs=1 reclaimable"), line);
  }
}
