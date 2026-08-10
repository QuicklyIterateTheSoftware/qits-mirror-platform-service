package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * A synthetic OCI client: a full push and a full pull, in about as many lines as it takes to say
 * what the protocol is.
 *
 * <p>It exists because {@code mvn verify} may not assume docker, podman or skopeo is installed —
 * the clone-alone rule that makes {@code GitHostTest} drive the real {@code git} CLI is the same
 * rule that forbids this suite driving the real docker one. The roundtrip still has to be proved
 * somewhere that runs on every build.
 *
 * <p>A plain JDK {@link HttpClient} rather than RestAssured, for one reason that matters: {@code
 * BodyPublishers.ofInputStream} sends a body with <b>no Content-Length</b>, and that chunked path is
 * exactly what {@code quarkus.http.limits.max-body-size} does not bound on a raw Vert.x route — and
 * exactly what {@code docker push} uses for layers. RestAssured always sets a Content-Length, so
 * that case would go untested. HTTP/1.1 is pinned for the same fidelity reason.
 *
 * <p>The division of labour follows {@code GitHostTest}: this client is for <b>protocol shape</b>,
 * while RestAssured stays the tool for status codes, headers and the JSON error envelope.
 */
public final class OciClient implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private String authorization;

  public OciClient(URI base) {
    this.base = base;
  }

  /** What {@code skopeo --dest-creds} / {@code podman push --creds} send. */
  public OciClient basicAuth(String user, String password) {
    authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    return this;
  }

  @Override
  public void close() {
    http.close();
  }

  public int versionProbe() {
    return send(request("/v2/").GET(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  public boolean blobExists(String name, String digest) {
    return send(
                request("/v2/" + name + "/blobs/" + digest).method("HEAD", noBody()),
                HttpResponse.BodyHandlers.discarding())
            .statusCode()
        == 200;
  }

  public byte[] getBlob(String name, String digest) {
    HttpResponse<byte[]> response =
        send(request("/v2/" + name + "/blobs/" + digest).GET(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), "GET blob " + digest);
    return response.body();
  }

  /** Opens a session and returns the path from {@code Location}. */
  public String startUpload(String name) {
    HttpResponse<String> response =
        send(
            request("/v2/" + name + "/blobs/uploads/").POST(noBody()),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode(), "open upload: " + response.body());
    return location(response);
  }

  public HttpResponse<String> mountBlob(String name, String digest, String fromName) {
    return send(
        request("/v2/" + name + "/blobs/uploads/?mount=" + digest + "&from=" + fromName)
            .POST(noBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> monolithicUpload(String name, String digest, byte[] bytes) {
    return send(
        request("/v2/" + name + "/blobs/uploads/?digest=" + digest).POST(chunked(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The single streaming PATCH real clients send. */
  public HttpResponse<String> patchUpload(String location, byte[] bytes) {
    return send(
        request(location).method("PATCH", chunked(bytes)), HttpResponse.BodyHandlers.ofString());
  }

  /** A PATCH declaring where its chunk starts, for the resumable multi-chunk flow. */
  public HttpResponse<String> patchUploadAt(String location, byte[] bytes, long from) {
    return send(
        request(location)
            .header("Content-Range", from + "-" + (from + bytes.length - 1))
            .method("PATCH", chunked(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> finishUpload(String location, String digest) {
    String separator = location.contains("?") ? "&" : "?";
    return send(
        request(location + separator + "digest=" + digest).PUT(noBody()),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> putManifest(String name, String reference, byte[] manifest, String mediaType) {
    return send(
        request("/v2/" + name + "/manifests/" + reference)
            .header("Content-Type", mediaType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(manifest)),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<byte[]> getManifest(String name, String reference) {
    return send(
        request("/v2/" + name + "/manifests/" + reference)
            .header("Accept", String.join(", ", List.copyOf(java.util.Set.of(
                TinyImage.MANIFEST_TYPE, TinyImage.INDEX_TYPE))))
            .GET(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  public List<String> listTags(String name, String query) {
    HttpResponse<String> response =
        send(
            request("/v2/" + name + "/tags/list" + (query == null ? "" : query)).GET(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    try {
      JsonNode tags = JSON.readTree(response.body()).path("tags");
      return java.util.stream.StreamSupport.stream(tags.spliterator(), false)
          .map(JsonNode::asText)
          .toList();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- the composites the tests actually call ---------------------------------------------------

  /**
   * A full push, in the order a real client does it: HEAD each blob, upload only what is missing,
   * then the manifest last.
   */
  public void push(String name, String tag, TinyImage image) {
    for (TinyImage.Blob blob : List.of(image.config(), image.layer())) {
      if (blobExists(name, blob.digest())) {
        continue;
      }
      String session = startUpload(name);
      assertEquals(202, patchUpload(session, blob.bytes()).statusCode(), "PATCH " + blob.digest());
      assertEquals(
          201, finishUpload(session, blob.digest()).statusCode(), "PUT " + blob.digest());
    }
    HttpResponse<String> response =
        putManifest(name, tag, image.manifest(), image.manifestMediaType());
    assertEquals(201, response.statusCode(), "PUT manifest: " + response.body());
  }

  /**
   * A full pull, re-verifying every digest it receives — which is what turns an
   * {@code assertEquals(pushed, pulled)} into a statement about the registry rather than about this
   * client.
   */
  public TinyImage pull(String name, String reference) {
    HttpResponse<byte[]> manifestResponse = getManifest(name, reference);
    assertEquals(200, manifestResponse.statusCode(), "GET manifest " + reference);
    byte[] manifest = manifestResponse.body();
    String mediaType = manifestResponse.headers().firstValue("content-type").orElseThrow();
    assertEquals(
        TinyImage.digest(manifest),
        manifestResponse.headers().firstValue("docker-content-digest").orElseThrow(),
        "the registry's Docker-Content-Digest must match the bytes it served");

    try {
      JsonNode root = JSON.readTree(manifest);
      TinyImage.Blob config = fetch(name, root.path("config"));
      TinyImage.Blob layer = fetch(name, root.path("layers").get(0));
      return new TinyImage(layer, config, manifest, mediaType);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private TinyImage.Blob fetch(String name, JsonNode descriptor) {
    String digest = descriptor.path("digest").asText();
    byte[] bytes = getBlob(name, digest);
    assertEquals(digest, TinyImage.digest(bytes), "served blob does not match its own digest");
    assertTrue(bytes.length > 0);
    return new TinyImage.Blob(digest, bytes, descriptor.path("mediaType").asText());
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private HttpRequest.Builder request(String pathOrLocation) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(pathOrLocation));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return builder;
  }

  private static HttpRequest.BodyPublisher noBody() {
    return HttpRequest.BodyPublishers.noBody();
  }

  /** No Content-Length: the encoding docker uses, and the one the wire ceiling does not gate. */
  private static HttpRequest.BodyPublisher chunked(byte[] bytes) {
    return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
  }

  private static String location(HttpResponse<?> response) {
    return response.headers().firstValue("location").orElseThrow();
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return http.send(builder.build(), bodyHandler);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
