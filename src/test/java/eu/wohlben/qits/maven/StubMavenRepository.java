package eu.wohlben.qits.maven;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process maven repository for the proxy suite to be a proxy <em>of</em>.
 *
 * <p>{@code npm/StubNpmRegistry}'s shape and its two reasons, unchanged. The clone-alone rule makes
 * it mandatory rather than convenient: {@code mvn verify} must be green from a bare checkout with
 * <b>no network</b>, so a test that reached repo1.maven.org would fail on an aeroplane and pass in
 * CI for reasons unrelated to this code — and worse, would pass <em>wrongly</em>, since Central
 * answers 404 for a synthetic artifact exactly as an unconfigured proxy would. Counting upstream
 * requests, which is what every caching claim rests on, needs a stub regardless.
 *
 * <p><b>Why it is driven over HTTP rather than by touching fields.</b> Quarkus instantiates a {@code
 * QuarkusTestProfile} in <em>two</em> classloaders, so a plain static singleton exists twice: the
 * application talks to one server while the test registers artifacts on another, and every assertion
 * fails with an empty answer for a reason nothing in the test names. Anchoring the server in a
 * <b>system property</b> — the one namespace both loaders share — and making every mutation a
 * request means the second instance is simply a client of the first.
 */
final class StubMavenRepository {

  static final StubMavenRepository INSTANCE = new StubMavenRepository();

  private static final String ANCHOR = "qits.test.maven-upstream-url";
  private static final String CONTROL = "/_control/";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Only populated in the instance that actually started the server; the other one is a client.
  private final Map<String, byte[]> files = new ConcurrentHashMap<>();
  private final Map<String, String> metadata = new ConcurrentHashMap<>();
  private final Map<String, String> etags = new ConcurrentHashMap<>();
  private final AtomicInteger metadataRequests = new AtomicInteger();
  private final AtomicInteger conditionalRequests = new AtomicInteger();
  private final AtomicInteger fileRequests = new AtomicInteger();
  private volatile boolean reachable = true;
  private volatile boolean withEtags = true;

  private StubMavenRepository() {
    String existing = System.getProperty(ANCHOR);
    if (existing != null) {
      baseUrl = existing;
      return;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub maven repository", e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(null);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty(ANCHOR, baseUrl);
  }

  /** What {@code qits.artifacts.maven.proxy.upstream} is pointed at. */
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

  /**
   * Whether upstream answers an {@code ETag} at all. False leaves only {@code Last-Modified}, which
   * is the older validator a maven repository behind a plain file server has — and the branch the
   * proxy would otherwise never take in this suite.
   */
  void withEtags(boolean value) {
    control("etags", Map.of("X-Value", Boolean.toString(value)), new byte[0]);
  }

  int metadataRequests() {
    return counters().path("metadata").asInt();
  }

  /** How many metadata requests carried a validator — i.e. were revalidations rather than fetches. */
  int conditionalRequests() {
    return counters().path("conditional").asInt();
  }

  int fileRequests() {
    return counters().path("file").asInt();
  }

  /** Hosts one file at a repository-relative path — a jar, a pom, or a checksum sibling. */
  void hostFile(String path, byte[] content) {
    control("file", Map.of("X-Path", path), content);
  }

  /** Hosts one {@code maven-metadata.xml}, which is the only path answered with a validator. */
  void hostMetadata(String path, String document) {
    control("metadata", Map.of("X-Path", path), document.getBytes(StandardCharsets.UTF_8));
  }

  /** A real metadata document, listing the versions upstream has of one artifact. */
  static String metadataDocument(String groupId, String artifactId, String... versions) {
    StringBuilder document = new StringBuilder();
    document
        .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n  <groupId>")
        .append(groupId)
        .append("</groupId>\n  <artifactId>")
        .append(artifactId)
        .append("</artifactId>\n  <versioning>\n    <latest>")
        .append(versions[versions.length - 1])
        .append("</latest>\n    <release>")
        .append(versions[versions.length - 1])
        .append("</release>\n    <versions>\n");
    for (String version : versions) {
      document.append("      <version>").append(version).append("</version>\n");
    }
    return document.append("    </versions>\n  </versioning>\n</metadata>\n").toString();
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
    String resource = path.substring(1);

    if (resource.endsWith("/maven-metadata.xml")) {
      metadataRequests.incrementAndGet();
      String document = metadata.get(resource);
      if (document == null) {
        respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
        return;
      }
      String etag = etags.get(resource);
      String lastModified = lastModifiedOf(document);
      String conditional = exchange.getRequestHeaders().getFirst("If-None-Match");
      String since = exchange.getRequestHeaders().getFirst("If-Modified-Since");
      if (conditional != null || since != null) {
        conditionalRequests.incrementAndGet();
        if ((conditional != null && conditional.equals(etag))
            || (conditional == null && lastModified.equals(since))) {
          if (withEtags) {
            exchange.getResponseHeaders().add("ETag", etag);
          }
          exchange.getResponseHeaders().add("Last-Modified", lastModified);
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
          return;
        }
      }
      if (withEtags) {
        exchange.getResponseHeaders().add("ETag", etag);
      }
      exchange.getResponseHeaders().add("Last-Modified", lastModified);
      exchange.getResponseHeaders().add("Content-Type", "text/xml");
      respond(exchange, 200, document.getBytes(StandardCharsets.UTF_8));
      return;
    }

    fileRequests.incrementAndGet();
    byte[] content = files.get(resource);
    if (content == null) {
      respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
      return;
    }
    respond(exchange, 200, content);
  }

  private void handleControl(HttpExchange exchange, String command) throws java.io.IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    switch (command) {
      case "reset" -> {
        files.clear();
        metadata.clear();
        etags.clear();
        metadataRequests.set(0);
        conditionalRequests.set(0);
        fileRequests.set(0);
        reachable = true;
        withEtags = true;
        respond(exchange, 200, new byte[0]);
      }
      case "reachable" -> {
        reachable = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "etags" -> {
        withEtags = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, new byte[0]);
      }
      case "file" -> {
        files.put(exchange.getRequestHeaders().getFirst("X-Path"), body);
        respond(exchange, 200, new byte[0]);
      }
      case "metadata" -> {
        String path = exchange.getRequestHeaders().getFirst("X-Path");
        String document = new String(body, StandardCharsets.UTF_8);
        metadata.put(path, document);
        etags.put(path, "\"" + Integer.toHexString(document.hashCode()) + "\"");
        respond(exchange, 200, new byte[0]);
      }
      case "counters" ->
          respond(
              exchange,
              200,
              encode(
                  Map.of(
                      "metadata", metadataRequests.get(),
                      "conditional", conditionalRequests.get(),
                      "file", fileRequests.get())));
      default -> respond(exchange, 404, new byte[0]);
    }
  }

  /** Derived from the document, so hosting a new one moves the validator without a clock. */
  private static String lastModifiedOf(String document) {
    return "Mon, 0" + (Math.abs(document.hashCode()) % 9 + 1) + " Aug 2026 00:00:00 GMT";
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
