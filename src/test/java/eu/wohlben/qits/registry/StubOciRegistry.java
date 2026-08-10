package eu.wohlben.qits.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process container registry for the mirror suite to be a mirror <em>of</em>.
 *
 * <p>Mandatory rather than convenient, for the reason {@code StubNpmRegistry}'s javadoc gives at
 * length and this one will not repeat: {@code mvn verify} must be green from a bare checkout with
 * <b>no network</b>. The registry case is worse than npm's, though, and worth naming. The three
 * prefilled upstreams are quay.io, Docker Hub and Red Hat — real, reachable, and perfectly capable
 * of making a test pass for reasons that have nothing to do with this code. A suite that dialled
 * them would be slow, would fail on an aeroplane, and would silently stop testing the fetch path the
 * day someone's image was retagged.
 *
 * <p>It is reached through {@code qits.artifacts.oci.mirror.endpoint-override}, which redirects
 * every upstream here regardless of the domain its row carries. That is also why the suite's default
 * for that key is a <b>closed port</b>: opting in is explicit, and a test that has not opted in
 * cannot reach the internet at all.
 *
 * <p>Driven over HTTP rather than by touching fields, anchored in a system property — the same
 * two-classloader hazard {@code StubNpmRegistry} documents applies here unchanged.
 */
final class StubOciRegistry {

  static final StubOciRegistry INSTANCE = new StubOciRegistry();

  private static final String ANCHOR = "qits.test.oci-upstream-url";
  private static final String CONTROL = "/_control/";
  private static final String TOKEN_PATH = "/token";
  private static final String TOKEN = "stub-upstream-token";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Only populated in the instance that actually started the server; the other one is a client.
  private final Map<String, byte[]> manifests = new ConcurrentHashMap<>();
  private final Map<String, String> manifestTypes = new ConcurrentHashMap<>();
  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
  private final Set<String> corrupted = ConcurrentHashMap.newKeySet();
  private final AtomicInteger manifestGets = new AtomicInteger();
  private final AtomicInteger manifestHeads = new AtomicInteger();
  private final AtomicInteger blobGets = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private volatile boolean reachable = true;
  private volatile boolean requireAuth;
  private volatile long stallMillis;

