package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The npm cache's live rule: cold versions and their cold documents go, and <b>no tombstone is
 * written</b>.
 *
 * <p>Three things are on trial and each one is a way this type can go wrong. The <b>scope</b>: the
 * format tables are shared, so an enumeration that leaked would put another type's rows under this
 * one's window. The <b>staleness rule</b> for a packument, which has to fold in its versions' access
 * or it evicts the document of a package something is actively installing. And the <b>missing
 * tombstone</b>, which is what makes an eviction a cache decision rather than an unpublish of
 * somebody else's package.
 */
@QuarkusTest
class NpmProxyEvictionTest extends EvictionFixture {

  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject NpmProxyEvictor evictor;

  private final CacheEviction engine = new CacheEviction();

  @Test
  void onlyTheNpmCachesRowsAreEnumerated() throws Exception {
    // The scope, asserted over a store holding all three formats. Nothing but the npm cache's own
    // identities may appear in this type's plan at all — not as dead, and not as kept either, since
    // a keep here would mean the enumeration reached rows another type owns.
    seedNpmCache();
    seedMavenCache();
    seedMirror();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(NpmProxyProfile.KEY, evictor.typeKey());
    assertEquals(
        List.of(
            NPM_WARM_PACKAGE + NpmProxyEvictor.PACKUMENT,
            NPM_WARM_PACKAGE + "@5.3.0",
            NPM_COLD_PACKAGE + NpmProxyEvictor.PACKUMENT,
            NPM_COLD_PACKAGE + "@1.3.0"),
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .map(JudgedIdentity::identity)
            .sorted()
            .toList());
    assertTrue(
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .allMatch(identity -> NPM_CACHE.equals(identity.repository())),
        "every identity is the npm cache's; the other two formats are another evictor's business");
    assertTrue(mavenArtifacts.findOne(MAVEN_CACHE, MAVEN_COLD_PATH).isPresent());
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent());
  }

  @Test
  void aColdPackageLosesItsVersionAndItsDocumentAndAWarmOneKeepsBoth() throws Exception {
    // The rule and the staleness rule in one case. left-pad was last installed 200 days ago, so both
    // its rows go. chalk's DOCUMENT was last revalidated 200 days ago too — but its tarball was
    // pulled yesterday, and a packument judged on fetched_at alone would evict the document of a
    // package something is actively installing.
    seedNpmCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(
        List.of(NPM_COLD_PACKAGE + NpmProxyEvictor.PACKUMENT, NPM_COLD_PACKAGE + "@1.3.0"),
        identities(plan.dead()));
    assertEquals(
        List.of(NPM_WARM_PACKAGE + NpmProxyEvictor.PACKUMENT, NPM_WARM_PACKAGE + "@5.3.0"),
        identities(plan.kept()));
    assertTrue(
        plan.dead().stream()
            .allMatch(dead -> CacheEviction.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertTrue(
        plan.kept().stream()
            .allMatch(kept -> CacheEviction.keptAccessed(WINDOW).equals(kept.rule())));
  }

  @Test
  void anEvictedVersionLeavesNoTombstoneBecauseReFetchingItIsThePoint() throws Exception {
    // The assertion this type exists to make. A tombstone records "this name is spent forever",
    // which is what a hosted registry owes its consumers and the opposite of what a cache owes: the
    // version is upstream's, and the next install must be able to pull it through again.
    NpmCache cache = seedNpmCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    assertEquals(
        List.of(NPM_COLD_PACKAGE + NpmProxyEvictor.PACKUMENT, NPM_COLD_PACKAGE + "@1.3.0"),
        identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isEmpty());
    assertTrue(npmProxyPackuments.findOne(NPM_CACHE, NPM_COLD_PACKAGE).isEmpty());
    assertTrue(
        npmVersionTombstones.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isEmpty(),
        "no tombstone: a tombstoned cache entry would refuse the re-cache this service exists for");
    assertEquals(0, npmVersionTombstones.count(), "and none anywhere else either");
    assertTrue(
        npmVersions.findOne(NPM_CACHE, NPM_WARM_PACKAGE, "5.3.0").isPresent(),
        "the warm package is untouched");
    assertTrue(
        blobStore.exists(cache.coldTarball()), "the blob is the sweep's question, not this one");
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsItsVersionRowIntact() throws Exception {
    // The strand hazard on this type: a row deleted over a young file would leave the file row-less,
    // and row-less is untouchable by construction. A packument names no file, so it is never
    // withheld — there is nothing for the window to protect.
    seedNpmCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> true);

    assertEquals(List.of(NPM_COLD_PACKAGE + "@1.3.0"), identities(applied.withheldByGraceWindow()));
    assertEquals(
        List.of(NPM_COLD_PACKAGE + NpmProxyEvictor.PACKUMENT), identities(applied.deleted()));
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isPresent());
  }

  @Test
  void theNoteSaysWhatEvictingADocumentDoesNotFree() throws Exception {
    // Without it a reviewer reads "0 reclaimed bytes" beside a hundred condemned packuments and
    // concludes the collector is broken. The characters leave the table; the data file shrinks only
    // under a VACUUM FULL, which nothing here runs.
    seedNpmCache();

    String note = evictor.note();

    assertTrue(note.contains("VACUUM FULL"), note);
    assertTrue(note.contains("0 bytes"), note);
    assertTrue(note.contains("characters"), note);
  }

  @Test
  void aCacheWithNothingInItIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a deployment nothing has installed through yet.
    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertFalse(plan.blobsRetained().contains("anything"));
  }
}
