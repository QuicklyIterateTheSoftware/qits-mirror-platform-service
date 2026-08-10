package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OCI mirror, end to end through this service: a manifest and its blobs are fetched from an
 * upstream, verified, bound to a namespace and served — and the second pull touches nothing.
 *
 * <p>A <b>service</b> smoke test. The namespaces it pulls through ({@code quay}, {@code hub}) come
 * from the V1 prefill rather than from a fixture, which is the point: a fresh deployment of this
 * service mirrors quay, Red Hat and Docker Hub with no manual step, and this is where that claim is
 * checked against a real database. The protocol's edge cases — bearer challenges, digest
 * verification failures, TTL revalidation, multi-arch indexes — are proved once in
 * qits-registries-oci.
 *
 * <p>The routes are at the HOST ROOT ({@code /v2}), not under a segment: docker and podman resolve
 * an image reference against {@code <host>/v2/} and accept no prefix.
 *
 * <p>Image names are unique per test <b>and per run</b>. Nothing wipes {@code
 * target/mirror-test-blobs} between runs and blobs dedupe globally, so content reused between runs
 * would leave yesterday's layer on disk — a blob-store hit, which turns "the mirror fetched three
 * things" into "the mirror fetched two things" with nothing in the failure to say why.
 */
@QuarkusTest
@TestProfile(OciMirrorSmokeTest.AgainstTheStubUpstream.class)
class OciMirrorSmokeTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  public static class AgainstTheStubUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Every upstream — quay.io, docker.io, Red Hat — is dialled at the in-process stub. The
      // suite's default for this key is a closed port; opting in is deliberate and explicit.
      return Map.of(
          "qits.artifacts.oci.mirror.endpoint-override", StubOciRegistry.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void resetUpstream() {
    StubOciRegistry.INSTANCE.reset();
  }

  @Test
  void aManifestMissIsFetchedVerifiedBoundAndServed_andTheNextPullTouchesNoUpstream() {
    String image = "quarkus/builder-" + RUN + "-" + UNIQUE.incrementAndGet();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);

    byte[] served =
        given()
            .when()
            .get("/v2/quay/" + image + "/manifests/jdk-25")
            .then()
            .statusCode(200)
            .header("Docker-Content-Digest", upstream.manifestDigest())
            .header("Content-Type", upstream.manifestMediaType())
            .extract()
            .asByteArray();

    // Byte-for-byte, because a manifest's digest covers its literal whitespace: a mirror that
    // re-serialised would serve a document nobody can address.
    assertArrayEquals(upstream.manifest(), served);
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets(), "the miss costs exactly one fetch");

    given().when().get("/v2/quay/" + image + "/manifests/jdk-25").then().statusCode(200);
    assertEquals(
        1,
        StubOciRegistry.INSTANCE.manifestGets(),
        "a tag inside its TTL is served from disk with no upstream traffic at all");
    assertEquals(0, StubOciRegistry.INSTANCE.manifestHeads(), "and not even a revalidation");
  }

  @Test
  void aWholeImageRoundTripsThroughTheHubNamespaceAndTheSecondPullFetchesNothing() {
    // The feature, in one assertion pair: three upstream requests the first time — the manifest, the
    // config blob and the layer — and none at all the second. Driven by a client that re-verifies
    // every digest it is handed, through the `hub` namespace the V1 prefill ships.
    String image = "library/alpine-" + RUN + "-" + UNIQUE.incrementAndGet();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "3.21", upstream);

    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      TinyImage pulled = client.pull("hub/" + image, "3.21");
      assertArrayEquals(upstream.manifest(), pulled.manifest());
      assertArrayEquals(upstream.config().bytes(), pulled.config().bytes());
      assertArrayEquals(upstream.layer().bytes(), pulled.layer().bytes());
      assertEquals(3, StubOciRegistry.INSTANCE.fetches(), "manifest + config + layer, once each");

      TinyImage again = client.pull("hub/" + image, "3.21");
      assertArrayEquals(upstream.manifest(), again.manifest());
      assertEquals(
          3,
          StubOciRegistry.INSTANCE.fetches(),
          "everything the second pull needs is already on this disk");
    }
  }

  @Test
  void aPushToAMirrorNamespaceIsRefusedByType() {
    // Nothing pushes to this service — OCI_IMAGES is not a registered type here at all — and a
    // mirror namespace refuses a push because of what it is, not how it was configured.
    String image = "library/pushed-" + RUN + "-" + UNIQUE.incrementAndGet();
    TinyImage subject = TinyImage.of(image);
    given()
        .when()
        .post("/v2/hub/" + image + "/blobs/uploads/")
        .then()
        .statusCode(405);
    given()
        .contentType(subject.manifestMediaType())
        .body(subject.manifest())
        .when()
        .put("/v2/hub/" + image + "/manifests/latest")
        .then()
        .statusCode(405);
  }
}
