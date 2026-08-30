package eu.wohlben.qits.mirror.stories.operations;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.mirror.stories.caching.PullThroughBootstrapIT;
import eu.wohlben.qits.mirror.stories.maven.MavenPullThroughIT;
import eu.wohlben.qits.mirror.stories.oci.ImagePullThroughIT;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>What an operator sees, and — the point of the story — what looking costs.</b>
 *
 * <p>{@code /mirror/api} is this service's only JAX-RS surface and it has no write. There is no
 * create, no delete and no "evict now": what this service holds is decided by what somebody pulled,
 * and what it drops is decided by a window in configuration and a clock. A page that could delete a
 * cached tag would be a second eviction policy with no record of why it ran.
 *
 * <p>So the interesting property of this surface is not what it returns. It is that <b>reading it
 * dials nobody</b> — and that is a claim worth pinning precisely because the opposite is such an
 * attractive mistake. An inventory page that asked each upstream what it holds would look better,
 * answer more, and turn one operator opening a browser tab into three registry round trips per row;
 * it would also stop working the moment a registry did, which is the one time an operator most needs
 * to see what is cached. This story makes the negative on all three planes at once, with all three
 * registries up and recording throughout.
 *
 * <h2>Directional, and why not the other spelling</h2>
 *
 * <p>{@code assertNoEdgesFrom(SERVICE)} would be the blunter claim and it would be <b>false</b>:
 * this service dials its own store to answer, and says so with a declared edge. The honest claim is
 * about the boundary rather than the caller — nothing reached the three things being spared — which
 * is exactly {@code assertNoEdgesTo}, made once per registry.
 *
 * <h2>The failure mode this surface must never have</h2>
 *
 * <p>A failed read here has to reach the caller as a failure. Nothing in {@code MirrorExplorer}
 * catches a database error to answer an empty list, and nothing may be added that does: an empty
 * {@code repositories} array means "this mirror caches nothing", which during an outage is false and
 * looks like a configuration somebody should go and repair. The story cannot break a database to
 * show that, so it pins the neighbouring shape it can: an unknown cache root is a <b>404</b>, and a
 * known one that holds nothing would be an empty list — two different answers, which is the whole
 * rule.
 *
 * <h2>{@code @UserflowRunsAfter}, and what it does and does not promise</h2>
 *
 * <p>An inventory is only worth reading when there is something in it, so this story is ordered
 * after the three pull-through classes and asserts the rows they left. That is an <b>ordering</b>
 * dependency and not a gate: {@code @UserflowRunsAfter} ignores their outcome, which is right —
 * if a pull failed, an inventory that cannot find what it pulled is telling the truth about a
 * broken cache and should be red too.
 *
 * <p>Every listing assertion below is a <b>filter</b> (a {@code find} by name) rather than a claim
 * about the whole collection. Other stories in this catalogue pull other packages through the same
 * roots, and a story that counted rows would break every time the catalogue grew.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class CacheInventoryIT {

  static final String CATEGORY = "operations";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "An operator reads what the mirror holds and fronts, and the reading dials nobody";

  static final String SLUG = Slugs.slug(STORY);

  private static final String OPERATOR = "a platform operator";

  /** A cache root nobody seeded and nobody ever will. */
  private static final String UNKNOWN_ROOT = "not-a-cache-root";

  private static final String NPM_PACKAGES =
      StoryTarget.EXPLORER + "/" + StoryTarget.NPM_CACHE + "/packages";

  private static final String MAVEN_PACKAGES =
      StoryTarget.EXPLORER + "/" + StoryTarget.MAVEN_CACHE + "/packages";

  private static final String OCI_PACKAGES =
      StoryTarget.EXPLORER + "/" + StoryTarget.OCI_NAMESPACE + "/packages";

  private static final String UNKNOWN_PACKAGES =
      StoryTarget.EXPLORER + "/" + UNKNOWN_ROOT + "/packages";

  /** The maven coordinate as the explorer normalizes it: group, colon, artifact. */
  private static final String MAVEN_COORDINATE =
      MavenPullThroughIT.GROUP_ID + ":" + MavenPullThroughIT.ARTIFACT_ID;

  @BeforeAll
  static void tapTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserflowRunsAfter({
    PullThroughBootstrapIT.class,
    MavenPullThroughIT.class,
    ImagePullThroughIT.class
  })
  @UserStoryDescription(
      """
      An operator opens the explorer. Five cache roots: the npm and maven ones a boot seeds, and
      the three container namespaces a migration prefills — and each one names what it is a cache
      OF, which is the whole reason a row here is interesting. For npm and maven that is a
      configuration key; for a container namespace it is a row in a table, because which registries
      are mirrored has a CRUD surface and a UI while a config key is invisible.

      Then the drill-down, once per plane, and each one shows exactly what somebody pulled: a
      package at a version, a maven coordinate at a version with the files that make it up, and an
      image with the manifest digest a runtime addresses it by. A mirror holds what was asked for
      and nothing else, so this page is a record of demand rather than a catalogue.

      And a cache root that does not exist is a 404 — not an empty listing. "I have nothing under
      that name" and "there is no such name" are different answers, and this service is the worst
      place on the platform to give the first one when the second is true.

      What none of it did was ask anybody. Not npmjs, not Central, not quay.io. An inventory that
      dialled its upstreams would turn one open browser tab into three registry round trips per
      row, and would go blank during exactly the outage an operator needs it in.
      """)
  void anOperatorReadsTheInventory(Interactions story, Network net) {
    // Five listings and a refusal, all of them reads of this service's own store — which is the
    // only thing the surface touches, and the point of the negatives below.
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cache roots, the upstream rows and what is cached under each");

    NetworkCapture.actor(OPERATOR);

    // --- the roots, and what each fronts ------------------------------------------------------------
    given()
        .get(StoryTarget.EXPLORER)
        .then()
        .statusCode(200)
        .body(
            "repositories.find { it.name == '" + StoryTarget.NPM_CACHE + "' }.type",
            equalTo("npm-proxy"))
        .body(
            "repositories.find { it.name == '" + StoryTarget.NPM_CACHE + "' }.upstream",
            notNullValue())
        .body(
            "repositories.find { it.name == '" + StoryTarget.MAVEN_CACHE + "' }.type",
            equalTo("maven-proxy"))
        .body(
            "repositories.find { it.name == '" + StoryTarget.OCI_NAMESPACE + "' }.type",
            equalTo("oci-mirror"))
        .body("repositories.findAll { it.type == 'oci-mirror' }.size()", equalTo(3));
    story
        .note(
            "five cache roots: two a boot seeds and three a migration prefills, each naming what it"
                + " is a cache OF — which is the whole reason a row here is worth a page")
        .as("every-cache-root-names-what-it-fronts");

    // --- which container registries are mirrored ----------------------------------------------------
    // A row rather than a key, which is why this listing exists at all. `quay` has been pulled
    // through by now, so its cached-image count is a number the pull made rather than a constant.
    given()
        .get(StoryTarget.UPSTREAMS)
        .then()
        .statusCode(200)
        .body(
            "upstreams.find { it.namespace == '" + StoryTarget.OCI_NAMESPACE + "' }.host",
            equalTo(StoryTarget.OCI_UPSTREAM))
        .body(
            "upstreams.find { it.namespace == '" + StoryTarget.OCI_NAMESPACE + "' }.cachedImages",
            greaterThanOrEqualTo(1))
        .body("upstreams.find { it.namespace == 'hub' }.host", equalTo("docker.io"))
        .body(
            "upstreams.find { it.namespace == 'redhat' }.host",
            equalTo("registry.access.redhat.com"));
    story
        .note(
            "the container upstreams are rows, not keys: a namespace, the registry it fronts, and"
                + " how much of it has actually been pulled through")
        .as("mirrored-registries-are-rows");

    // --- one drill-down per plane -------------------------------------------------------------------
    given()
        .get(NPM_PACKAGES)
        .then()
        .statusCode(200)
        .body("repository.type", equalTo("npm-proxy"))
        .body(
            "packages.find { it.name == '"
                + PullThroughBootstrapIT.PACKAGE
                + "' }.versions[0].version",
            equalTo(PullThroughBootstrapIT.VERSION));

    given()
        .get(MAVEN_PACKAGES)
        .then()
        .statusCode(200)
        .body("repository.type", equalTo("maven-proxy"))
        .body(
            "packages.find { it.name == '" + MAVEN_COORDINATE + "' }.versions[0].version",
            equalTo(MavenPullThroughIT.VERSION));

    given()
        .get(OCI_PACKAGES)
        .then()
        .statusCode(200)
        .body("repository.type", equalTo("oci-mirror"))
        .body(
            "packages.find { it.name == '" + ImagePullThroughIT.IMAGE + "' }.versions[0].labels",
            equalTo(java.util.List.of(ImagePullThroughIT.TAG)));
    story
        .note(
            "each plane's drill-down shows exactly what somebody pulled — a package version, a"
                + " maven coordinate, and an image tag bound to the manifest a runtime addresses")
        .as("what-was-pulled-is-what-is-listed");

    // --- and a name that is not a cache root --------------------------------------------------------
    given().get(UNKNOWN_PACKAGES).then().statusCode(404);
    story
        .note(
            "a cache root that does not exist is a 404 and never an empty listing: \"I hold nothing"
                + " under that name\" and \"there is no such name\" are different answers")
        .as("an-unknown-root-is-a-404");
  }

  @AfterAll
  static void theStoryReportIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);
    for (String path :
        java.util.List.of(
            StoryTarget.EXPLORER, StoryTarget.UPSTREAMS, NPM_PACKAGES, MAVEN_PACKAGES,
            OCI_PACKAGES)) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          SLUG,
          NetworkEdge.HTTP,
          OPERATOR,
          StoryTarget.SERVICE,
          StoryTarget.served("GET", path, 200));
    }
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", UNKNOWN_PACKAGES, 404));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cache roots, the upstream rows and what is cached under each");

    // THE CLAIM, on all three planes at once: reading the inventory reached none of them.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.NPM_UPSTREAM);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.MAVEN_UPSTREAM);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.OCI_UPSTREAM);
    // Six reads and one declared store dependency, and nothing else at all.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, java.util.List.of(OPERATOR, StoryTarget.SERVICE));

    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "every-cache-root-names-what-it-fronts");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "mirrored-registries-are-rows");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "what-was-pulled-is-what-is-listed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "an-unknown-root-is-a-404");
  }
}
