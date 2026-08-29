package eu.wohlben.qits.mirror;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.mirror.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, beside the registry it is a cache of — the one posture
 * this repository's suite has never had, and the one its README says is still owed. Every existing
 * test here is a {@code @QuarkusTest}: {@code NpmCacheSmokeTest} and its maven
 * and OCI siblings prove the three wire protocols against an in-process stub, {@code MirrorApiTest}
 * proves the explorer's two reads, and {@code MirrorSeedTest} proves the seeder when it is called by
 * hand. What none of them can reach is what only a launched process has:
 *
 * <ul>
 *   <li><b>The cache roots exist because the process booted.</b> {@link MirrorStartupSeed} runs in
 *       {@code LaunchMode.NORMAL} and {@code DEVELOPMENT} and never under {@code TEST} — deliberately,
 *       so a suite is not testing the seeder in every class — so "a fresh deployment can be pulled
 *       through out of the box" is a claim no {@code @QuarkusTest} in this repository is allowed to
 *       make. Here the {@code npmjs} row is there or the story fails.
 *   <li><b>The datasource expression resolves the generic resource contract.</b> The shipped config
 *       reads {@code ${QITS_RESOURCE_DB_URL}} and friends and has no defaults on purpose; a suite
 *       replaces the whole triple from {@code EmbeddedPgConfigSource} at ordinal 500 and never
 *       exercises the expression. The launched process below is handed the three variables a
 *       deployment injects and nothing else, so Flyway either migrates or the boot dies naming the
 *       variable, which is the refuse-to-boot stance under test rather than described.
 *   <li><b>The absolute {@code dist.tarball} url is built from the request.</b> It is not a config
 *       key — {@code NpmPackuments} explains why — so it can only be right or wrong against a real
 *       client on a real port, which is what makes an install follow this service instead of
 *       bypassing it.
 *   <li><b>{@code /mirror/api}, {@code /artifacts/npm} and health are one process on one port.</b>
 *       A JAX-RS surface, a raw Vert.x route stack from a library jar and the non-application root
 *       are three different registrations, and packaging is where a collision between them would
 *       show.
 * </ul>
 *
 * <p>The far side is a {@link MockService} impersonating <b>registry.npmjs.org</b> — the address
 * {@code qits.artifacts.npm.proxy.upstream} ships pointing at — so every caching claim is assertable
 * on <b>both ends</b>: the client got the bytes, and upstream was (or was not) asked. That count is
 * the whole of what a pull-through cache is; without it "served from cache" is unfalsifiable.
 *
 * <p><b>What the mock can and cannot stand in for.</b> It serializes every stubbed body as JSON,
 * which makes it an exact stand-in for a packument — that document really is JSON — and a
 * <em>stand-in</em> for a tarball, which really is a gzipped USTAR archive. That costs this story
 * nothing it claims: {@code NpmUpstream} treats a tarball as opaque bytes and
 * verifies no hash, deliberately, so what is under test here is that the bytes upstream served are
 * the bytes the client gets, twice, and that the second answer never left the process. The archive
 * being a real one is pinned where it belongs, in {@code NpmCacheSmokeTest}'s {@code TinyPackage}.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright never launches anything — which is what lets this run in a step container with no
 * browser in it.
 *
 * <p><b>This IT is named on the command line rather than opted in from the pom</b> ({@code
 * .config/qits/ci-event-userflows.yml} passes {@code -DskipITs=false "-Dit.test=PullThroughBootstrapIT"}).
 * Two reasons, and either alone would settle it. The README owes a <em>packaged-surface</em> probe
 * list that is half about the CLIENT — {@code /} answering 200 HTML with {@code <base href="/">}, a
 * deep link falling back to {@code index.html}, and {@code /v2} and {@code /artifacts/*} staying
 * 404s inside the SPA fallback's reach — and the userflow run deliberately builds no client ({@code
 * -Dquarkus.quinoa=false}, and the webui submodule arrives empty in a step container), so a blanket
 * {@code -DskipITs=false} would make that run red on a test that is right the day it lands. And this
 * module's surefire suite already spawns a real postgres; opting {@code verify} into a second one
 * plus a launched process would change the clone-alone build the README documents, for everybody,
 * to run a test that CI runs anyway. {@code skipITs} therefore stays true in {@code pom.xml} and
 * keeps meaning "run everything" for the {@code native} profile that flips it.
 */
@QuarkusIntegrationTest
@TestProfile(PullThroughBootstrapIT.PackagedAgainstAMockRegistry.class)
public class PullThroughBootstrapIT {

  static final String CATEGORY = "caching";
  static final String PULL_SLUG =
      "a-build-s-first-fetch-fills-the-cache-the-second-is-served-without-asking-upstream";
  static final String REFUSAL_SLUG = "a-no-from-upstream-is-never-cached-and-an-outage-is-never-a-no";

  /**
   * The service the mock impersonates — also the {@link MockService#ensureStarted} key. It is the
   * literal default of {@code qits.artifacts.npm.proxy.upstream}, so the sequence diagram names the
   * address a deployment really dials rather than a fixture's alias.
   */
  static final String UPSTREAM = "registry.npmjs.org";

  /** The seeded npm cache root, as {@link MirrorRepositorySeeder} names it. */
  static final String NPM_CACHE = MirrorRepositorySeeder.NPM_CACHE;

  /** The seeded maven cache root beside it, which the first story reads but never pulls through. */
  static final String MAVEN_CACHE = MirrorRepositorySeeder.MAVEN_CACHE;

  /** The npm cache's mount point, spelled in full — it is a literal in the qits-registries jar. */
  static final String NPM_BASE = "/artifacts/npm/" + NPM_CACHE;

  static final String READY = "/mirror/q/health/ready";
  static final String EXPLORER = "/mirror/api/repositories";

  // --- the package the first story pulls through ---------------------------------------------

  /**
   * Deliberately synthetic: no name used here exists on the real npmjs, so a stub that failed to
   * register could never be papered over by the internet answering plausibly instead.
   */
  static final String CACHED = "tiny-tarball";

  static final String CACHED_VERSION = "1.4.2";

  /** npmjs' own layout, which is also the layout this service re-emits: {@code <name>/-/<file>}. */
  static final String CACHED_TARBALL_PATH =
      "/" + CACHED + "/-/" + CACHED + "-" + CACHED_VERSION + ".tgz";

  /** The body upstream serves for that path. See the class comment on what it stands in for. */
  static final String TARBALL_BODY = "the bytes upstream served for " + CACHED + "@" + CACHED_VERSION;

  /**
   * Upstream's own hash claims, carried in the packument's {@code dist} block.
   *
   * <p>They are <b>not</b> hashes of {@link #TARBALL_BODY} and do not need to be: this service
   * verifies neither, deliberately — {@code NpmUpstream}'s class comment says why — and re-emits both
   * unmodified so the installing client checks the bytes against a hash this process never computed
   * and could not forge. What the story asserts is precisely that: they arrive unedited.
   */
  static final String UPSTREAM_SHASUM = "9c88a2f60a0f2d6bcbdd2f0b6a5d2b3f4e1c07ad";

  static final String UPSTREAM_INTEGRITY =
      "sha512-3FvGOhKkr0CTVA6d0hzs0MLuKZCU1V2mSLzYQpaTMUytKmTZ5rWnLmMbLBWkjBqLQKf7YkD9jgHTyLtqTLXZ3g==";

  /** A marker no rewrite may drop: the document is upstream's, with one field per version moved. */
  static final String UPSTREAM_MARKER = "the mock registry";

  // --- the two packages the second story asks for --------------------------------------------

  /** Upstream has never heard of it. It gets NO stub: an unstubbed route is the mock's own 404. */
  static final String UNKNOWN = "never-published-here";

  /**
   * A package whose packument resolves and whose bytes do not: its {@code dist.tarball} names a
   * closed port, which is what an upstream outage looks like to a {@code java.net.http.HttpClient}.
   */
  static final String UNREACHABLE = "upstream-went-away";

  static final String UNREACHABLE_VERSION = "2.0.0";

  /** Refuses at once rather than hanging — the suite's own offline spelling, in application.properties. */
  static final String CLOSED_PORT = "http://127.0.0.1:1";

  /**
   * Marks the stubs as registered, for the same reason {@code MockIdp} parks its keypair: a test
   * profile is instantiated in more than one classloader and a static field written by one copy is
   * not the field another reads, while the JVM has exactly one property table. {@link
   * MockService#ensureStarted} already makes the <em>server</em> singular; the stubs live on the
   * owning instance, so this is what keeps the second copy from trying (and failing) to re-register
   * them on an attached handle.
   */
  private static final String STUBBED_PROPERTY = "qits.mirror.it.upstream-stubbed";

  /**
   * Hands the launched artifact its config the way a deployment does.
   *
   * <p>Every key here is a <b>runtime</b> key. A packaged process takes its configuration as {@code
   * -D} arguments on an artifact that was already built, so a build-time key would be silently
   * ignored and this would prove something other than what it says. Everything that makes this
   * service what it is stays exactly as it ships: {@code quarkus.rest.path}, the non-application
   * root, the arc exclusions that veto the hosted profiles, the Flyway lineage, {@code
   * qits.artifacts.blobs-datasource=mirror}, the body ceiling and the eviction windows.
   *
   * <p>Four of the eight are the deployment's own inputs and four are neutralisations:
   *
   * <ul>
   *   <li><b>The database triple</b> — the platform's generic resource contract, which
   *       qits-platform-deployments injects and the shipped datasource expression reads. It is the
   *       same embedded postgres the surefire suite spawns, under a database of this IT's own
   *       ({@code mirror_userflows_it}), so the launched process and the suite can never mean the
   *       same schema. Its url travels through a system property rather than a static field, for
   *       the classloader reason above.
   *   <li><b>The npm upstream</b> — the one seam this test moves, and the whole point of it.
   *   <li><b>The maven upstream and the OCI endpoint override</b> — pointed at a closed port, the
   *       spelling {@code src/test/resources/application.properties} already uses for exactly this
   *       reason. No story below resolves a jar or pulls an image; a key left at Maven Central or
   *       derived from the seeded OCI domains would let a mistyped path reach the real internet from
   *       a step container, where it would pass or fail for reasons unrelated to this code.
   *   <li><b>The eviction kill switch</b> — the service's own, {@code qits.mirror.eviction.enabled},
   *       rather than a scheduler flag ({@code quarkus.scheduler.enabled} is build-time fixed and a
   *       {@code -D} on a built artifact would be read, accepted and ignored). The sweep is the only
   *       background work this process has and its only trigger is the clock at 03:20; a run that
   *       happened to start then would be deleting rows underneath the stories. It ships enabled,
   *       and this is the switch a deployment reaches for.
   *   <li><b>OTel</b> — dark outside a deployment, like {@code %dev}/{@code %test}. This is a
   *       neutralisation and not tidiness: the shipped config points the exporter at
   *       {@code http://qits-observability:8080}, a name that resolves on qits-net and nowhere else,
   *       so a launched artifact would spend the run retrying an export to a host that is not there.
   * </ul>
   */
  public static class PackagedAgainstAMockRegistry implements QuarkusTestProfile {

    /** Where the url is parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.userflow-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockService upstream = upstreamStartedAndStubbed();
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          "qits.artifacts.npm.proxy.upstream", upstream.baseUrl(),
          "qits.artifacts.maven.proxy.upstream", CLOSED_PORT,
          "qits.artifacts.oci.mirror.endpoint-override", CLOSED_PORT,
          "qits.mirror.eviction.enabled", "false",
          "quarkus.otel.sdk.disabled", "true");
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url("mirror_userflows_it");
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  /**
   * Start the mock of npmjs once per JVM and stub the three routes these stories read.
   *
   * <p>The bodies are npmjs' own shapes rather than invented ones: a packument with {@code
   * dist-tags}, {@code versions} and a {@code dist} block per version, and a tarball served at
   * {@code <name>/-/<unscoped>-<version>.tgz}. Anything the stubs got wrong would make this prove a
   * registry that does not exist — and would do it quietly, since the proxy passes a packument
   * through rather than rebuilding it.
   *
   * <p>{@link #UNKNOWN} gets NO stub at all. An unstubbed route is a 404 here, which is the
   * registry's genuine "no such package" and needs no arrangement.
   */
  static synchronized MockService upstreamStartedAndStubbed() {
    if (System.getProperty(STUBBED_PROPERTY) != null) {
      return MockService.attach(UPSTREAM);
    }
    MockService upstream = MockService.ensureStarted(UPSTREAM);

    // The package a build installs. `_upstream` is a marker no rewrite may drop: the proxy serves
    // upstream's document THROUGH, moving one field per version, rather than re-synthesising one
    // from the members it happened to parse.
    upstream.stub(
        "GET",
        "/" + CACHED,
        Map.of(
            "_id",
            CACHED,
            "name",
            CACHED,
            "dist-tags",
            Map.of("latest", CACHED_VERSION),
            "versions",
            Map.of(
                CACHED_VERSION,
                Map.of(
                    "name", CACHED,
                    "version", CACHED_VERSION,
                    "dist",
                        Map.of(
                            "tarball", upstream.baseUrl() + CACHED_TARBALL_PATH,
                            "shasum", UPSTREAM_SHASUM,
                            "integrity", UPSTREAM_INTEGRITY))),
            "_upstream",
            UPSTREAM_MARKER));

    // The bytes that packument points at.
    upstream.stub("GET", CACHED_TARBALL_PATH, TARBALL_BODY);

    // The package whose index resolves and whose bytes do not: dist.tarball names a closed port, so
    // the tarball fetch below never reaches this mock at all — which is what makes its recording
    // ("one packument request, no tarball request") the proof that the miss really was an outage.
    upstream.stub(
        "GET",
        "/" + UNREACHABLE,
        Map.of(
            "_id",
            UNREACHABLE,
            "name",
            UNREACHABLE,
            "dist-tags",
            Map.of("latest", UNREACHABLE_VERSION),
            "versions",
            Map.of(
                UNREACHABLE_VERSION,
                Map.of(
                    "name", UNREACHABLE,
                    "version", UNREACHABLE_VERSION,
                    "dist",
                        Map.of(
                            "tarball",
                            CLOSED_PORT
                                + "/"
                                + UNREACHABLE
                                + "/-/"
                                + UNREACHABLE
                                + "-"
                                + UNREACHABLE_VERSION
                                + ".tgz",
                            "shasum", UPSTREAM_SHASUM,
                            "integrity", UPSTREAM_INTEGRITY)))));

    System.setProperty(STUBBED_PROPERTY, "true");
    return upstream;
  }

  @UserStory(
      value = "A build's first fetch fills the cache; the second is served without asking upstream",
      category = "caching")
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
      request past this service to the internet. Then the tarball itself is pulled through and
      stored, and the client gets upstream's bytes.

      The second build asks for both again and upstream hears nothing. The index is inside its
      TTL, and a tarball is immutable — content-addressed underneath, served with an ETag and a
      year of `immutable` — so the answers are byte-for-byte the ones before them and neither
      leaves the process. That is the whole economic claim of a mirror, and the only way to make
      it falsifiable is to count what upstream was asked.

      And what was pulled becomes visible: the explorer's read-only listing names the cache root,
      the address it fronts, and the version now held under it. What a cache contains is decided
      by what somebody pulled, so the page that shows it is the page that reads those rows back.
      """)
  void theFirstFetchFillsTheCacheAndTheSecondNeverLeavesTheProcess(Interactions story) {
    MockService upstream = MockService.attach(UPSTREAM);

    story.note(
        "qits-platform-mirror starts against its own PostgreSQL, beside the registry it caches");
    given().get(READY).then().statusCode(200).body("status", equalTo("UP"));

    // The cache roots the BOOT made. MirrorStartupSeed runs in NORMAL and DEVELOPMENT and never
    // under TEST, so this row's existence is a property of a launched artifact and of nothing else
    // in this repository's suite. The `upstream` column is the same config key the miss path dials,
    // read back through a different class — so a rename that broke one and not the other fails here.
    given()
        .get(EXPLORER)
        .then()
        .statusCode(200)
        .body("repositories.find { it.name == '" + NPM_CACHE + "' }.type", equalTo("npm-proxy"))
        .body(
            "repositories.find { it.name == '" + NPM_CACHE + "' }.upstream",
            equalTo(upstream.baseUrl()))
        .body("repositories.find { it.name == '" + MAVEN_CACHE + "' }.type", equalTo("maven-proxy"));
    story
        .happened("a platform operator", "qits-platform-mirror", "GET /mirror/api/repositories")
        .as("cache-roots-seeded-at-boot");

    // --- the cold index -----------------------------------------------------------------------
    // The Accept header is the abbreviated type both npm and pnpm really send; this registry
    // answers the FULL document to it on purpose, which is spec-legal.
    //
    // The body is read with Jackson rather than through rest-assured's GPath, the way every other
    // npm assertion in the fleet reads one: a packument's two most interesting keys are `dist-tags`
    // and a version number, and Groovy parses a hyphen as subtraction and a dotted version as
    // navigation. That is a silent wrong answer rather than an error, which is not a thing to have
    // in the assertion that says the document came through unedited.
    String coldIndex =
        given()
            .header("Accept", "application/vnd.npm.install-v1+json, application/json")
            .get(NPM_BASE + "/" + CACHED)
            .then()
            .statusCode(200)
            .contentType(startsWith("application/json"))
            .extract()
            .asString();

    JsonNode cold = parse(coldIndex);
    assertEquals(CACHED_VERSION, cold.path("dist-tags").path("latest").asText());
    // Served THROUGH, not rebuilt: a member this service never reads still reaches the client,
    // which is what lets npmjs add a field tomorrow with no release here.
    assertEquals(
        UPSTREAM_MARKER,
        cold.path("_upstream").asText(),
        "upstream's document is served through, not re-synthesised from what was parsed");

    JsonNode dist = cold.path("versions").path(CACHED_VERSION).path("dist");
    assertEquals(
        UPSTREAM_INTEGRITY,
        dist.path("integrity").asText(),
        "upstream's integrity must reach the client unedited — it is what the install verifies");
    assertEquals(UPSTREAM_SHASUM, dist.path("shasum").asText());

    // The one field that moves, and the one that cannot be a config key: its value depends on the
    // request, so only a real client on a real port can show it is right.
    String tarballUrl = dist.path("tarball").asText();
    assertTrue(
        tarballUrl.endsWith(NPM_BASE + "/" + CACHED + "/-/" + CACHED + "-" + CACHED_VERSION + ".tgz"),
        "dist.tarball must point back at this service, not at upstream: " + tarballUrl);
    assertEquals(
        1, requestsTo(upstream, "/" + CACHED), "a cold index is one upstream read, and exactly one");

    story
        .happened("a build container", "qits-platform-mirror", "GET " + NPM_BASE + "/" + CACHED)
        .as("index-requested");
    story
        .happened("qits-platform-mirror", UPSTREAM, "GET /" + CACHED + " (a cold miss)")
        .as("index-fetched-upstream");

    // --- the cold tarball ---------------------------------------------------------------------
    Response coldTarball =
        given()
            .get(tarballUrl)
            .then()
            .statusCode(200)
            .header("Cache-Control", "public, max-age=31536000, immutable")
            // Not a header npm reads — it verifies against the packument — but it is upstream's
            // claim, re-emitted here too and still never checked by this service.
            .header("X-Npm-Integrity", UPSTREAM_INTEGRITY)
            .header("ETag", notNullValue())
            .extract()
            .response();
    byte[] coldBytes = coldTarball.asByteArray();
    assertTrue(
        new String(coldBytes, StandardCharsets.UTF_8).contains(TARBALL_BODY),
        "the client must get UPSTREAM's bytes, not something assembled here");
    assertEquals(
        1,
        requestsTo(upstream, CACHED_TARBALL_PATH),
        "the tarball miss costs one upstream fetch, and exactly one");

    story
        .happened(
            "a build container",
            "qits-platform-mirror",
            "GET " + NPM_BASE + "/" + CACHED + "/-/" + CACHED + "-" + CACHED_VERSION + ".tgz")
        .as("tarball-requested");
    story
        .happened("qits-platform-mirror", UPSTREAM, "GET " + CACHED_TARBALL_PATH + " (a cold miss)")
        .as("tarball-fetched-upstream");

    // --- the second build ------------------------------------------------------------------------
    // The claim that makes this a cache rather than a proxy. Both answers are byte-for-byte the
    // first ones, and upstream's counters have not moved: the index is inside its TTL and the
    // tarball is immutable, so neither request left this process.
    String warmIndex =
        given().get(NPM_BASE + "/" + CACHED).then().statusCode(200).extract().asString();
    assertEquals(
        coldIndex,
        warmIndex,
        "the cached index must be the same document, character for character — it is upstream's "
            + "body re-served, not a document reassembled from what was parsed out of it");

    Response warmTarball = given().get(tarballUrl).then().statusCode(200).extract().response();
    assertArrayEquals(
        coldBytes,
        warmTarball.asByteArray(),
        "a cached tarball must come back byte-for-byte what upstream served");
    assertEquals(
        coldTarball.getHeader("ETag"),
        warmTarball.getHeader("ETag"),
        "the validator of an immutable url can never change");

    assertEquals(
        1, requestsTo(upstream, "/" + CACHED), "a warm index must not be re-asked inside its TTL");
    assertEquals(
        1,
        requestsTo(upstream, CACHED_TARBALL_PATH),
        "a cached tarball must never be re-fetched — it is immutable and content-addressed");

    story
        .happened(
            "a second build container",
            "qits-platform-mirror",
            "GET the same index and tarball -> served from cache, upstream untouched")
        .as("second-build-served-from-cache");

    // --- and what it now holds ------------------------------------------------------------------
    given()
        .get(EXPLORER + "/" + NPM_CACHE + "/packages")
        .then()
        .statusCode(200)
        .body("repository.name", equalTo(NPM_CACHE))
        .body("repository.type", equalTo("npm-proxy"))
        .body("packages.find { it.name == '" + CACHED + "' }.versions[0].version", equalTo(CACHED_VERSION));
    story
        .happened(
            "a platform operator",
            "qits-platform-mirror",
            "GET /mirror/api/repositories/" + NPM_CACHE + "/packages (what the pull left behind)")
        .as("cached-version-visible-in-the-explorer");
  }

  @UserStory(
      value = "A no from upstream is never cached, and an outage is never a no",
      category = "caching")
  @UserStoryDescription(
      """
      The flip side of holding somebody else's bytes. Everything this service can say about what
      exists, it says on upstream's authority — and this is the worst place on the platform to
      confuse the two ways of having nothing to give, because a wrong "there is no such thing" is
      cached by every npm, maven and docker client that asked and stays wrong long after the
      mistake is fixed.

      A package upstream has never heard of is a 404, passed through, in npm's own `{"error": …}`
      envelope. And it is REMEMBERED NOWHERE: ask again and upstream is asked again. That is not
      an optimisation left undone — it is what lets a package published a minute ago install a
      minute later, which a negative cache with any TTL at all would break.

      A package upstream cannot answer for is a different sentence and gets a different status.
      Its index resolved, so the version demonstrably exists; only the bytes are out of reach, and
      the answer says so with a 502 that names the package. Collapsing that into a 404 would teach
      the whole fleet that the version does not exist — which npm caches as "no such version", and
      which is exactly the failure a mirror is bought to prevent.

      Both refusals are JSON. Never Quarkus' HTML error page, and never an empty packument: the
      client here is a machine that parses what it gets, and a page rendered into a document slot
      is a corrupt-response error somebody debugs a long way from the cause.
      """)
  void upstreamsRefusalIsPassedThroughAndItsSilenceIsNot(Interactions story) {
    MockService upstream = MockService.attach(UPSTREAM);

    // (a) upstream's no. Nothing is stubbed for this name, so the mock answers 404 — which is
    // exactly what registry.npmjs.org answers for a package it does not hold.
    given()
        .get(NPM_BASE + "/" + UNKNOWN)
        .then()
        .statusCode(404)
        .contentType(startsWith("application/json"))
        .body("error", equalTo("no such package upstream: " + UNKNOWN));
    assertEquals(
        1,
        requestsTo(upstream, "/" + UNKNOWN),
        "the 404 must be UPSTREAM's answer, not a guess made here");

    story
        .happened(
            "a build container",
            "qits-platform-mirror",
            "GET " + NPM_BASE + "/" + UNKNOWN + " -> 404 {\"error\": …}")
        .as("unknown-package-refused");
    story
        .happened("qits-platform-mirror", UPSTREAM, "GET /" + UNKNOWN + " -> 404")
        .as("upstream-said-no");

    // (b) and it was not written down. The same request again reaches upstream again — there is no
    // negative entry to serve and no TTL to wait out, which is what makes a package published a
    // minute ago installable a minute later.
    given().get(NPM_BASE + "/" + UNKNOWN).then().statusCode(404);
    assertEquals(
        2,
        requestsTo(upstream, "/" + UNKNOWN),
        "a cache must never remember a no — the second ask must reach upstream too");
    story
        .happened(
            "qits-platform-mirror",
            UPSTREAM,
            "GET /" + UNKNOWN + " again -> 404 (nothing was cached)")
        .as("the-no-was-not-remembered");

    // (c) upstream's silence, which is not a no. The index resolves, so the version exists; the
    // bytes are behind an address that refuses the connection.
    String index =
        given().get(NPM_BASE + "/" + UNREACHABLE).then().statusCode(200).extract().asString();
    assertEquals(
        UNREACHABLE_VERSION,
        parse(index).path("dist-tags").path("latest").asText(),
        "the index must resolve — it is what makes the refusal below an outage and not a no");

    String refusal =
        given()
            .get(
                NPM_BASE
                    + "/"
                    + UNREACHABLE
                    + "/-/"
                    + UNREACHABLE
                    + "-"
                    + UNREACHABLE_VERSION
                    + ".tgz")
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

    // The recording is what proves it was an outage and not a refusal: the index was asked for
    // once, and the tarball never reached this mock at all — it was dialled at the closed port the
    // stubbed packument named.
    assertEquals(1, requestsTo(upstream, "/" + UNREACHABLE));
    assertEquals(
        0,
        upstream.recordedRequests().stream()
            .filter(request -> request.path().startsWith("/" + UNREACHABLE + "/-/"))
            .count(),
        "the tarball fetch must have gone to the dead address the packument named");

    story
        .happened(
            "a build container",
            "qits-platform-mirror",
            "GET the tarball of " + UNREACHABLE + "@" + UNREACHABLE_VERSION + " -> 502, not 404")
        .as("an-outage-is-a-502");
    story
        .happened(
            "qits-platform-mirror",
            UPSTREAM,
            "GET /" + UNREACHABLE + " -> 200 (the index resolved; only the bytes did not)")
        .as("the-index-was-fine");

    // (d) and one path with no handler behind it, on the machine plane. It answers npm's envelope
    // rather than Vert.x' HTML page, which is the shape every refusal above shares.
    //
    // WHAT THIS DOES AND DOES NOT PIN. It is NpmRoutes' own catch-all under /artifacts/npm, and
    // that is all: `quarkus.quinoa.ignored-path-prefixes` — the list that keeps the SPA fallback
    // from answering a machine path with index.html — is not under test here, because this
    // pipeline builds with -Dquarkus.quinoa=false and the launched artifact carries no client at
    // all. That list belongs to the packaged-surface probe the README still owes.
    given()
        .get(NPM_BASE + "/-/v1/search")
        .then()
        .statusCode(404)
        .contentType(startsWith("application/json"))
        .body("error", startsWith("not a route this npm registry serves"));
    story
        .happened(
            "a build container",
            "qits-platform-mirror",
            "GET " + NPM_BASE + "/-/v1/search -> 404 JSON, never an HTML page")
        .as("a-machine-path-answers-json");
  }

  /**
   * A packument, read the way an npm client reads one.
   *
   * <p>Jackson rather than rest-assured's GPath, for the reason spelled out at the first call site:
   * {@code dist-tags} and a dotted version number are both things Groovy reads as syntax.
   */
  private static JsonNode parse(String body) {
    try {
      return new ObjectMapper().readTree(body);
    } catch (Exception notJson) {
      throw new IllegalStateException("not a JSON document: " + body, notJson);
    }
  }

  /** How many times the mock upstream answered exactly {@code path} (query strings excluded). */
  private static long requestsTo(MockService upstream, String path) {
    return upstream.recordedRequests().stream()
        .filter(request -> path.equals(request.path()))
        .count();
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, PULL_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY, PULL_SLUG, "qits-platform-mirror", UPSTREAM, "GET /" + CACHED + " (a cold miss)");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "cache-roots-seeded-at-boot");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "index-requested");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "index-fetched-upstream");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "tarball-requested");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "tarball-fetched-upstream");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "second-build-served-from-cache");
    ReportAssertions.assertStepId(CATEGORY, PULL_SLUG, "cached-version-visible-in-the-explorer");

    ReportAssertions.assertComplete(CATEGORY, REFUSAL_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "unknown-package-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "upstream-said-no");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "the-no-was-not-remembered");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "an-outage-is-a-502");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "the-index-was-fine");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "a-machine-path-answers-json");
  }
}