  private StubOciRegistry() {
    String existing = System.getProperty(ANCHOR);
    if (existing != null) {
      baseUrl = existing;
      return;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub OCI registry", e);
    }
    server.createContext("/", this::handle);
    // A pool rather than the default in-line executor: one test deliberately stalls a response to
    // prove the request timeout, and the control channel has to stay answerable while it does.
    server.setExecutor(
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "stub-oci-registry");
              thread.setDaemon(true);
              return thread;
            }));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty(ANCHOR, baseUrl);
  }

  /** What {@code qits.artifacts.oci.mirror.endpoint-override} is pointed at. */
  String baseUrl() {
    return baseUrl;
  }

  void reset() {
    control("reset", Map.of(), new byte[0]);
  }

  /** Whether upstream answers at all. False drops every connection: an outage, not an error page. */
  void reachable(boolean value) {
    control("reachable", Map.of("X-Value", Boolean.toString(value)), new byte[0]);
  }

  /** Whether {@code /v2} demands the bearer dance, as Docker Hub does even for public images. */
  void requireAuth(boolean value) {
    control("require-auth", Map.of("X-Value", Boolean.toString(value)), new byte[0]);
  }

  /** How long every {@code /v2} response is held before it is written — the timeout's test. */
  void stall(long millis) {
    control("stall", Map.of("X-Value", Long.toString(millis)), new byte[0]);
  }

  int manifestGets() {
    return counters().path("manifestGet").asInt();
  }

  int manifestHeads() {
    return counters().path("manifestHead").asInt();
  }

  int blobGets() {
    return counters().path("blobGet").asInt();
  }

  int tokenRequests() {
    return counters().path("token").asInt();
  }

  /** Every upstream request that moved bytes — what "zero upstream fetches" is counted with. */
  int fetches() {
    return manifestGets() + blobGets();
  }

  // --- what upstream holds ----------------------------------------------------------------------

  /** Hosts an image under a tag: the manifest by tag AND by digest, plus its two blobs. */
  void hostImage(String name, String tag, TinyImage image) {
    hostManifest(name, tag, image.manifestMediaType(), image.manifest());
    hostManifest(name, image.manifestDigest(), image.manifestMediaType(), image.manifest());
    hostBlob(image.config().digest(), image.config().bytes());
    hostBlob(image.layer().digest(), image.layer().bytes());
  }

  /**
   * Hosts a multi-arch index under a tag, with its children reachable <b>by digest only</b> — which
   * is exactly how a real registry holds them, and what makes the lazy-child order observable.
   */
  void hostIndex(String name, String tag, byte[] index, TinyImage... children) {
    hostManifest(name, tag, TinyImage.INDEX_TYPE, index);
    hostManifest(name, TinyImage.digest(index), TinyImage.INDEX_TYPE, index);
    for (TinyImage child : children) {
      hostManifest(name, child.manifestDigest(), child.manifestMediaType(), child.manifest());
      hostBlob(child.config().digest(), child.config().bytes());
      hostBlob(child.layer().digest(), child.layer().bytes());
    }
  }

  void hostManifest(String name, String reference, String mediaType, byte[] bytes) {
    control("manifest", Map.of("X-Name", name, "X-Ref", reference, "X-Media-Type", mediaType), bytes);
  }

  void hostBlob(String digest, byte[] bytes) {
    control("blob", Map.of("X-Digest", digest), bytes);
  }

  /** Serves this blob's bytes with one flipped — a corrupted stream, not a missing one. */
  void corrupt(String digest) {
    control("corrupt", Map.of("X-Digest", digest), new byte[0]);
  }

  // --- the server -------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws java.io.IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.startsWith(CONTROL)) {
      handleControl(exchange, path.substring(CONTROL.length()));
      return;
    }
    if (!reachable) {
      // No status, no body: the connection simply goes away, which is what an outage looks like to
      // an HttpClient and the branch the serve-stale path exists for.
      exchange.close();
      return;
    }
    if (stallMillis > 0) {
      try {
        Thread.sleep(stallMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (path.equals(TOKEN_PATH)) {
      tokenRequests.incrementAndGet();
      respond(
          exchange,
          200,
          ("{\"token\":\"" + TOKEN + "\",\"expires_in\":300}").getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (requireAuth && !("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      // The challenge Docker Hub sends for a public image: a realm, a service and a scope, and no
      // credential of any kind needed to satisfy it.
      String scope = "repository:" + repositoryOf(path) + ":pull";
      exchange
          .getResponseHeaders()
          .add(
              "WWW-Authenticate",
              "Bearer realm=\"" + baseUrl + TOKEN_PATH + "\",service=\"stub\",scope=\"" + scope + "\"");
      respond(exchange, 401, "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}".getBytes(StandardCharsets.UTF_8));
      return;
    }

    int manifestsAt = path.indexOf("/manifests/");
    if (path.startsWith("/v2/") && manifestsAt > 0) {
      String key = path.substring("/v2/".length(), manifestsAt) + "|" + path.substring(manifestsAt + "/manifests/".length());
      boolean head = "HEAD".equals(exchange.getRequestMethod());
      if (head) {
        manifestHeads.incrementAndGet();
      } else {
        manifestGets.incrementAndGet();
      }
      byte[] manifest = manifests.get(key);
      if (manifest == null) {
        respond(exchange, 404, notFound());
        return;
      }
      exchange.getResponseHeaders().add("Content-Type", manifestTypes.get(key));
      exchange.getResponseHeaders().add("Docker-Content-Digest", TinyImage.digest(manifest));
      if (head) {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      respond(exchange, 200, manifest);
      return;
    }

    int blobsAt = path.indexOf("/blobs/");
    if (path.startsWith("/v2/") && blobsAt > 0) {
      blobGets.incrementAndGet();
      String digest = path.substring(blobsAt + "/blobs/".length());
      byte[] bytes = blobs.get(digest);
      if (bytes == null) {
        respond(exchange, 404, notFound());
        return;
      }
      if (corrupted.contains(digest)) {
        byte[] wrong = bytes.clone();
        wrong[wrong.length - 1] = (byte) (wrong[wrong.length - 1] ^ 0xFF);
        bytes = wrong;
      }
      exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
      respond(exchange, 200, bytes);
      return;
    }
    respond(exchange, 404, notFound());
  }

  private void handleControl(HttpExchange exchange, String command) throws java.io.IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    switch (command) {
      case "reset" -> {
        manifests.clear();
        manifestTypes.clear();
        blobs.clear();
        corrupted.clear();
        manifestGets.set(0);
        manifestHeads.set(0);
        blobGets.set(0);
        tokenRequests.set(0);
        reachable = true;
        requireAuth = false;
        stallMillis = 0;
        respond(exchange, 200, new byte[0]);
      }
      case "reachable" -> {
        reachable = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "require-auth" -> {
        requireAuth = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "stall" -> {
        stallMillis = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "manifest" -> {
        String key =
            exchange.getRequestHeaders().getFirst("X-Name")
                + "|"
                + exchange.getRequestHeaders().getFirst("X-Ref");
        manifests.put(key, body);
        manifestTypes.put(key, exchange.getRequestHeaders().getFirst("X-Media-Type"));
        respond(exchange, 200, new byte[0]);
      }
      case "blob" -> {
        blobs.put(exchange.getRequestHeaders().getFirst("X-Digest"), body);
        respond(exchange, 200, new byte[0]);
      }
      case "corrupt" -> {
        corrupted.add(exchange.getRequestHeaders().getFirst("X-Digest"));
        respond(exchange, 200, new byte[0]);
      }
      case "counters" ->
          respond(
              exchange,
              200,
              ("{\"manifestGet\":"
                      + manifestGets.get()
                      + ",\"manifestHead\":"
                      + manifestHeads.get()
                      + ",\"blobGet\":"
                      + blobGets.get()
                      + ",\"token\":"
                      + tokenRequests.get()
                      + "}")
                  .getBytes(StandardCharsets.UTF_8));
      default -> respond(exchange, 404, new byte[0]);
    }
  }

  private static String repositoryOf(String path) {
    int end = path.indexOf("/manifests/");
    if (end < 0) {
      end = path.indexOf("/blobs/");
    }
    return end < 0 ? "unknown" : path.substring("/v2/".length(), end);
  }

  private static byte[] notFound() {
    return "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}".getBytes(StandardCharsets.UTF_8);
  }

  // --- the client half --------------------------------------------------------------------------

  private JsonNode counters() {
    try {
      return JSON.readTree(
          http.send(
                  HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + "counters")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void control(String command, Map<String, String> headers, byte[] body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(request::header);
    try {
      int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status != 200) {
        throw new IllegalStateException("stub control " + command + " answered " + status);
      }
    } catch (Exception e) {
      throw new IllegalStateException("stub control " + command + " failed", e);
    }
  }

  private static void respond(HttpExchange exchange, int status, byte[] body)
      throws java.io.IOException {
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
    exchange.close();
  }
}
