package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The engine's whole rule: unaccessed past the window dies, everything else stays, and creation
 * counts as the first access.
 *
 * <p>Plain JUnit against {@link FakeCacheEvictor}, deliberately: what is under test is the rule, and
 * driving it through a real type would prove that type's enumeration works instead. The per-format
 * suites do that, against the database.
 */
class CacheEvictionTest {

  private static final Duration WINDOW = Duration.ofDays(30);
  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private final CacheEviction engine = new CacheEviction();

  @Test
  void anIdentityUnaccessedPastTheWindowDiesAndOneInsideItStays() {
    // The rule, in one case. The 31-day entry has not been pulled in a month; the 29-day one has,
    // and one day of difference is what separates them — a cache's whole policy is that comparison.
    FakeCacheEvictor evictor =
        new FakeCacheEvictor()
            .add("hub/library/node:22", daysAgo(31), "cold")
            .add("hub/library/node:24", daysAgo(29), "warm");

    EvictionPlan plan = engine.plan(evictor, WINDOW, NOW);

    assertEquals(List.of("hub/library/node:22"), identities(plan.dead()));
    assertEquals(List.of("hub/library/node:24"), identities(plan.kept()));
    assertEquals(CacheEviction.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(CacheEviction.keptAccessed(WINDOW), plan.kept().get(0).rule());
    assertEquals(Set.of("cold"), plan.blobsReleased());
    assertEquals(Set.of("warm"), plan.blobsRetained());
  }

  @Test
  void somethingCachedTodayAndNeverPulledSinceIsYoungRatherThanNeverRead() {
    // "Creation counts as the first access", which is the difference between a working cache policy
    // and one that deletes everything it just fetched. The evictor folds creation into the access
    // time; this case is what says the engine may rely on that.
    FakeCacheEvictor evictor = new FakeCacheEvictor().add("quay/quarkus:jdk-25", daysAgo(0), "b");

    EvictionPlan plan = engine.plan(evictor, WINDOW, NOW);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("quay/quarkus:jdk-25"), identities(plan.kept()));
  }

  @Test
  void aBlobUnderADyingAndASurvivingIdentityIsInBothSetsBecauseTheEngineNeverSubtracts() {
    // The seam's central promise: the engine reports both sets, and which blob may actually be
    // unlinked stays MirrorBlobSweep's answer across every type at once.
    FakeCacheEvictor evictor =
        new FakeCacheEvictor()
            .add("hub/node:20", daysAgo(90), "base", "old")
            .add("hub/node:24", daysAgo(1), "base", "new");

    EvictionPlan plan = engine.plan(evictor, WINDOW, NOW);

    assertTrue(plan.blobsReleased().contains("base"), "the dying tag did name it");
    assertTrue(plan.blobsRetained().contains("base"), "and the surviving one still does");
  }

  @Test
  void anEmptyTypePlansNothingRatherThanFailing() {
    EvictionPlan plan = engine.plan(new FakeCacheEvictor(), WINDOW, NOW);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  @Test
  void theRuleSentenceCarriesTheConfiguredWindowSoAReportCanBeArguedWith() {
    assertTrue(CacheEviction.rule(WINDOW).contains("P30D"));
    assertTrue(CacheEviction.rule(Duration.ofDays(90)).contains("P90D"));
    // A whole number of days is spelled in days: Duration's own toString would answer PT720H, which
    // is a different number than the one somebody configured.
    assertEquals("P30D", CacheEviction.iso(WINDOW));
    assertEquals("PT6H", CacheEviction.iso(Duration.ofHours(6)));
  }

  @Test
  void theEngineHandsTheEvictorExactlyThePlanItComputed() {
    // The binding contract: eviction removes exactly the identities the plan condemns and adds
    // nothing of its own.
    FakeCacheEvictor evictor =
        new FakeCacheEvictor()
            .add("hub/library/ubi:9", daysAgo(365), "cold")
            .add("hub/library/ubi:10", daysAgo(1), "warm");

    EvictionPlan plan = engine.plan(evictor, WINDOW, NOW);
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    assertEquals(plan, evictor.deleted);
    assertEquals(List.of("hub/library/ubi:9"), identities(applied.deleted()));
  }

  private static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<JudgedIdentity> identities) {
    return identities.stream().map(JudgedIdentity::identity).toList();
  }
}
