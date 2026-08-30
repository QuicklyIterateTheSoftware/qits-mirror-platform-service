package eu.wohlben.qits.mirror.stories.oci;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.mirror.stories.support.RecordingUpstream;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.registry.TinyImage;
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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The third plane, and the one whose cold miss is measured in gigabytes: a container pull served
 * out of the mirror instead of out of quay.io.</b>
 *
 * <p>The OCI plane differs from the other two in a way that shows up in every line below. npm and
 * maven each front <em>one address that is a config key</em>; a container namespace fronts a
 * <b>row</b> — {@code oci_mirror_upstream}, prefilled by V1 with {@code docker.io}, {@code quay.io}
 * and {@code registry.access.redhat.com} — because which registries are mirrored has a CRUD surface
 * and a UI, while a config key is invisible. So the namespace this story pulls through, {@code
 * quay}, exists because a <b>migration</b> put it there and a boot re-ensured it, and the story is
 * entitled to assume it the way it assumes the {@code npmjs} cache root.
 *
 * <p>The pull is driven on the wire rather than by {@code docker pull}, and the reason is not
 * squeamishness: a {@code docker pull} is executed by a <em>daemon</em>, so the loopback address a
 * story would hand it is resolved in another process' network namespace, and the daemon would
 * additionally demand TLS or an entry in its insecure-registries list. {@code skopeo}, which
 * sidesteps exactly that in sibling repositories, is not installed here. What a client does once its
 * daemon has resolved the host is three plain requests, and that is what these stories make.
 *
 * <h2>One arrow for two blobs, on purpose</h2>
 *
 * <p>A pull fetches a config blob and a layer blob, and both are addressed <b>by digest</b> — which
 * the default label scrubber rewrites to {@code {digest}}, because a digest is the most run-local
 * value there is and a label carrying one would move this story's {@code networkHash} on every
 * build. So the two requests dedupe to a single edge on each side. That is the right shape: the
 * diagram says blobs were fetched by digest, and the story's counted assertions say how many and
 * which. A diagram that named two hashes would be a diagram nobody could diff.
 *
 * <h2>{@code @TestMethodOrder} is load-bearing</h2>
 *
 * <p>The same reason as on the other two planes: "warm" is a state the cold story creates, and the
 * cumulative upstream recording is attributed by a cursor, so the cold story's three fetches belong
 * on the cold story's diagram and the warm story's empty slice is what {@code assertNoEdgesTo}
 * reads.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImagePullThroughIT {

  static final String CATEGORY = "oci";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String COLD_STORY = "A container pull fills the mirror from the registry it fronts";

  static final String COLD_SLUG = Slugs.slug(COLD_STORY);

  static final String WARM_STORY = "The next pull of the same tag reaches no registry at all";

  static final String WARM_SLUG = Slugs.slug(WARM_STORY);

  /** This class's own image. Nothing on the real quay.io is called this. */
  public static final String IMAGE = "story-mirror-image";

  /** A movable pointer, which is the one mirrored thing with a TTL. */
  public static final String TAG = "stable";

  private static final String PULLER = "a container runtime";

  private static final String NEXT_PULLER = "a second container runtime";

  /** Every manifest media type a real client offers, which is what the mirror forwards upstream. */
  private static final String MANIFEST_ACCEPT =
      String.join(
          ", ",
          TinyImage.MANIFEST_TYPE,
          TinyImage.INDEX_TYPE,
          "application/vnd.docker.distribution.manifest.v2+json",
          "application/vnd.docker.distribution.manifest.list.v2+json");

  private static TinyImage upstreamImage;

  // --- the paths, on both sides -------------------------------------------------------------------

  /** What a client asks THIS service for: the namespace segment, then the Distribution grammar. */
  private static final String SERVED_MANIFEST =
      StoryTarget.OCI_BASE + "/" + IMAGE + "/manifests/" + TAG;

  /** What this service asks the REGISTRY for: no namespace — that is this mirror's own layer. */
  private static final String UPSTREAM_MANIFEST = "/v2/" + IMAGE + "/manifests/" + TAG;

  @BeforeAll
  static void tapBothEndsAndHostTheImage() {
    StoryNetwork.install();
    RecordingUpstream quay = quay();
    upstreamImage = TinyImage.of(IMAGE);

    Map<String, String> manifestHeaders =
        Map.of("Docker-Content-Digest", upstreamImage.manifestDigest());
    quay.serve(
        UPSTREAM_MANIFEST,
        upstreamImage.manifestMediaType(),
        upstreamImage.manifest(),
        manifestHeaders);
    // Also by digest, the way a real registry holds it — a client that resolved the tag once may
    // ask for the manifest by digest ever after, and so may this mirror.
    quay.serve(
        "/v2/" + IMAGE + "/manifests/" + upstreamImage.manifestDigest(),
        upstreamImage.manifestMediaType(),
        upstreamImage.manifest(),
        manifestHeaders);
    quay.serve(
        upstreamBlob(upstreamImage.config().digest()),
        "application/octet-stream",
        upstreamImage.config().bytes());
    quay.serve(
        upstreamBlob(upstreamImage.layer().digest()),
        "application/octet-stream",
        upstreamImage.layer().bytes());
  }

  private static RecordingUpstream quay() {
    return RecordingUpstream.attach(StoryTarget.OCI_UPSTREAM);
  }

  private static String upstreamBlob(String digest) {
    return "/v2/" + IMAGE + "/blobs/" + digest;
  }

  private static String servedBlob(String digest) {
    return StoryTarget.OCI_BASE + "/" + IMAGE + "/blobs/" + digest;
  }

  /**
   * A blob GET, with RestAssured's URL encoding <b>off</b>.
   *
   * <p>This is not a preference. A blob is addressed by {@code sha256:<hex>} and the colon is part
   * of the reference; RestAssured percent-encodes it to {@code sha256%3A…} by default, and {@code
   * RegistryPaths}' route regex matches the literal colon, so every blob request would 404 with a
   * {@code UNSUPPORTED} envelope naming an escaped path. Every real client — docker, podman,
   * skopeo, containerd — sends the colon raw, which is what makes the encoded spelling the wrong
   * question rather than a stricter one.
   */
  private static io.restassured.response.Response blob(String digest) {
    return given().urlEncodingEnabled(false).get(servedBlob(digest)).andReturn();
  }

  @UserStory(value = COLD_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A runtime pulls an image the mirror has never held. It resolves the tag, then asks for the
      two blobs the manifest names — a config and a layer — which is what a pull is once the
      daemon has resolved the host.

      The manifest arrives byte for byte, and that is a hard requirement rather than a nicety: a
      manifest's digest covers its literal whitespace, so a mirror that re-serialised the document
      would serve something nobody can address by the digest it advertises. The
      `Docker-Content-Digest` header the client verifies against comes back with it.

      The blobs are fetched by digest and verified as they stream — this service hashes what
      arrives and refuses bytes that do not hash to what was asked for, so it cannot cache a
      corrupted layer even if the upstream served one. And the namespace itself needed no operator:
      `quay` is a row a migration prefilled and a boot re-ensured, together with the upstream it
      fronts.

      Three requests to quay.io, and no fourth.
      """)
  @Order(1)
  void aPullFillsTheMirror(Interactions story, Network net) {
    RecordingUpstream quay = quay();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the manifest, the tag binding and the blob bytes");

    NetworkCapture.actor(PULLER);

    byte[] manifest =
        given()
            .header("Accept", MANIFEST_ACCEPT)
            .get(SERVED_MANIFEST)
            .then()
            .statusCode(200)
            .header("Docker-Content-Digest", equalTo(upstreamImage.manifestDigest()))
            .header("Content-Type", upstreamImage.manifestMediaType())
            .extract()
            .asByteArray();
    assertArrayEquals(
        upstreamImage.manifest(),
        manifest,
        "byte-for-byte, because a manifest's digest covers its literal whitespace: a mirror that"
            + " re-serialised would serve a document nobody can address");
    assertEquals(
        1, quay.requestsTo(UPSTREAM_MANIFEST), "resolving a cold tag is one upstream read");

    story
        .note(
            "the tag resolves through a namespace a migration prefilled — nobody registered quay,"
                + " and the manifest comes back byte for byte with the digest a client verifies")
        .as("tag-resolved-through-a-prefilled-namespace");

    assertArrayEquals(
        upstreamImage.config().bytes(),
        blob(upstreamImage.config().digest()).then().statusCode(200).extract().asByteArray(),
        "the config blob must be upstream's bytes");
    assertArrayEquals(
        upstreamImage.layer().bytes(),
        blob(upstreamImage.layer().digest()).then().statusCode(200).extract().asByteArray(),
        "and so must the layer");

    assertEquals(
        1,
        quay.requestsTo(upstreamBlob(upstreamImage.config().digest())),
        "the config blob is fetched once");
    assertEquals(
        1,
        quay.requestsTo(upstreamBlob(upstreamImage.layer().digest())),
        "and the layer once");
    assertEquals(
        3,
        quay.recordedRequests().stream()
            .filter(request -> request.path().startsWith("/v2/" + IMAGE + "/"))
            .count(),
        "a whole cold pull is three upstream requests — manifest, config, layer — and no fourth");

    story
        .note(
            "the two blobs the manifest names are pulled through by digest and verified as they"
                + " stream: bytes that do not hash to what was asked for are refused, never cached")
        .as("blobs-pulled-through-and-verified");
    story
        .note(
            "a whole cold pull cost quay.io three requests — one manifest and one per blob — which"
                + " is the number every later pull of this tag is measured against")
        .as("three-upstream-requests");
  }

  @UserStory(value = WARM_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The same pull again, on a mirror that now holds it, and quay.io hears nothing.

      The tag was agreed with its upstream recently enough to serve without asking — an OCI tag is
      a movable pointer, so it is the one mirrored thing with a TTL, and inside that window there
      is not even a revalidating HEAD. The blobs are addressed by digest and are immutable by
      construction: there has never been a question to ask about them.

      This is the plane where the claim is worth the most. A container layer is measured in
      hundreds of megabytes, and the difference between a fleet that pulls its base images from a
      mirror and one that pulls them from the internet is the difference between a deploy and an
      egress bill. The proof is once again an absence: no edge in this diagram reaches the
      registry, measured against a registry that is up and recording throughout.
      """)
  @Order(2)
  void theNextPullReachesNoRegistry(Interactions story, Network net) {
    RecordingUpstream quay = quay();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the manifest, the tag binding and the blob bytes");

    NetworkCapture.actor(NEXT_PULLER);

    assertArrayEquals(
        upstreamImage.manifest(),
        given()
            .header("Accept", MANIFEST_ACCEPT)
            .get(SERVED_MANIFEST)
            .then()
            .statusCode(200)
            .header("Docker-Content-Digest", equalTo(upstreamImage.manifestDigest()))
            .extract()
            .asByteArray());
    assertArrayEquals(
        upstreamImage.config().bytes(),
        blob(upstreamImage.config().digest()).then().statusCode(200).extract().asByteArray());
    assertArrayEquals(
        upstreamImage.layer().bytes(),
        blob(upstreamImage.layer().digest()).then().statusCode(200).extract().asByteArray());

    assertEquals(
        3,
        quay.recordedRequests().stream()
            .filter(request -> request.path().startsWith("/v2/" + IMAGE + "/"))
            .count(),
        "still three — the whole second pull was served from this disk, and not even the tag was"
            + " revalidated");

    story
        .note(
            "the whole pull came off this disk: the tag is inside its TTL, so there was not even a"
                + " revalidating HEAD, and a blob addressed by digest has never had a question to"
                + " ask")
        .as("the-whole-pull-was-local");
    story
        .note(
            "an absence is not an edge: this story's diagram has no arrow to the registry at all,"
                + " which on this plane is the difference between a deploy and an egress bill")
        .as("the-registry-was-never-dialled");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, COLD_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        PULLER,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_MANIFEST, 200));
    // One arrow for both blobs: the digest is scrubbed to {digest}, so the two requests share a
    // label and dedupe on the quadruple. The counts in the story body are what tell them apart.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        PULLER,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", servedBlob(upstreamImage.config().digest()), 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.OCI_UPSTREAM,
        StoryTarget.fetched("GET", UPSTREAM_MANIFEST, "200"));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryTarget.OCI_UPSTREAM,
        StoryTarget.fetched("GET", upstreamBlob(upstreamImage.config().digest()), "200"));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the manifest, the tag binding and the blob bytes");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, COLD_SLUG, 5);
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, COLD_SLUG, "tag-resolved-through-a-prefilled-namespace");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "blobs-pulled-through-and-verified");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "three-upstream-requests");

    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        NEXT_PULLER,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", SERVED_MANIFEST, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        NEXT_PULLER,
        StoryTarget.SERVICE,
        StoryTarget.served("GET", servedBlob(upstreamImage.layer().digest()), 200));
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the manifest, the tag binding and the blob bytes");
    // THE CLAIM, on the third plane.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, WARM_SLUG, StoryTarget.OCI_UPSTREAM);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, WARM_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, WARM_SLUG, java.util.List.of(NEXT_PULLER, StoryTarget.SERVICE));
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "the-whole-pull-was-local");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "the-registry-was-never-dialled");

    // The one run-local value that reaches this JVM and must reach no report: the ephemeral port
    // the recording registry bound. A label carrying it would move the networkHash on every run —
    // and this service is anonymous by design, so a port is the only secret-shaped thing there is.
    for (String slug : java.util.List.of(COLD_SLUG, WARM_SLUG)) {
      ReportAssertions.assertNotLeaked(
          CATEGORY_SLUG, slug, RecordingUpstream.attach(StoryTarget.OCI_UPSTREAM).baseUrl());
    }
  }
}
