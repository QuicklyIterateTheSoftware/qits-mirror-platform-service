package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mirror's live rule: cold content dies, warm content stays, and the mechanisms that keep an
 * eviction from breaking something — the grace window, the funnel's own cleanup, the index closure —
 * are asserted rather than assumed.
 */
@QuarkusTest
class OciMirrorEvictionTest extends EvictionFixture {

  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject OciMirrorEvictor evictor;

  private final CacheEviction engine = new CacheEviction();

  @Test
  void aTagNobodyHasPulledInsideTheWindowDiesAndItsFreshnessRowGoesWithIt() throws Exception {
    // The rule end to end: a cached tag cold for two months is evicted, its row is gone, and so is
    // the oci_mirror_tag_check row the miss path wrote beside it — a freshness row for a tag that no
    // longer exists is a row nothing would ever read or delete again.
    MirrorStore mirror = seedMirror();
    mirrorTagCheck("jdk-25", Instant.now().minus(Duration.ofDays(60)));
    ageMirrorRows(Duration.ofDays(60));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    assertEquals(OciMirrorProfile.KEY, evictor.typeKey());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(plan.dead()),
        "the tag, and the child manifest no tag names — upstream drift leaves both behind");
    assertEquals(List.of(), plan.kept());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    ociTags.getEntityManager().clear();
    assertTrue(
        ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(), "the tag row is gone");
    assertTrue(
        ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).isEmpty(),
        "the untagged child row is gone");
    assertTrue(
        mirrorTagChecks.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(),
        "the freshness row travels with the tag, through the funnel rather than through a caller");
  }

  @Test
  void aTagSpelledLikeAReleaseDiesAllTheSameBecauseTheReleaseIsUpstreams() throws Exception {
    // The one rule this engine deliberately does NOT have. Upstream calls jdk-25 a release; keeping
    // it forever on that basis is how a mirror never shrinks. Version protection is own-ness's, and
    // this service holds nothing of its own.
    seedMirror();
    ageMirrorRows(Duration.ofDays(200));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertTrue(identities(plan.dead()).contains(MIRROR_IMAGE + ":jdk-25"));
  }

  @Test
  void aTagPulledInsideTheWindowStaysAndBothLinesNameTheirRule() throws Exception {
    // The comparison a reviewer reads down the page: one identity kept, one condemned, each saying
    // why. "Kept" alone is not reviewable; "accessed inside the P30D window" is.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now().minus(Duration.ofDays(2)));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MIRROR_IMAGE + ":jdk-25"), identities(plan.kept()));
    assertEquals(CacheEviction.keptAccessed(WINDOW), plan.kept().get(0).rule());
    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertEquals(CacheEviction.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
  }

  @Test
  void aFreshlyCachedImageIsYoungRatherThanNeverPulled() throws Exception {
    // Creation counts as the first access. Without that, everything the mirror ever fetched would be
    // eligible the moment it landed — a cache that deletes what it just paid for.
    seedMirror();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.dead(), "nothing has aged out of a store minutes old");
    assertEquals(2, plan.kept().size(), "the tag and the untagged child");
    assertEquals(
        census.take().live(OciMirrorProfile.KEY).keySet(),
        plan.blobsRetained(),
        "with nothing condemned, what this type retains is the census's own live set for it");
  }

  @Test
  void aChildManifestOfAKeptIndexIsEvictableAndItsBytesSurviveAnyway() throws Exception {
    // The lazy-pull bargain, stated as a test. An architecture nobody pulls ages out; the index that
    // lists it stays; and the child's bytes stay reachable through the index's closure, so the sweep
    // never unlinks them. Re-fetching that architecture costs one upstream request.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now());

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertTrue(plan.blobsReleased().contains(mirror.child()), "the dying row did name those bytes");
    assertTrue(
        plan.blobsRetained().contains(mirror.child()),
        "and the surviving index still does — the sweep subtracts, a plan never does");
    assertTrue(plan.blobsRetained().contains(mirror.index()));
  }

  @Test
  void anIdentityOverAYoungBlobIsWithheldWholeWithItsRowsIntact() throws Exception {
    // The strand hazard, carried over to this type: deleting a row while its file is inside the
    // grace window would leave the blob row-less — untouchable by construction, therefore never
    // reclaimed. The identity waits out the window with its file.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> blobId.equals(mirror.layer()));

    assertEquals(List.of(), applied.deleted(), "the layer under both identities is too young");
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), applied.errors(), "withheld is the window working, not an error");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent(), "the row stays");
  }

  @Test
  void aStoreWithNoMirroredImageIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a deployment that has not pulled anything through yet. The three mirror
    // namespaces are there from V1 and hold nothing.
    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.kept());
    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(), plan.blobsRetained());
  }
}
