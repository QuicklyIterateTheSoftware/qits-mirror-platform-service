package eu.wohlben.qits.mirror.stories.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.mirror.stories.support.AccessLogTap;
import eu.wohlben.qits.mirror.stories.support.Cli;
import eu.wohlben.qits.mirror.stories.support.NpmUpstreamFixture;
import eu.wohlben.qits.mirror.stories.support.RecordingUpstream;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * <b>The flow this whole service exists for, run by the tool that really runs it.</b>
 *
 * <p>Every other story in this catalogue drives the wire. These two drive {@code npm}. The
 * difference buys three claims that no wire-level assertion in this repository can make, and each
 * of them is a thing that has broken a mirror in the field:
 *
 * <ul>
 *   <li><b>npm follows the rewritten {@code dist.tarball}.</b> That URL is not a config key — it is
 *       built from the request, because its right value depends on the authority the request arrived
 *       on. A packument that echoed upstream's URL would send the very next request past this
 *       service to the internet, and every assertion about the <em>packument</em> would still pass.
 *       Here the archive lands in {@code node_modules} or it does not.
 *   <li><b>npm verifies {@code integrity} end to end.</b> The {@code sha512} in the {@code dist}
 *       block is upstream's, re-emitted by this service untouched and never checked by it — so the
 *       install is a check of this cache's bytes against a hash this process never computed and
 *       could not forge. An install that completes is that check passing.
 *   <li><b>npm accepts a registry mounted under a path.</b> {@code /artifacts/npm/npmjs/} is not a
 *       host root, and a client that mis-derived a tarball URL from it would 404 on every fetch.
 * </ul>
 *
 * <h2>The tap is different here, and that is the interesting part</h2>
 *
 * <p>A RestAssured filter sees nothing of this: npm talks to the packaged process over a socket this
 * JVM never touches. So these two stories are the only ones that arm {@link AccessLogTap} — the
 * launched process' own access log, read back as edges. Read that class's comment for why it is
 * <em>armed</em> rather than always-on: every other story's requests are logged there too, and an
 * unarmed source would draw each of them a second time.
 *
 * <p>Their edges therefore carry {@link NetworkEdge#PACKAGE} rather than {@code http}, which is the
 * one place in this catalogue that kind says something the transport does not: the client is a
 * package manager doing a package manager's job.
 *
 * <h2>No request count, and one that is</h2>
 *
 * <p>How many requests an {@code npm install} is belongs to npm, not to this repository — a version
 * of it that fetched one more document would fail a count that promises nothing. So the incoming
 * side is pinned with {@code assertEdge} plus {@code assertOnlyEdgesFrom}: the two requests that
 * <em>are</em> the story must be there, and nobody but this story's build may have initiated
 * anything.
 *
 * <p>The <b>upstream</b> count is a different matter entirely, and it is exact. What this service
 * asks a registry is this service's promise and nobody else's: two fetches for the cold install, and
 * none at all for the warm one.
 *
 * <h2>{@code @TestMethodOrder} is load-bearing</h2>
 *
 * <p>The warm story is only warm because the cold story ran, and the cumulative upstream recording
 * is attributed by a cursor — so the two upstream fetches belong on the cold story's diagram and the
 * warm story's empty slice is what {@code assertNoEdgesTo} reads. The second install also uses a
 * <b>fresh npm cache of its own</b>: without that npm would answer out of its own store and prove
 * nothing about this service at all — and the story would notice, because its incoming edges would
 * vanish too.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("eu.wohlben.qits.mirror.stories.support.Cli#npmPresent")
public class NpmInstallThroughTheMirrorIT {

  static final String CATEGORY = "npm";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String COLD_STORY = "A build installs a dependency through the mirror with npm";

  static final String COLD_SLUG = Slugs.slug(COLD_STORY);

  static final String WARM_STORY =
      "A second build installs the same dependency and the mirror asks upstream nothing";

  static final String WARM_SLUG = Slugs.slug(WARM_STORY);

  /** This class's own package. Nothing called this exists on the real npmjs. */
  static final String PACKAGE = "story-npm-install";

  static final String VERSION = "2.1.0";

  private static final String BUILD = "a build container";

  private static final String NEXT_BUILD = "a second build container";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SERVED_INDEX = StoryTarget.NPM_BASE + "/" + PACKAGE;

  private static final String SERVED_TARBALL =
      StoryTarget.NPM_BASE + NpmUpstreamFixture.tarballPath(PACKAGE, VERSION);

  private static final String UPSTREAM_INDEX = NpmUpstreamFixture.packumentPath(PACKAGE);

  private static final String UPSTREAM_TARBALL = NpmUpstreamFixture.tarballPath(PACKAGE, VERSION);

  /** The consuming project's own manifest — npm refuses to install without one. */
  private static final String CONSUMER_MANIFEST =
      """
      {
        "name": "story-consumer",
        "version": "0.0.0",
        "private": true,
        "description": "A build that resolves its dependencies through the platform mirror."
      }
      """;

  @TestHTTPResource("/")
  URL root;

  @BeforeAll
  static void tapBothEndsAndHostThePackage() {
    StoryNetwork.install();
    NpmUpstreamFixture.host(
        RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM), PACKAGE, VERSION);
  }

  private static RecordingUpstream registry() {
    return RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM);
  }

  /**
   * The registry URL npm is pointed at: the {@code npmjs} cache root inside {@code /artifacts/npm}.
   * Trailing slash included, because that is the spelling npm stores in a lockfile and the one every
   * derived URL hangs off.
   */
  private String registryUrl() {
    String base = root.toString();
    return (base.endsWith("/") ? base : base + "/") + StoryTarget.NPM_BASE.substring(1) + "/";
  }

  @UserStory(value = COLD_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A build container with nothing cached anywhere runs `npm install` against the mirror, and
      that is the whole story: no fixture pretends to be a client, and no assertion stands in for
      the tool.

      One install is two requests on the wire. First the packument, which resolves the version.
      Then the tarball at the absolute URL that packument advertises — the one field this service
      rewrites, pointed back at the authority the request arrived on, which is why an install
      routed here stays routed here.

      npm verifies the archive against the `integrity` in the packument before it unpacks
      anything. That hash is upstream's, re-emitted by this cache untouched and never checked by
      it, so an install that completes is a check of this cache's bytes against a claim this
      process could not have forged.

      What lands on disk is then read in Java, because "npm exited zero" and "the right version is
      in node_modules" are two different claims, and only the second one is about the bytes.

      On the far side: exactly two fetches, the packument and the archive, and no third.
      """)
  @Order(1)
  void aBuildInstallsThroughTheMirror(Interactions story, Commands commands, Network net)
      throws IOException {
    RecordingUpstream registry = registry();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the packument, the version row and the tarball bytes");

    // Whose traffic the access log's next lines are, and what kind. Set before the first command,
    // because the framework reads both when it drains — and the actor is reset at every story
    // border, so this story never inherits another's.
    AccessLogTap.arm(BUILD, NetworkEdge.PACKAGE);

    // HOME is not writable in every container this runs in, and npm writes there unasked.
    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    // A cache of its own, inside the scratch that is wiped per run: an install that answered from
    // a warm npm cache would pass with this service switched off entirely.
    commands.env(
        "npm_config_cache", commands.workDir().resolve(".npm-cold").toAbsolutePath().toString());
    // npm's update-notifier fetches the package named `npm` from whatever registry it was pointed
    // at — traffic nobody here asked for, in a diagram that is about what a build asked for.
    commands.env("npm_config_update_notifier", "false");
    commands.env("npm_config_fund", "false");
    commands.env("npm_config_audit", "false");

    commands.in("cold-build");
    commands.file("package.json", CONSUMER_MANIFEST).as("consumer-prepared");

    commands
        .run(
            "{} install {}@{} --registry {}",
            Cli.npm(),
            PACKAGE,
            VERSION,
            registryUrl())
        .as("package-installed");

    assertInstalled(commands.workDir().resolve("cold-build"));
    story
        .note(
            "node_modules holds the version that was asked for — npm resolved it, fetched it and"
                + " verified upstream's integrity against the bytes this cache served")
        .as("contents-verified");

    // The far side, which is this service's own promise rather than npm's: two fetches, no third.
    assertEquals(
        1, registry.requestsTo(UPSTREAM_INDEX), "the packument was fetched from upstream once");
    assertEquals(
        1, registry.requestsTo(UPSTREAM_TARBALL), "and the archive once");
    story
        .note(
            "one `npm install` is two requests, and on a cold cache each of them costs the registry"
                + " exactly one fetch")
        .as("two-fetches-for-two-requests");

    // The receiver writes off the request thread, so the last line can still be in flight when the
    // story returns — and a line that lands after the drain is a line in no story's diagram.
    AccessLogTap.awaitLogged(SERVED_TARBALL);
  }

  @UserStory(value = WARM_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The same install, in a different directory, with an npm cache npm has never used before —
      so every byte has to come from the mirror, and the mirror is what is under test rather than
      npm's own store.

      The install succeeds identically, and the registry hears nothing at all. That is the claim
      the whole service is for, made this time by the real client: the second build container on
      the second agent on the second branch costs npmjs nothing.

      An absence is the one thing a diagram cannot draw, so the proof is an assertion that no edge
      in this story reaches the registry — taken against a registry that is up and recording
      throughout, and that had answered twice ten seconds earlier.
      """)
  @Order(2)
  void aSecondBuildInstallsWithoutTouchingUpstream(
      Interactions story, Commands commands, Network net) throws IOException {
    RecordingUpstream registry = registry();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument, the version row and the tarball bytes");

    AccessLogTap.arm(NEXT_BUILD, NetworkEdge.PACKAGE);

    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    // THE LINE THAT MAKES THIS STORY ABOUT THIS SERVICE. A shared npm cache would answer both
    // requests locally, the mirror would never be asked, and every assertion below would pass
    // against a mirror that was switched off.
    commands.env(
        "npm_config_cache", commands.workDir().resolve(".npm-warm").toAbsolutePath().toString());
    commands.env("npm_config_update_notifier", "false");
    commands.env("npm_config_fund", "false");
    commands.env("npm_config_audit", "false");

    commands.in("warm-build");
    commands.file("package.json", CONSUMER_MANIFEST).as("second-consumer-prepared");

    commands
        .run(
            "{} install {}@{} --registry {}",
            Cli.npm(),
            PACKAGE,
            VERSION,
            registryUrl())
        .as("package-installed-again")
        ;

    assertInstalled(commands.workDir().resolve("warm-build"));

    assertEquals(
        1,
        registry.requestsTo(UPSTREAM_INDEX),
        "the packument is inside its TTL and must not be re-asked");
    assertEquals(
        1,
        registry.requestsTo(UPSTREAM_TARBALL),
        "and a cached archive must never be re-fetched — it is immutable and content-addressed");

    story
        .note(
            "a second build container with a cold npm cache installed the same version, and the"
                + " registry's recording never moved")
        .as("second-install-served-from-cache");
    story
        .note(
            "an absence is not an edge: this story's diagram has no arrow to the registry at all,"
                + " which is what a mirror is bought for and what only the real client can show")
        .as("upstream-was-never-dialled");

    AccessLogTap.awaitLogged(SERVED_TARBALL);
  }

  /** What is on disk, read in Java — npm's exit code says the resolve worked, this says the bytes. */
  private static void assertInstalled(Path buildDir) throws IOException {
    Path installed = buildDir.resolve("node_modules").resolve(PACKAGE).resolve("package.json");
    assertTrue(Files.isRegularFile(installed), () -> "npm installed nothing at " + installed);
    JsonNode manifest = JSON.readTree(Files.readString(installed));
    assertEquals(PACKAGE, manifest.path("name").asText(), "the installed package's name");
    assertEquals(VERSION, manifest.path("version").asText(), "the installed package's version");
  }

  @AfterAll
  static void giveTheArmBackAndCheckTheReports() {
    // Belongs here rather than at the end of a story body: the framework drains a story's edges
    // AFTER the body returns, so disarming inside one would drop that story's own traffic.
    AccessLogTap.disarm();
    if (!Cli.npmPresent()) {
      return;
    }

    ReportAssertions.assertComplete(CATEGORY_SLUG, COLD_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertWroteFile(CATEGORY_SLUG, COLD_SLUG, "package.json");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "consumer-prepared");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "package-installed");
    ReportAssertions.assertCommand(CATEGORY_SLUG, COLD_SLUG, "install " + PACKAGE, 0);
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "contents-verified");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "two-fetches-for-two-requests");

    // What `npm install` actually is on the wire, observed in the launched process' own access log.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.PACKAGE,
        BUILD,
        AccessLogTap.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.PACKAGE,
        BUILD,
        AccessLogTap.SERVICE,
        StoryTarget.served("GET", SERVED_TARBALL, 200));
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
    // No count: how many requests an install is belongs to npm. The set of INITIATORS is still
    // exactly this story's promise, and a leaked actor is precisely what it catches.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, COLD_SLUG, java.util.List.of(BUILD, StoryTarget.SERVICE));

    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "second-consumer-prepared");
    ReportAssertions.assertCommand(CATEGORY_SLUG, WARM_SLUG, "install " + PACKAGE, 0);
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "second-install-served-from-cache");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "upstream-was-never-dialled");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.PACKAGE,
        NEXT_BUILD,
        AccessLogTap.SERVICE,
        StoryTarget.served("GET", SERVED_INDEX, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.PACKAGE,
        NEXT_BUILD,
        AccessLogTap.SERVICE,
        StoryTarget.served("GET", SERVED_TARBALL, 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached packument, the version row and the tarball bytes");
    // THE CLAIM, made by the real client.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, WARM_SLUG, StoryTarget.NPM_UPSTREAM);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, WARM_SLUG, java.util.List.of(NEXT_BUILD, StoryTarget.SERVICE));

    // The one run-local value in reach: the ephemeral port the recording registry bound. npm never
    // sees it — it talks only to this service — but the packument this service served named it, so
    // pinning that it reached no label is what keeps these two stories' networkHash settled.
    for (String slug : java.util.List.of(COLD_SLUG, WARM_SLUG)) {
      ReportAssertions.assertNotLeaked(
          CATEGORY_SLUG, slug, RecordingUpstream.attach(StoryTarget.NPM_UPSTREAM).baseUrl());
    }
  }
}
