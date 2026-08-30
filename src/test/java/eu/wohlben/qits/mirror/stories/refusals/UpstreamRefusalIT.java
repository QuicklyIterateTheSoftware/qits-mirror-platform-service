package eu.wohlben.qits.mirror.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.mirror.stories.support.NpmUpstreamFixture;
import eu.wohlben.qits.mirror.stories.support.RecordingUpstream;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The two ways of having nothing to give, and why this service is the worst place on the platform
 * to confuse them.</b>
 *
 * <p>Everything this mirror can say about what exists, it says on somebody else's authority. So a
 * refusal here is not a status code, it is a <em>sentence about upstream</em> — and the two sentences
 * are opposites:
 *
 * <ul>
 *   <li><b>"Upstream has never heard of it."</b> A 404, passed through, and <b>written down
 *       nowhere</b>. Remembering a no is the one optimisation this cache must never make: a package
 *       published a minute ago has to be installable a minute later, which any negative TTL at all
 *       would break.
 *   <li><b>"Upstream could not answer."</b> A 502 naming the version, because the index resolved and
 *       the version demonstrably exists — only the bytes were out of reach. Collapsing that into a
 *       404 teaches the whole fleet that the version does not exist, which npm caches as "no such
 *       version" and which is exactly the failure a mirror is bought to prevent.
 * </ul>
 *
 * <p>Both refusals are JSON, in npm's own {@code {"error": …}} envelope — never Quarkus' HTML error
 * page and never an empty packument. The client here is a machine that parses what it gets, and a
 * page rendered into a document slot is a corrupt-response error somebody debugs a long way from the
 * cause.
 *
 * <h2>Order is not load-bearing here, and namespacing is why</h2>
 *
 * <p>Unlike the caching pair, these two stories share no state: each owns a package name of its own
 * and counts only its own upstream paths, so neither can be satisfied or spoiled by the other's
 * traffic whichever runs first. That is the discipline every class in this catalogue keeps except
 * where a warm read genuinely needs a cold one before it.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class UpstreamRefusalIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String NO_STORY = "A package upstream never heard of is refused and remembered nowhere";

  static final String NO_SLUG = Slugs.slug(NO_STORY);

  static final String OUTAGE_STORY = "An upstream that cannot answer is a 502 and never a 404";

  static final String OUTAGE_SLUG = Slugs.slug(OUTAGE_STORY);

  /** No stub is ever registered for it: an unregistered route is the registry's own 404. */
  static final String UNKNOWN = "story-never-published";

  /**
   * A package whose packument resolves and whose bytes do not: its {@code dist.tarball} names a
   * closed port, which is what an upstream outage looks like to a {@code java.net.http.HttpClient}.
   */
  static final String UNREACHABLE = "story-bytes-out-of-reach";

  static final String UNREACHABLE_VERSION = "2.0.0";

  /** Refuses at once rather than hanging — the suite's own offline spelling. */
  static final String CLOSED_PORT = "http://127.0.0.1:1";

  private static final String BUILD = "a build container";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SERVED_UNKNOWN = StoryTarget.NPM_BASE + "/" + UNKNOWN;

  private static final String SERVED_UNREACHABLE = StoryTarget.NPM_BASE + "/" + UNREACHABLE;

  private static final String SERVED_UNREACHABLE_TARBALL =
      StoryTarget.NPM_BASE + NpmUpstreamFixture.tarballPath(UNREACHABLE, UNREACHABLE_VERSION);

  private static final String NO_ROUTE = StoryTarget.NPM_BASE + "/-/v1/search";

  @BeforeAll
  static void tapBothEndsAndHostTheHalfPackage() {
    StoryNetwork.install();
    NpmUpstreamFixture.hostWithUnreachableArchive(
        registry(), UNREACHABLE, UNREACHABLE_VERSION, CLOSED_PORT);
  }

  private static RecordingUpstream registry() {
    return RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM);
  }

  @UserStory(value = NO_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A build asks for a package that does not exist. Upstream says so, and this service says
      exactly what upstream said — in npm's own `{"error": …}` envelope, so the client parses a
      refusal rather than an HTML page.

      Then it asks again, and the whole point of the story is what happens next: upstream is asked
      AGAIN. Nothing was written down. That is not an optimisation left undone — it is the
      difference between a cache and a rumour. A negative entry with any TTL at all would make a
      package published a minute ago uninstallable for the length of that TTL, on every machine
      that had asked before it existed.

      The proof is the shape a state change always needs: the old behaviour must be observed
      CONTINUING rather than a new one arriving. One ask reached upstream, and the second ask
      reached it too — so nothing in between decided it already knew the answer.

      And a path with no handler behind it answers the same envelope. The SPA fallback sits in
      front of these routes now that the client is served at the root, so "a machine path is a
      machine refusal" is a claim worth making out loud.
      """)
  void aNoIsPassedThroughAndRememberedNowhere(
      Interactions story, eu.wohlben.qits.userflows.Network net) {
    RecordingUpstream registry = registry();

    // Looked up and NOT written to, which is the story: the cache is asked whether it holds this
    // package and the answer is never turned into a row. No tap can see a JDBC connection, so the
    // one dependency that is not on the diagram by observation is declared.
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "look for a cached packument, and write none");

    // Every request this story makes is a build's, so the actor is set once, up front. (It has to
    // be set at all: the framework resets the actor at every story start, so nothing another story
    // named can leak in here.)
    NetworkCapture.actor(BUILD);

    long before = registry.requestsTo(NpmUpstreamFixture.packumentPath(UNKNOWN));

    given()
        .get(SERVED_UNKNOWN)
        .then()
        .statusCode(404)
        .contentType(startsWith("application/json"))
        .body("error", equalTo("no such package upstream: " + UNKNOWN));
    assertEquals(
        before + 1,
        registry.requestsTo(NpmUpstreamFixture.packumentPath(UNKNOWN)),
        "the 404 must be UPSTREAM's answer, not a guess made here");
    story
        .note(
            "the refusal reaches the client in npm's own {\"error\": …} envelope, never an HTML"
                + " error page — and it is UPSTREAM's 404, passed through")
        .as("unknown-package-refused");

    // The second ask. There is no negative entry to serve and no TTL to wait out, which is what
    // makes a package published a minute ago installable a minute later.
    given().get(SERVED_UNKNOWN).then().statusCode(404);
    assertEquals(
        before + 2,
        registry.requestsTo(NpmUpstreamFixture.packumentPath(UNKNOWN)),
        "a cache must never remember a no — the second ask must reach upstream too");
    story
        .note(
            "asked a second time, the same 404 reached upstream a second time: nothing was cached,"
                + " which is what lets a package published a minute ago install a minute later")
        .as("the-no-was-not-remembered");

    // A path with no handler behind it, on the machine plane. NpmRoutes' own catch-all answers
    // npm's envelope rather than Vert.x' HTML page — and note what this does NOT pin:
    // `quarkus.quinoa.ignored-path-prefixes`, the list that keeps the SPA fallback off a machine
    // path, is not under test here because this pipeline builds with -Dquarkus.quinoa=false and the
    // launched artifact carries no client at all.
    given()
        .get(NO_ROUTE)
        .then()
        .statusCode(404)
        .contentType(startsWith("application/json"))
        .body("error", startsWith("not a route this npm registry serves"));
    story
        .note(
            "a path with no handler behind it answers npm's envelope too — 404 JSON, never Vert.x'"
                + " HTML page")
        .as("a-machine-path-answers-json");
  }

  @UserStory(value = OUTAGE_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The other refusal, and the one that costs the most when it is spelled wrong.

      A package whose index resolves and whose bytes do not. The version demonstrably exists —
      upstream said so, in the document this service just served — and only the archive is out of
      reach, behind an address that refuses the connection.

      So the answer is a 502 that names the package and the version, and never a 404. A 404 here
      would be a lie the whole fleet believes: npm caches "no such version" and keeps believing it
      long after the address comes back, and so do maven and every docker client that asked.

      What proves it was an outage rather than a refusal is the registry's own recording. It was
      asked for the index, once, and answered. It was never asked for the archive at all — the
      dial went to the dead address the packument named, which is a place this recording cannot
      see. The absence in the diagram and the count beside it are the same fact told twice.
      """)
  void anOutageIsNeverANo(Interactions story, eu.wohlben.qits.userflows.Network net) {
    RecordingUpstream registry = registry();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument; store no bytes, because none arrived");
    NetworkCapture.actor(BUILD);

    String index =
        given().get(SERVED_UNREACHABLE).then().statusCode(200).extract().asString();
    assertEquals(
        UNREACHABLE_VERSION,
        parse(index),
        "the index must resolve — it is what makes the refusal below an outage and not a no");

    String refusal =
        given()
            .get(SERVED_UNREACHABLE_TARBALL)
            .then()
            .statusCode(502)
            .contentType(startsWith("application/json"))
            .extract()
            .path("error");
    assertTrue(
        refusal.contains("unreachable")
            && refusal.contains(UNREACHABLE + "@" + UNREACHABLE_VERSION)
            && refusal.contains("not cached"),
        "a 502 must say the bytes could not be reached and name what was wanted: " + refusal);

    assertEquals(
        1,
        registry.requestsTo(NpmUpstreamFixture.packumentPath(UNREACHABLE)),
        "the index was read from upstream once");
    assertEquals(
        0,
        registry.requestsTo(NpmUpstreamFixture.tarballPath(UNREACHABLE, UNREACHABLE_VERSION)),
        "the archive fetch must have gone to the dead address the packument named");

    story
        .note(
            "the bytes are a 502 that names the package and the version — never a 404, which the"
                + " whole fleet would cache as \"no such version\"")
        .as("an-outage-is-a-502");
    story
        .note(
            "upstream answered the index and was never asked for the archive at all: the dial went"
                + " to the dead address the packument named, which is what makes this an outage")
        .as("the-index-was-fine");
  }

  /** {@code dist-tags.latest}, read with Jackson — Groovy reads a hyphen as subtraction. */
  private static String parse(String body) {
    try {
      return JSON.readTree(body).path("dist-tags").path("latest").asText();
    } catch (Exception notJson) {
      throw new IllegalStateException("not a JSON document: " + body, notJson);
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, NO_SLUG, UserflowReport.PASSED);
    // Both asks for the unknown package are ONE edge on each end — same actor, same route, same
    // status up and down, and dedup is on the whole quadruple. The count that distinguishes them is
    // the assertion over the registry's recording, with a note beside it. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        NO_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_UNKNOWN, 404));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        NO_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", NO_ROUTE, 404));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        NO_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", NpmUpstreamFixture.packumentPath(UNKNOWN), "404"));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        NO_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "look for a cached packument, and write none");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, NO_SLUG, 4);
    ReportAssertions.assertStepId(CATEGORY_SLUG, NO_SLUG, "unknown-package-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, NO_SLUG, "the-no-was-not-remembered");
    ReportAssertions.assertStepId(CATEGORY_SLUG, NO_SLUG, "a-machine-path-answers-json");

    ReportAssertions.assertComplete(CATEGORY_SLUG, OUTAGE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_UNREACHABLE, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_UNREACHABLE_TARBALL, 502));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", NpmUpstreamFixture.packumentPath(UNREACHABLE), "200"));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument; store no bytes, because none arrived");
    // Four, and no fifth — in particular no edge to the registry for the archive, which is the
    // whole of what makes the 502 an outage rather than a refusal. An absence is invisible to every
    // presence check above; the count is where it becomes a proof.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, OUTAGE_SLUG, 4);
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "an-outage-is-a-502");
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "the-index-was-fine");
  }
}
