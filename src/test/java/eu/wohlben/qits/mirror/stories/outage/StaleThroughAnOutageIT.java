package eu.wohlben.qits.mirror.stories.outage;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.mirror.stories.support.NpmUpstreamFixture;
import eu.wohlben.qits.mirror.stories.support.RecordingUpstream;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The half of the bargain nobody buys a mirror for and everybody needs it for: the day npmjs is
 * not there.</b>
 *
 * <p>A pull-through cache that only helps while its upstream is healthy is a bandwidth optimisation.
 * What makes it infrastructure is what it does when the registry is <em>gone</em> — and there are
 * three different right answers, which this pair separates:
 *
 * <ul>
 *   <li>An <b>immutable</b> thing that is cached is served, with nothing dialled at all. A tarball
 *       is content-addressed; there is no question to ask anybody.
 *   <li>A <b>mutable</b> document past its TTL is served <b>stale</b>, after the revalidation fails.
 *       That is the branch {@code NpmUpstream.serveStaleOrFail} exists for and the reason CI keeps
 *       installing through an npmjs outage: a document that might be slightly old beats a build that
 *       does not run.
 *   <li>A thing that was <b>never cached</b> is a 502 that says so. A mirror may not invent bytes it
 *       has never seen, and it may not call their absence a 404 either.
 * </ul>
 *
 * <h2>{@code @TestMethodOrder} is load-bearing, and here it is the whole experiment</h2>
 *
 * <p>The second story asserts a <b>state change</b>, and a state change is only observable against a
 * state. The first story establishes it: a package pulled through while the registry was still
 * answering, with the registry's recording as evidence of exactly what that cost. The second then
 * turns the registry dark and watches what stops working, what keeps working, and — the part that
 * needs the clock — what starts costing a dial that used to cost nothing.
 *
 * <h2>Proving a TTL expired, without asking anything that knows</h2>
 *
 * <p>Nothing on this service's surface reports whether a cached packument is fresh. There is no
 * admin read for it and there should not be; freshness is an implementation of a promise, not a
 * fact a client is owed. So the second story proves expiry the only honest way there is: by
 * <b>polling until the old behaviour stops</b>. Inside the TTL a packument read costs the registry
 * nothing, and the registry's recording says so. Past it, every read costs one dial — which, with
 * the registry dark, is a dial that goes nowhere and is recorded as {@code dropped}. The moment that
 * count moves is the moment the TTL expired, measured on the far side rather than asserted from a
 * timer here.
 *
 * <p>And the client never noticed: every one of those reads answered 200 with the identical
 * document. That is the claim.
 *
 * <p>{@link StoryProfile#PACKUMENT_TTL} is what makes the poll bounded. See that field's comment —
 * it is a seam shared with the two warm-read stories in this catalogue, and it is deliberately the
 * one number both sides read.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StaleThroughAnOutageIT {

  static final String CATEGORY = "outage";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String WARM_STORY = "A build warms the cache while the registry is still answering";

  static final String WARM_SLUG = Slugs.slug(WARM_STORY);

  static final String DARK_STORY =
      "The registry goes dark and the mirror keeps the build running on what it holds";

  static final String DARK_SLUG = Slugs.slug(DARK_STORY);

  /** This class's own package. Nothing else in the catalogue touches it. */
  static final String PACKAGE = "story-outage-install";

  /** The version this pair pulls through while the registry is up. */
  static final String CACHED_VERSION = "3.1.0";

  /** Named in the same packument and never pulled — the "never cached" arm of the dark story. */
  static final String UNCACHED_VERSION = "3.2.0";

  private static final String BUILD = "a build container";

  private static final String BUILD_IN_THE_DARK = "a build during the outage";

  /** How long to wait for the packument TTL to expire before giving up on the experiment. */
  private static final Duration EXPIRY_PATIENCE = StoryProfile.PACKUMENT_TTL.plusSeconds(45);

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private static TinyPackage upstreamArchive;

  private static String warmIndex;

  private static byte[] warmTarballBytes;

  private static final String SERVED_INDEX = StoryTarget.NPM_BASE + "/" + PACKAGE;

  private static final String SERVED_CACHED_TARBALL =
      StoryTarget.NPM_BASE + NpmUpstreamFixture.tarballPath(PACKAGE, CACHED_VERSION);

  private static final String SERVED_UNCACHED_TARBALL =
      StoryTarget.NPM_BASE + NpmUpstreamFixture.tarballPath(PACKAGE, UNCACHED_VERSION);

  private static final String UPSTREAM_INDEX = NpmUpstreamFixture.packumentPath(PACKAGE);

  private static final String UPSTREAM_CACHED_TARBALL =
      NpmUpstreamFixture.tarballPath(PACKAGE, CACHED_VERSION);

  private static final String UPSTREAM_UNCACHED_TARBALL =
      NpmUpstreamFixture.tarballPath(PACKAGE, UNCACHED_VERSION);

  @BeforeAll
  static void tapBothEndsAndHostTwoVersions() {
    StoryNetwork.install();
    upstreamArchive =
        NpmUpstreamFixture.hostTwoVersionsWithOneArchive(
            registry(), PACKAGE, CACHED_VERSION, UNCACHED_VERSION);
  }

  private static RecordingUpstream registry() {
    return RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM);
  }

  @UserStory(value = WARM_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The ordinary day, recorded so the extraordinary one has something to be different from.

      A build pulls one version of a package through: the index, then the archive that index
      points at. Two upstream reads, which is what a cold miss costs and all it costs.

      The packument names a SECOND version too, and nothing here fetches it. That is deliberate
      and it is the setup for what follows: when the registry goes away, the difference between a
      version this cache holds and a version it merely knows the name of is the difference between
      a build that runs and a build that is told the truth about why it cannot.
      """)
  @Order(1)
  void aBuildWarmsTheCacheWhileTheRegistryAnswers(Interactions story, Network net) {
    RecordingUpstream registry = registry();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument, the version row and the tarball bytes");

    NetworkCapture.actor(BUILD);

    warmIndex =
        given()
            .header("Accept", "application/vnd.npm.install-v1+json, application/json")
            .get(SERVED_INDEX)
            .then()
            .statusCode(200)
            .contentType(startsWith("application/json"))
            .extract()
            .asString();
    assertTrue(
        warmIndex.contains("\"" + UNCACHED_VERSION + "\""),
        "the packument must name both versions — the second one is the setup for the dark story");
    assertEquals(1, registry.requestsTo(UPSTREAM_INDEX), "a cold index is one upstream read");

    warmTarballBytes =
        given().get(SERVED_CACHED_TARBALL).then().statusCode(200).extract().asByteArray();
    assertArrayEquals(
        upstreamArchive.tarball(),
        warmTarballBytes,
        "the client must get UPSTREAM's bytes, not something assembled here");
    assertEquals(
        1, registry.requestsTo(UPSTREAM_CACHED_TARBALL), "the archive miss costs one fetch");
    assertEquals(
        0,
        registry.requestsTo(UPSTREAM_UNCACHED_TARBALL),
        "and nothing fetched the version nobody asked for — a mirror holds what somebody pulled");

    story
        .note(
            "one version pulled through: the index, then the archive it names. Two upstream reads,"
                + " which is what a cold miss costs")
        .as("one-version-warmed");
    story
        .note(
            "the packument names a second version and nothing fetched it — which is the difference"
                + " the outage is about to make visible")
        .as("the-other-version-is-only-a-name");
  }

  @UserStory(value = DARK_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The registry stops answering. Not with an error — a registry with an opinion is a registry
      that is there — but by dropping the connection, which is what an outage looks like to the
      HTTP client inside this service.

      Nothing about the build changes at first, and that is the first claim: the index is inside
      its TTL and the archive is immutable, so both come back byte for byte and this service has
      no reason to dial anybody.

      Then the build asks for the version this cache has never held. That one cannot be invented,
      so the archive is dialled — into the dark — and the answer is a 502 that names the package
      and the version. Never a 404: the version demonstrably exists, upstream said so in the
      document being served right now, and a 404 would teach every client that asked otherwise.

      And then the clock runs out. Past the TTL a packument is due for revalidation, and the
      revalidation goes nowhere. The story waits for exactly that moment — not by trusting a timer
      but by watching the registry's own recording until a read that used to cost nothing starts
      costing a dropped dial — and then asserts the thing the whole branch exists for: the client
      still got 200, and still got the same document. Slightly old beats not building.
      """)
  @Order(2)
  void theRegistryGoesDarkAndTheBuildKeepsRunning(Interactions story, Network net) {
    RecordingUpstream registry = registry();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument and the tarball bytes");

    NetworkCapture.actor(BUILD_IN_THE_DARK);
    registry.reachable(false);
    story
        .note(
            "the registry stops answering — the connection goes away with no status at all, which"
                + " is what an outage is and what a 500 is not")
        .as("the-registry-is-gone");

    // --- what is cached and fresh: nothing is dialled at all --------------------------------------
    long indexReadsBefore = registry.requestsTo(UPSTREAM_INDEX);

    String index = given().get(SERVED_INDEX).then().statusCode(200).extract().asString();
    assertEquals(warmIndex, index, "inside its TTL the cached document is served unchanged");
    assertArrayEquals(
        warmTarballBytes,
        given().get(SERVED_CACHED_TARBALL).then().statusCode(200).extract().asByteArray(),
        "an immutable archive that is cached needs nobody's permission to be served");
    assertEquals(
        indexReadsBefore,
        registry.requestsTo(UPSTREAM_INDEX),
        "inside the TTL there is nothing to revalidate, so the dark registry was not even tried");
    story
        .note(
            "the build installs exactly as before: a fresh index and an immutable archive, both"
                + " from this process, with nothing dialled at all")
        .as("what-is-cached-still-installs");

    // --- what was never cached: dialled, and honestly refused --------------------------------------
    String refusal =
        given()
            .get(SERVED_UNCACHED_TARBALL)
            .then()
            .statusCode(502)
            .contentType(startsWith("application/json"))
            .extract()
            .path("error");
    assertTrue(
        refusal.contains("unreachable")
            && refusal.contains(PACKAGE + "@" + UNCACHED_VERSION)
            && refusal.contains("not cached"),
        "a 502 must name what could not be reached and what was wanted: " + refusal);
    assertTrue(
        registry.requestsTo(UPSTREAM_UNCACHED_TARBALL) >= 1,
        "the archive was genuinely dialled — the refusal is measured, not assumed");
    assertEquals(
        RecordingUpstream.DROPPED,
        registry.recordedRequests().stream()
            .filter(request -> UPSTREAM_UNCACHED_TARBALL.equals(request.path()))
            .reduce((first, second) -> second)
            .orElseThrow()
            .status(),
        "and the dial went into the dark, which is why the answer is a 502 and not a 404");
    story
        .note(
            "the version this cache never held is dialled anyway, into the dark, and refused with"
                + " a 502 that names it — never a 404, which would be a lie about what exists")
        .as("what-was-never-cached-says-so");

    // --- and the clock: the old behaviour STOPS -----------------------------------------------------
    // The proof of expiry is the disappearance of "a read costs nothing", not the arrival of
    // anything. Poll until the registry's recording says a revalidation was attempted; every answer
    // along the way must still be the same 200 document, which is the whole point of the branch.
    long deadline = System.nanoTime() + EXPIRY_PATIENCE.toNanos();
    long revalidations;
    do {
      assertEquals(
          warmIndex,
          given().get(SERVED_INDEX).then().statusCode(200).extract().asString(),
          "a read during the outage must answer the cached document, fresh or stale");
      revalidations = registry.requestsTo(UPSTREAM_INDEX) - indexReadsBefore;
      if (revalidations > 0) {
        break;
      }
      assertTrue(
          System.nanoTime() < deadline,
          "the packument TTL ("
              + StoryProfile.PACKUMENT_TTL
              + ") never expired: a read that used to cost the registry nothing still costs it"
              + " nothing, so this story could not observe the state change it is about");
      sleep(POLL_INTERVAL);
    } while (true);

    story
        .note(
            "past the TTL the index is due for revalidation, and the revalidation goes nowhere:"
                + " what used to cost the registry nothing now costs it a dropped dial. That is"
                + " how this story knows the clock ran out — the old behaviour stopped")
        .as("the-ttl-ran-out");
    story
        .note(
            "and the build never noticed. The stale document is served with a 200, character for"
                + " character the one from before the outage, which is why CI keeps installing"
                + " through a registry that is not there")
        .as("stale-beats-not-building");
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the packument TTL", interrupted);
    }
  }

  @AfterAll
  static void restoreTheRegistryAndCheckTheReports() {
    // The registry is shared with every other class in this catalogue and this is the only story
    // that darkens it. Restoring it here, rather than at the end of the story body, keeps the
    // outage in force for the whole of the story's own drain.
    RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM).reachable(true);

    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_CACHED_TARBALL, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_INDEX, "200"));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_CACHED_TARBALL, "200"));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument, the version row and the tarball bytes");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, WARM_SLUG, 5);
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "one-version-warmed");
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, WARM_SLUG, "the-other-version-is-only-a-name");

    ReportAssertions.assertComplete(CATEGORY_SLUG, DARK_SLUG, UserflowReport.PASSED);
    // Three requests in — however many times the poll repeated the first one, an edge is a
    // quadruple and the label is a constant, so a nondeterministic read count draws one arrow.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.HTTP,
        BUILD_IN_THE_DARK,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.HTTP,
        BUILD_IN_THE_DARK,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_CACHED_TARBALL, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.HTTP,
        BUILD_IN_THE_DARK,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_UNCACHED_TARBALL, 502));
    // And two out, both of which are the OUTAGE itself drawn as evidence: a dial that was made and
    // was not answered. This is the one catalogue where an arrow to the upstream is the good news.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_UNCACHED_TARBALL, RecordingUpstream.DROPPED));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_INDEX, RecordingUpstream.DROPPED));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        DARK_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument and the tarball bytes");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, DARK_SLUG, 6);
    ReportAssertions.assertStepId(CATEGORY_SLUG, DARK_SLUG, "the-registry-is-gone");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DARK_SLUG, "what-is-cached-still-installs");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DARK_SLUG, "what-was-never-cached-says-so");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DARK_SLUG, "the-ttl-ran-out");
    ReportAssertions.assertStepId(CATEGORY_SLUG, DARK_SLUG, "stale-beats-not-building");
  }
}
