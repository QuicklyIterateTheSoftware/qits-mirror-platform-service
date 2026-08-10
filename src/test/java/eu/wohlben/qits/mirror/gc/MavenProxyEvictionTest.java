package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The maven cache's live rule: cold files and their cold documents go, one at a time.
 *
 * <p>Three things are on trial and each one is a way this type can go wrong. The <b>scope</b>: the
 * format tables are shared, so an enumeration that leaked would put another type's rows under this
 * one's window. The <b>staleness rule</b> for a document, which folds in the access of the files
 * under its directory or it evicts the metadata of an artifact something is actively building
 * against. And the <b>identity being a path</b> rather than a coordinate: a cache repairs itself on
 * the next request, so there is no half-version to prevent and a warm sibling neither saves a cold
 * file nor is dragged out by it.
 */
@QuarkusTest
class MavenProxyEvictionTest extends EvictionFixture {

  private static final Duration WINDOW = Duration.ofDays(90);

  @Inject MavenProxyEvictor evictor;

  private final CacheEviction engine = new CacheEviction();

  @Test
  void onlyTheMavenCachesRowsAreEnumerated() throws Exception {
    seedNpmCache();
    seedMavenCache();
    seedMirror();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(MavenProxyProfile.KEY, evictor.typeKey());
    assertEquals(
        List.of(MAVEN_COLD_PATH, MAVEN_WARM_PATH, MAVEN_METADATA_PATH + MavenProxyEvictor.METADATA),
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .map(JudgedIdentity::identity)
            .sorted()
            .toList());
    assertTrue(
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .allMatch(identity -> MAVEN_CACHE.equals(identity.repository())),
        "every identity is the maven cache's; the other two formats are another evictor's business");
    assertTrue(npmVersions.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isPresent());
  }

  @Test
  void aColdFileGoesAndAWarmOneKeepsItselfAndTheDocumentAboveIt() throws Exception {
    // The rule and the staleness rule in one case. The 1.7.36 jar was last resolved 200 days ago, so
    // it goes. The DOCUMENT was last revalidated 200 days ago too — but the 2.0.13 jar under it was
    // resolved yesterday, and a document judged on fetched_at alone would be evicted out from under
    // an artifact something is actively building against.
    seedMavenCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MAVEN_COLD_PATH), identities(plan.dead()));
    assertEquals(
        List.of(MAVEN_WARM_PATH, MAVEN_METADATA_PATH + MavenProxyEvictor.METADATA),
        identities(plan.kept()));
    assertTrue(
        plan.dead().stream()
            .allMatch(dead -> CacheEviction.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertTrue(
        plan.kept().stream()
            .allMatch(kept -> CacheEviction.keptAccessed(WINDOW).equals(kept.rule())));
  }

  @Test
  void oneColdFileGoesWithoutDraggingItsWarmSiblingWithIt() throws Exception {
    // The deliberate difference from a hosted maven repository, whose identity is a whole coordinate
    // because half a published version is a broken resolve nothing can repair. Here the next request
    // re-fetches whatever is missing, so a file is the unit.
    MavenCache cache = seedMavenCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    assertEquals(List.of(MAVEN_COLD_PATH), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_CACHE, MAVEN_COLD_PATH).isEmpty());
    assertTrue(
        mavenArtifacts.findOne(MAVEN_CACHE, MAVEN_WARM_PATH).isPresent(),
        "the warm file is untouched");
    assertTrue(
        mavenProxyMetadata.findOne(MAVEN_CACHE, MAVEN_METADATA_PATH).isPresent(),
        "and so is the document a resolver still reads");
    assertTrue(blobStore.exists(cache.coldJar()), "the blob is the sweep's question, not this one");
  }

  @Test
  void aFileInsideTheGraceWindowWithholdsItsRowIntact() throws Exception {
    // The strand hazard on this type: a row deleted over a young file would leave the file row-less,
    // and row-less is untouchable by construction. A document names no file, so it is never withheld
    // — there is nothing for the window to protect.
    seedMavenCache();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> true);

    assertEquals(List.of(MAVEN_COLD_PATH), identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), identities(applied.deleted()));
    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_CACHE, MAVEN_COLD_PATH).isPresent());
  }

  @Test
  void theNoteSaysWhatEvictingADocumentDoesNotFree() throws Exception {
    seedMavenCache();

    String note = evictor.note();

    assertTrue(note.contains("VACUUM FULL"), note);
    assertTrue(note.contains("0 bytes"), note);
    assertTrue(note.contains("characters"), note);
  }

  @Test
  void aCacheWithNothingInItIsAnEmptyPlanRatherThanAFailure() throws Exception {
    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertFalse(plan.blobsRetained().contains("anything"));
  }

  @Test
  void whatTheTypeRetainsIsTheCensusOwnLiveSetWhenNothingDies() throws Exception {
    // The vocabulary check: the two blob sets a plan returns are the census's, which is what the
    // blob sweep reconciles over.
    seedMavenCache();

    EvictionPlan plan = engine.plan(evictor, Duration.ofDays(3650), Instant.now());

    assertEquals(List.of(), identities(plan.dead()));
    assertEquals(census.take().live(MavenProxyProfile.KEY).keySet(), plan.blobsRetained());
  }
}
