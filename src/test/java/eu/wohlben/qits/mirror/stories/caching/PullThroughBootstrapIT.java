package eu.wohlben.qits.mirror.stories.caching;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The two halves of what a pull-through cache promises, told as two stories on one cache — and
 * the second one is the reason this repository has a userflow catalogue at all.</b>
 *
 * <p>A cache's central claim is not about a response. It is about a <em>request that was never
 * made</em>: the second build got the same bytes and nobody paid npmjs for them. That is an absence,
 * and an absence is the one thing a presence check cannot state — which is why the warm story below
 * ends in {@link ReportAssertions#assertNoEdgesTo}, pointed at a registry that is <b>up, reachable
 * and recording</b> throughout. A hit that quietly dialled upstream is precisely the bug this
 * service exists to prevent, and it is the only bug here that costs money on every CI run while
 * every functional assertion in the fleet stays green.
 *
 * <h2>{@code @TestMethodOrder} is load-bearing, not tidiness</h2>
 *
 * <p>The two stories share one cache and one cumulative upstream recording, and both facts make the
 * order part of the proof:
 *
 * <ul>
 *   <li><b>The cache.</b> "Warm" is a state the cold story creates. Run second-first and the warm
 *       story would be a cold miss wearing the wrong name, and it would <em>pass every assertion but
 *       the count</em>.
 *   <li><b>The recording.</b> {@link eu.wohlben.qits.userflows.NetworkCapture#source} attributes a
 *       cumulative recording with a cursor, so traffic recorded before a drain lands in whichever
 *       story drains first. The cold story's two upstream fetches belong on the cold story's
 *       diagram; the warm story's empty slice is what {@code assertNoEdgesTo} reads. Pinning the
 *       order is what keeps each on the story that earned it.
 * </ul>
 *
 * <h2>What only a launched process can show</h2>
 *
 * <p>Every other test in this repository is a {@code @QuarkusTest}. Four things below are outside
 * what any of them may claim, and {@link StoryProfile} is where each is set up:
 *
 * <ul>
 *   <li><b>The cache roots exist because the process booted.</b> {@code MirrorStartupSeed} runs in
 *       {@code NORMAL} and never under {@code TEST}, so "a fresh deployment can be pulled through
 *       out of the box" is a claim only this posture is allowed to make. Here the {@code npmjs} row
 *       is there or the story fails.
 *   <li><b>The datasource expression resolves the platform's generic resource contract</b> — the
 *       shipped {@code ${QITS_RESOURCE_DB_URL}} triple, handed in exactly as a deployment hands it.
 *   <li><b>The absolute {@code dist.tarball} URL is built from the request.</b> It is not a config
 *       key, so it can only be right or wrong against a real client on a real port.
 *   <li><b>Four surfaces are one process on one port</b> — a JAX-RS API, three raw Vert.x route
 *       stacks from library jars, and the non-application root.
 * </ul>
 *
 * <h2>The far side is real, and that is what makes the negative worth anything</h2>
 *
 * <p>The registry is a {@link RecordingUpstream} named {@code registry.npmjs.org} — the literal
 * default of {@code qits.artifacts.npm.proxy.upstream} — serving a <b>real gzipped USTAR
 * archive</b> with its genuine SHA-1 and SHA-512 in the {@code dist} block. It records what it was
 * asked <em>before</em> it answers. So when the warm story says nothing reached it, that is a
 * measurement taken on a server that would have written the line down.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PullThroughBootstrapIT {

  static final String CATEGORY = "caching";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String COLD_STORY =
      "A build's first fetch fills the cache from the registry the mirror fronts";

  static final String COLD_SLUG = Slugs.slug(COLD_STORY);

  static final String WARM_STORY =
      "The next build is served entirely from the cache and the registry is never dialled";

  static final String WARM_SLUG = Slugs.slug(WARM_STORY);

  /**
   * This class's own package name. Synthetic on purpose: nothing called this exists on the real
   * npmjs, so a fixture that failed to register could never be papered over by the internet
   * answering plausibly instead — and no other story in the catalogue touches it, so neither
   * story's count can be satisfied by somebody else's traffic.
   */
  public static final String PACKAGE = "story-cold-install";

  public static final String VERSION = "1.4.2";

  /** The two initiators, which are the only thing that differs between the two builds' requests. */
  private static final String BUILD = "a build container";

  private static final String NEXT_BUILD = "a second build container";

  private static final String OPERATOR = "a platform operator";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** What upstream served, so the client's copy can be compared against the original bytes. */
  private static TinyPackage upstreamArchive;

  /** The cold answers, kept so the warm story can demand them byte for byte. */
  private static String coldIndex;

  private static byte[] coldTarballBytes;

  private static String coldTarballEtag;

  private static String tarballUrl;

  @BeforeAll
  static void tapBothEndsAndHostThePackage() {
    StoryNetwork.install();
    upstreamArchive =
        NpmUpstreamFixture.host(registry(), PACKAGE, VERSION);
  }

  private static RecordingUpstream registry() {
    return RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM);
  }

  // --- the wire paths, as both ends spell them ---------------------------------------------------

  /** What a build asks THIS service for. */
  private static final String SERVED_INDEX = StoryTarget.NPM_BASE + "/" + PACKAGE;

  private static final String SERVED_TARBALL =
      StoryTarget.NPM_BASE + NpmUpstreamFixture.tarballPath(PACKAGE, VERSION);

  /** What this service asks the REGISTRY for. The mount point is the only difference. */
  private static final String UPSTREAM_INDEX = NpmUpstreamFixture.packumentPath(PACKAGE);

  private static final String UPSTREAM_TARBALL = NpmUpstreamFixture.tarballPath(PACKAGE, VERSION);

  private static final String PACKAGES_LISTING =
      StoryTarget.EXPLORER + "/" + StoryTarget.NPM_CACHE + "/packages";

  @UserStory(value = COLD_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A build container resolves a dependency it has never resolved before. Nobody registered
      anything and nobody warmed anything: the `npmjs` cache root exists because this process
      booted, which is the difference between a pull-through cache and a thing an operator has to
      remember to set up before the first CI build runs.

      The index comes back as upstream wrote it — every member re-emitted, upstream's own
      `integrity` untouched — with exactly one field per version moved: `dist.tarball`, pointed
      back at the authority the request arrived on. That rewrite is the whole reason an install
      routed here stays routed here; a document repeating upstream's url would send the very next
      request past this service to the internet.

      Then the tarball itself is pulled through and stored, and the client gets upstream's bytes.
      Each of the two documents cost exactly one upstream read, which is the number this story is
      about: a cold miss is a fetch, and it is one fetch.

      And what was pulled becomes visible. What a cache contains is decided by what somebody
      pulled, so the page that shows it is the page that reads those rows back.
      """)
  @Order(1)
  void theFirstFetchFillsTheCache(Interactions story, Network net) {
    RecordingUpstream registry = registry();

    // Every byte and every row this service holds lives in one PostgreSQL database — metadata and
    // blob content alike, since qits.artifacts.blobs-datasource=mirror. No tap can see a JDBC
    // connection, so the one dependency that is not on the diagram by observation is declared.
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument, the version row and the tarball bytes");

    story.note(
        "qits-platform-mirror is up against its own PostgreSQL, beside the registry it caches");
    given().get(StoryTarget.READY).then().statusCode(200).body("status", equalTo("UP"));

    // The cache roots the BOOT made. The `upstream` column is the same config key the miss path
    // dials, read back through a different class — so a rename that broke one and not the other
    // fails here. The actor is set BEFORE the call: a tap sees a request, never a narrative role.
    NetworkCapture.actor(OPERATOR);
    given()
        .get(StoryTarget.EXPLORER)
        .then()
        .statusCode(200)
        .body(
            "repositories.find { it.name == '" + StoryTarget.NPM_CACHE + "' }.type",
            equalTo("npm-proxy"))
        .body(
            "repositories.find { it.name == '" + StoryTarget.NPM_CACHE + "' }.upstream",
            equalTo(registry.baseUrl()))
        .body(
            "repositories.find { it.name == '" + StoryTarget.MAVEN_CACHE + "' }.type",
            equalTo("maven-proxy"));
    story
        .note(
            "the npmjs and central cache roots exist because the process BOOTED — nobody registered"
                + " them, and npmjs already names the upstream the miss path will dial")
        .as("cache-roots-seeded-at-boot");

    // --- the cold index --------------------------------------------------------------------------
    // The Accept header is the abbreviated type both npm and pnpm really send; this registry
    // answers the FULL document to it on purpose, which is spec-legal.
    //
    // The body is read with Jackson rather than through rest-assured's GPath, the way every other
    // npm assertion in the fleet reads one: a packument's two most interesting keys are `dist-tags`
    // and a version number, and Groovy parses a hyphen as subtraction and a dotted version as
    // navigation. That is a silent wrong answer rather than an error.
    NetworkCapture.actor(BUILD);
    coldIndex =
        given()
            .header("Accept", "application/vnd.npm.install-v1+json, application/json")
            .get(SERVED_INDEX)
            .then()
            .statusCode(200)
            .contentType(startsWith("application/json"))
            .extract()
            .asString();

    JsonNode cold = parse(coldIndex);
    assertEquals(VERSION, cold.path("dist-tags").path("latest").asText());
    // Served THROUGH, not rebuilt: a member this service never reads still reaches the client,
    // which is what lets npmjs add a field tomorrow with no release here.
    assertEquals(
        NpmUpstreamFixture.UPSTREAM_MARKER,
        cold.path("_upstream").asText(),
        "upstream's document is served through, not re-synthesised from what was parsed");

    JsonNode dist = cold.path("versions").path(VERSION).path("dist");
    assertEquals(
        upstreamArchive.integrity(),
        dist.path("integrity").asText(),
        "upstream's integrity must reach the client unedited — it is what the install verifies");
    assertEquals(upstreamArchive.shasum(), dist.path("shasum").asText());

    // The one field that moves, and the one that cannot be a config key: its value depends on the
    // request, so only a real client on a real port can show it is right. `stories/npm` is where a
    // real npm CLI follows it.
    tarballUrl = dist.path("tarball").asText();
    assertTrue(
        tarballUrl.endsWith(SERVED_TARBALL),
        "dist.tarball must point back at this service, not at upstream: " + tarballUrl);
    assertEquals(
        1, registry.requestsTo(UPSTREAM_INDEX), "a cold index is one upstream read, and exactly one");

    story
        .note(
            "the packument arrives as upstream wrote it — `_upstream`, `shasum` and `integrity`"
                + " untouched — with dist.tarball alone pointed back at this service")
        .as("index-served-through");
    story
        .note("a cold miss: the index was not held here, so upstream was read exactly once")
        .as("index-fetched-upstream");

    // --- the cold tarball ------------------------------------------------------------------------
    Response coldTarball =
        given()
            .get(tarballUrl)
            .then()
            .statusCode(200)
            .header("Cache-Control", "public, max-age=31536000, immutable")
            // Not a header npm reads — it verifies against the packument — but it is upstream's
            // claim, re-emitted here too and still never checked by this service.
            .header("X-Npm-Integrity", upstreamArchive.integrity())
            .header("ETag", notNullValue())
            .extract()
            .response();
    coldTarballBytes = coldTarball.asByteArray();
    coldTarballEtag = coldTarball.getHeader("ETag");
    assertArrayEquals(
        upstreamArchive.tarball(),
        coldTarballBytes,
        "the client must get UPSTREAM's bytes, not something assembled here");
    assertEquals(
        1,
        registry.requestsTo(UPSTREAM_TARBALL),
        "the tarball miss costs one upstream fetch, and exactly one");

    story
        .note(
            "the tarball is upstream's archive byte for byte, served with an ETag and a year of"
                + " `immutable`, and upstream's own integrity claim re-emitted beside it")
        .as("tarball-served-through");
    story
        .note("a cold miss again: the bytes were pulled through and stored on the way past")
        .as("tarball-fetched-upstream");

    // --- and what it now holds ---------------------------------------------------------------------
    NetworkCapture.actor(OPERATOR);
    given()
        .get(PACKAGES_LISTING)
        .then()
        .statusCode(200)
        .body("repository.name", equalTo(StoryTarget.NPM_CACHE))
        .body("repository.type", equalTo("npm-proxy"))
        .body(
            "packages.find { it.name == '" + PACKAGE + "' }.versions[0].version", equalTo(VERSION));
    story
        .note(
            "what the pull left behind is visible: the explorer lists the version now held under"
                + " the npmjs root")
        .as("cached-version-visible-in-the-explorer");
  }

  @UserStory(value = WARM_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The claim that makes this a cache rather than a proxy, and the only claim here that cannot be
      made by looking at a response.

      A second build asks for the same index and the same tarball. Both answers are byte-for-byte
      the ones before them — same document, same archive, same ETag — because the index is inside
      its TTL and a tarball is immutable, content-addressed underneath and served with a year of
      `immutable`.

      And the registry, which is up and recording throughout, was not asked for either of them.
      That is the whole economic claim of a mirror: the second build costs npmjs nothing. It is
      also the one claim a diagram can only make by NOT drawing something, so the story's proof is
      an assertion that no edge in it reaches the registry at all — taken against a server that
      would have written the line down had this service dialled it.

      A cache that answered correctly and fetched anyway would pass every other test in this
      repository.
      """)
  @Order(2)
  void theSecondBuildNeverLeavesTheProcess(Interactions story, Network net) {
    RecordingUpstream registry = registry();

    // The cache is read, so the store is dialled — and saying so is what keeps the negative below
    // honest: "no edges to the registry" is a claim about the REGISTRY, not about this story being
    // quiet. The service was busy; it simply had no reason to leave the building.
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument, the version row and the tarball bytes");

    // A DIFFERENT actor, and that is what keeps this build visible at all: its two requests are the
    // same method, path and status as the first build's, and the drained edge set dedupes on the
    // whole quadruple. The initiator is the only thing that differs, and it is also the only thing
    // that matters here.
    NetworkCapture.actor(NEXT_BUILD);

    String warmIndex = given().get(SERVED_INDEX).then().statusCode(200).extract().asString();
    assertEquals(
        coldIndex,
        warmIndex,
        "the cached index must be the same document, character for character — it is upstream's "
            + "body re-served, not a document reassembled from what was parsed out of it");

    Response warmTarball = given().get(tarballUrl).then().statusCode(200).extract().response();
    assertArrayEquals(
        coldTarballBytes,
        warmTarball.asByteArray(),
        "a cached tarball must come back byte-for-byte what upstream served");
    assertEquals(
        coldTarballEtag,
        warmTarball.getHeader("ETag"),
        "the validator of an immutable url can never change");

    // The same two counters the cold story left at one. They are the assertion's belt; the
    // braces is assertNoEdgesTo in @AfterAll, which is the claim in the diagram itself.
    assertEquals(
        1,
        registry.requestsTo(UPSTREAM_INDEX),
        "a warm index must not be re-asked inside its TTL");
    assertEquals(
        1,
        registry.requestsTo(UPSTREAM_TARBALL),
        "a cached tarball must never be re-fetched — it is immutable and content-addressed");

    story
        .note(
            "the second build got both answers byte-for-byte, ETag included, and the registry's"
                + " recording never moved — neither request left this process")
        .as("second-build-served-from-cache");
    story
        .note(
            "an absence is not an edge: this story's diagram has no arrow to the registry at all,"
                + " which is what the mirror is bought for")
        .as("upstream-was-never-dialled");
  }

  /** A packument, read the way an npm client reads one — see the first call site for why Jackson. */
  private static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception notJson) {
      throw new IllegalStateException("not a JSON document: " + body, notJson);
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY_SLUG, COLD_SLUG, UserflowReport.PASSED);

    // --- the cold story's whole graph, both ends ---------------------------------------------------
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", StoryTarget.EXPLORER, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", PACKAGES_LISTING, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_TARBALL, 200));
    // Observed on the far side, drained from the registry's own recording — the two cold misses,
    // and there is no third.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_INDEX, "200"));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.NPM_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_TARBALL, "200"));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument, the version row and the tarball bytes");
    // EXACTLY those seven. The presence checks above cannot see a stray edge — a probe the tap's
    // skip missed, an upstream fetch nobody meant to make — and "upstream was asked twice and not
    // four times" is the entire economic claim this pair is about.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, COLD_SLUG, 7);

    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "cache-roots-seeded-at-boot");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "index-served-through");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "index-fetched-upstream");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "tarball-served-through");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "tarball-fetched-upstream");
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, COLD_SLUG, "cached-version-visible-in-the-explorer");

    // --- and the warm story's, which is mostly about what is not in it -----------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        NEXT_BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        NEXT_BUILD,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_TARBALL, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument, the version row and the tarball bytes");

    // THE CLAIM. Directional rather than assertNoEdgesFrom(SERVICE), which would be false — this
    // story's service legitimately dialled its own store and says so above. What must not have
    // happened is a dial to the REGISTRY, and this is the assertion that says it.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, WARM_SLUG, StoryTarget.NPM_UPSTREAM);
    // Three edges and no fourth: two requests in, one store dependency declared, nothing out.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, WARM_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, WARM_SLUG, java.util.List.of(NEXT_BUILD, StoryTarget.SERVICE));

    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "second-build-served-from-cache");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "upstream-was-never-dialled");

    // This service is anonymous by design — no qits-auth-core, because a cache that authenticated
    // its readers would be a cache nothing could read — so there is no bearer to keep out of a
    // bundle. What CAN leak here is a run-local value, and there is exactly one: the ephemeral port
    // the recording registry bound. It reaches this JVM (the packument's dist.tarball named it) and
    // must reach no report, because a label carrying it would move the networkHash on every run.
    for (String slug : java.util.List.of(COLD_SLUG, WARM_SLUG)) {
      ReportAssertions.assertNotLeaked(
          CATEGORY_SLUG, slug, RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM).baseUrl());
    }
  }
}
