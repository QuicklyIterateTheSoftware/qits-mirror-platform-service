package eu.wohlben.qits.npm;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process npm registry for the proxy suite to be a proxy <em>of</em>.
 *
 * <p>The clone-alone rule makes this mandatory rather than convenient: {@code mvn verify} must be
 * green from a bare checkout with <b>no network</b>, so a test that reached registry.npmjs.org would
 * fail on an aeroplane and pass in CI for reasons unrelated to this code — and worse, would pass
 * <em>wrongly</em> here, since npmjs answers 404 for a synthetic package exactly as an unconfigured
 * proxy would. Counting upstream requests, which is what every caching claim rests on, needs a stub
 * regardless.
 *
 * <p><b>Why it is driven over HTTP rather than by touching fields.</b> Quarkus instantiates a {@code
 * QuarkusTestProfile} in <em>two</em> classloaders — once in the JUnit {@code ParentLastURLClassLoader}
 * (whose overrides are the ones that reach the running application) and once in the Quarkus runtime
 * loader (which is where the test class itself lives). A plain static singleton therefore exists
 * twice: the application talks to one server while the test registers packages on another, and every
 * assertion fails with an empty document for a reason nothing in the test names. Anchoring the
 * server in a <b>system property</b> — the one namespace both loaders share — and making every
 * mutation a request means the second instance is simply a client of the first, and which loader got
 * there first stops mattering.
 */
final class StubNpmRegistry {

  static final StubNpmRegistry INSTANCE = new StubNpmRegistry();

  private static final String ANCHOR = "qits.test.npm-upstream-url";
  private static final String CONTROL = "/_control/";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Only populated in the instance that actually started the server; the other one is a client.
  private final Map<String, String> packuments = new ConcurrentHashMap<>();
  private final Map<String, String> etags = new ConcurrentHashMap<>();
  private final Map<String, byte[]> tarballs = new ConcurrentHashMap<>();
  private final AtomicInteger packumentRequests = new AtomicInteger();
  private final AtomicInteger conditionalRequests = new AtomicInteger();
  private final AtomicInteger tarballRequests = new AtomicInteger();
  private volatile boolean reachable = true;

  private StubNpmRegistry() {
    String existing = System.getProperty(ANCHOR);
    if (existing != null) {
      baseUrl = existing;
      return;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub npm registry", e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(null);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty(ANCHOR, baseUrl);
  }

  /** What {@code qits.artifacts.npm.proxy.upstream} is pointed at. */
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

  int packumentRequests() {
    return counters().path("packument").asInt();
  }

  /** How many packument requests carried an {@code If-None-Match} — i.e. were revalidations. */
  int conditionalRequests() {
    return counters().path("conditional").asInt();
  }

  int tarballRequests() {
    return counters().path("tarball").asInt();
  }

  /**
   * Publishes a package upstream: a packument naming its version as {@code latest}, and the tarball
   * that packument points at. The {@code dist} block carries upstream's own hashes, which the proxy
   * must re-emit unmodified — that is what the installing client verifies against.
   */
  void hostPackage(TinyPackage subject) {
    hostVersions(subject.name(), subject);
  }

  /** The same, for a package upstream has more than one version of. */
  void hostVersions(String name, TinyPackage... versions) {
    Map<String, Object> versionsNode = new LinkedHashMap<>();
    String latest = null;
    for (TinyPackage version : versions) {
      Map<String, Object> manifest = new LinkedHashMap<>(version.manifest());
      manifest.put(
          "dist",
          Map.of(
              "tarball", baseUrl + "/" + name + "/-/" + version.tarballFile(),
              "shasum", version.shasum(),
              "integrity", version.integrity()));
      versionsNode.put(version.version(), manifest);
      control(
          "tarball", Map.of("X-Path", name + "/-/" + version.tarballFile()), version.tarball());
      latest = version.version();
    }
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("_id", name);
    document.put("name", name);
    document.put("dist-tags", Map.of("latest", latest));
    document.put("versions", versionsNode);
    document.put("_upstream", "stub"); // a marker no rewrite may drop

    control("packument", Map.of("X-Package", name), encode(document));
  }

  // --- the server -------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws java.io.IOException {
    String path = exchange.getRequestURI().getPath();
    // Control first, and before the reachability switch: turning upstream back on must work while
    // it is off.
    if (path.startsWith(CONTROL)) {
      handleControl(exchange, path.substring(CONTROL.length()));
      return;
    }
    if (!reachable) {
      // No status, no body: the connection simply goes away, which is what an outage looks like to
      // an HttpClient and the branch the stale-serving path exists for.
      exchange.close();
      return;
    }
    // getPath() is already percent-decoded, so `@scope%2fname` and `@scope/name` arrive the same —
    // exactly as they do at npmjs, which is why the proxy is free to send either.
    String resource = path.substring(1);
    int separator = resource.indexOf("/-/");

    if (separator > 0) {
      tarballRequests.incrementAndGet();
      byte[] tarball = tarballs.get(resource);
      if (tarball == null) {
        respond(exchange, 404, "{\"error\":\"no such tarball\"}".getBytes(StandardCharsets.UTF_8));
        return;
      }
      respond(exchange, 200, tarball);
      return;
    }

    packumentRequests.incrementAndGet();
    String doc = packuments.get(resource);
    if (doc == null) {
      respond(exchange, 404, "{\"error\":\"Not found\"}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String etag = etags.get(resource);
    String conditional = exchange.getRequestHeaders().getFirst("If-None-Match");
    if (conditional != null) {
      conditionalRequests.incrementAndGet();
      if (conditional.equals(etag)) {
        exchange.getResponseHeaders().add("ETag", etag);
        exchange.sendResponseHeaders(304, -1);
        exchange.close();
        return;
      }
    }
    exchange.getResponseHeaders().add("ETag", etag);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    respond(exchange, 200, doc.getBytes(StandardCharsets.UTF_8));
  }

  private void handleControl(HttpExchange exchange, String command) throws java.io.IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    switch (command) {
      case "reset" -> {
        packuments.clear();
        etags.clear();
        tarballs.clear();
        packumentRequests.set(0);
        conditionalRequests.set(0);
        tarballRequests.set(0);
        reachable = true;
        respond(exchange, 200, new byte[0]);
      }
      case "reachable" -> {
        reachable = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "packument" -> {
        String name = exchange.getRequestHeaders().getFirst("X-Package");
        String doc = new String(body, StandardCharsets.UTF_8);
        packuments.put(name, doc);
        etags.put(name, "\"" + Integer.toHexString(doc.hashCode()) + "\"");
        respond(exchange, 200, new byte[0]);
      }
      case "tarball" -> {
        tarballs.put(exchange.getRequestHeaders().getFirst("X-Path"), body);
        respond(exchange, 200, new byte[0]);
      }
      case "counters" ->
          respond(
              exchange,
              200,
              encode(
                  Map.of(
                      "packument", packumentRequests.get(),
                      "conditional", conditionalRequests.get(),
                      "tarball", tarballRequests.get())));
      default -> respond(exchange, 404, new byte[0]);
    }
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

  private static byte[] encode(Map<String, Object> document) {
    try {
      return JSON.writeValueAsBytes(document);
    } catch (Exception e) {
      throw new IllegalStateException(e);
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
