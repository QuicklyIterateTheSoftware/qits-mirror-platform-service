package eu.wohlben.qits.mirror.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * <b>The far side of every story in this catalogue: a registry this service is a cache OF, that
 * records what it was asked and can be made to stop answering mid-story.</b>
 *
 * <p>One class serves all three planes — an npm registry, a maven repository and an OCI registry —
 * because a pull-through cache treats each of them as the same three facts: a path, some bytes, and
 * a status. What differs between the planes is the content, and content is what a story supplies.
 *
 * <h2>Why this replaced {@code qits-service-mock}'s {@code MockService}</h2>
 *
 * <p>The previous shape of this repository's one story class stood a {@code MockService} where
 * npmjs is. It was a good fit for the two stories that existed and is the wrong tool for the
 * catalogue that now surrounds them, on four counts that are each independently fatal:
 *
 * <ul>
 *   <li><b>It serializes every stubbed body as JSON.</b> A packument really is JSON, so that cost
 *       nothing while the only client was RestAssured comparing strings. It cannot serve a
 *       <em>gzipped USTAR archive</em>, a <em>zip</em> or an <em>OCI layer</em> — so the real
 *       {@code npm install} in {@code stories/npm}, the jar in {@code stories/maven} and the image
 *       in {@code stories/oci} would all have been impossible, and with them every claim that rests
 *       on a real client verifying real bytes.
 *   <li><b>A stub can only be armed from the instance that started the server.</b> {@code
 *       MockService.attach} hands back a handle whose {@code stub} calls land in a second copy's
 *       map, so arming has to happen once, in the profile, before any story runs. {@link
 *       #reachable(boolean)} below is the opposite: {@code stories/outage} turns the registry dark
 *       <em>in the middle of a story</em>, which is the only way to observe what a mirror is bought
 *       for.
 *   <li><b>It has no way to not answer.</b> An outage is not a 500; it is a connection that goes
 *       away. {@link #reachable(boolean)} closes the exchange before any status is written, which is
 *       what {@code NpmUpstream}'s serve-stale branch and {@code MavenUpstream}'s actually see.
 *   <li><b>Its recording is exact-path and query-blind.</b> Fine here — no upstream this service
 *       dials is addressed by query — but it also means a recording could not have told a
 *       revalidation from a fetch. This one records the method, the path and <em>the status that
 *       was answered</em>, which is what makes {@code "GET /pkg -> dropped"} evidence of an outage
 *       rather than of a request.
 * </ul>
 *
 * <p>So {@code qits-service-mock} is no longer a dependency of this repository. It remains the right
 * tool for a service standing in for another <em>JSON API</em>; a cache's upstream is a byte store,
 * which is a different job.
 *
 * <h2>Two processes and three classloaders</h2>
 *
 * <p>This server is dialled by the <b>launched artifact</b>, which is a different process, so it has
 * to be a real socket on a real port and not an in-JVM fixture. It is also constructed from a {@code
 * QuarkusTestProfile}, which Quarkus instantiates in more than one classloader — so a plain static
 * singleton exists twice and the copy a story arms is not the copy the application talks to. Both
 * problems have the same answer, the one {@code npm/StubNpmRegistry} already uses in this
 * repository: the server's address is parked in a <b>system property</b>, the one namespace every
 * classloader in a JVM shares, and every mutation and every read is an HTTP request to that address.
 * The second instance is simply a client of the first, and which loader got there first stops
 * mattering.
 *
 * <h2>Recording discipline</h2>
 *
 * <p>The far-side tap rules this catalogue follows are written down in {@link StoryNetwork}; two of
 * them are this class's to keep:
 *
 * <ul>
 *   <li><b>A request is recorded BEFORE it is answered</b> — including a request that is never
 *       answered at all. A recording written after the response would miss exactly the case the
 *       outage story is about.
 *   <li><b>The recording is wiped when the server starts and never afterwards.</b> There is no
 *       floor and no reset: {@link eu.wohlben.qits.userflows.NetworkCapture#source} attributes a
 *       cumulative recording with a cursor, and a reset mid-run would re-attribute traffic to
 *       whichever story drained next. One process, one recording, one cursor.
 * </ul>
 */
public final class RecordingUpstream {

  /** Where a started server parks its address, per name. */
  private static final String ANCHOR_PREFIX = "qits.test.recording-upstream.";

  /** Everything under this prefix is control traffic and is never recorded or served. */
  private static final String CONTROL = "/_control/";

  /**
   * The status a recorded line carries when the connection was closed with no response at all — the
   * outage arm. Deliberately a word rather than a number: no status code was sent, and writing
   * {@code 000} would put a number in a diagram where none was on the wire.
   */
  public static final String DROPPED = "dropped";

  private static final Map<String, RecordingUpstream> INSTANCES = new ConcurrentHashMap<>();

  private final String name;
  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Populated only in the instance that actually started the server; the other one is a client.
  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private final List<String> recording = java.util.Collections.synchronizedList(new ArrayList<>());
  private volatile boolean reachable = true;

  private RecordingUpstream(String name) {
    this.name = name;
    String anchor = ANCHOR_PREFIX + name;
    String existing = System.getProperty(anchor);
    if (existing != null) {
      this.baseUrl = existing;
      return;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (Exception unstartable) {
      throw new IllegalStateException("could not start the recording upstream " + name, unstartable);
    }
    server.createContext("/", this::handle);
    // A pool rather than the caller-runs default: the launched process fetches a packument and a
    // tarball concurrently on a cold npm install, and a story that stalls one answer (there is no
    // sleeping arm today, but this is the shape one would take) must not stall the other.
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
    this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty(anchor, baseUrl);
  }

  /**
   * The one server for {@code name}, started on the first call in this JVM and attached to
   * afterwards.
   *
   * @param name how the network diagram names this upstream — {@code registry.npmjs.org}, {@code
   *     repo1.maven.org}, {@code quay.io}. It is deliberately the address a <b>deployment</b> really
   *     dials rather than a fixture's alias, so a reader of the diagram sees the dependency the
   *     shipped configuration declares.
   */
  public static RecordingUpstream named(String name) {
    return INSTANCES.computeIfAbsent(name, RecordingUpstream::new);
  }

  /**
   * The already-started server for {@code name} — what a <b>story class</b> calls, as against {@link
   * #named} which the profile calls to start it.
   *
   * <p>The distinction is a guard rather than style. {@code named} starts a server when no anchor is
   * found, which is right exactly once and catastrophic afterwards: a story that started a
   * <em>second</em> server would host its packages on a port the launched process has never heard
   * of, every fetch would 404 against the first server, and the failure would name a package rather
   * than the mistake. By the time any story runs, {@link StoryProfile} has already built the launch
   * command out of these addresses, so a missing anchor here can only mean the profile did not run.
   */
  public static RecordingUpstream attach(String name) {
    if (System.getProperty(ANCHOR_PREFIX + name) == null && !INSTANCES.containsKey(name)) {
      throw new IllegalStateException(
          "no recording upstream is running for "
              + name
              + " — StoryProfile starts all three, so a story class reaching this has been run"
              + " without it");
    }
    return named(name);
  }

  /** How the diagram names this upstream — also the {@code to} of every edge it produces. */
  public String name() {
    return name;
  }

  /** What the matching {@code qits.artifacts.*.upstream} key is pointed at. */
  public String baseUrl() {
    return baseUrl;
  }

  // --- what a story hosts ------------------------------------------------------------------------

  /** Serve {@code bytes} at exactly {@code path}, with a 200 and no extra headers. */
  public RecordingUpstream serve(String path, String contentType, byte[] bytes) {
    return serve(path, contentType, bytes, Map.of());
  }

  /**
   * Serve {@code bytes} at exactly {@code path}. Matching is on the <b>decoded</b> path, so {@code
   * @scope%2fname} and {@code @scope/name} are one route — which is what npmjs does and therefore
   * what lets this service send either spelling.
   *
   * @param headers extra response headers: an {@code ETag} for a packument, a {@code
   *     Docker-Content-Digest} for a manifest.
   */
  public RecordingUpstream serve(
      String path, String contentType, byte[] bytes, Map<String, String> headers) {
    Map<String, String> control = new LinkedHashMap<>();
    control.put("X-Path", encodeHeader(path));
    control.put("X-Content-Type", contentType);
    headers.forEach((header, value) -> control.put("X-Answer-" + header, encodeHeader(value)));
    control("serve", control, bytes);
    return this;
  }

  /**
   * Whether this registry answers at all.
   *
   * <p>{@code false} means the connection goes away with no status and no body, which is what an
   * outage looks like to a {@code java.net.http.HttpClient} and the branch {@code
   * NpmUpstream.serveStaleOrFail} exists for. A 500 would be a registry having an opinion; this is a
   * registry that is not there.
   *
   * <p>Armable <b>from a story method</b>, which is the whole point: the story turns the registry
   * dark between two reads and watches what the mirror does about it.
   */
  public void reachable(boolean value) {
    control("reachable", Map.of("X-Value", Boolean.toString(value)), new byte[0]);
  }

  // --- what the tap reads ------------------------------------------------------------------------

  /** One recorded exchange: what was asked, and what was answered. */
  public record Request(String method, String path, String status) {

    /** The label half of an edge, as {@link StoryNetwork} shapes it. */
    public String label() {
      return method + " " + path + " -> " + status;
    }
  }

  /**
   * The <b>whole</b> recording, every time — the contract {@link
   * eu.wohlben.qits.userflows.NetworkCapture#source} states, with the framework's per-source cursor
   * deciding which slice belongs to the story now draining.
   */
  public List<Request> recordedRequests() {
    String body = controlText("recording");
    List<Request> requests = new ArrayList<>();
    for (String line : body.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      // "<method>\t<path>\t<status>" — a tab, because a path may hold anything but one.
      String[] fields = line.split("\t", 3);
      if (fields.length == 3) {
        requests.add(new Request(fields[0], fields[1], fields[2]));
      }
    }
    return requests;
  }

  /** How many times this upstream was asked for exactly {@code path}, whatever it answered. */
  public long requestsTo(String path) {
    return recordedRequests().stream().filter(request -> path.equals(request.path())).count();
  }

  // --- the server --------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws java.io.IOException {
    String path = exchange.getRequestURI().getPath();
    // Control first, and before the reachability switch: turning the registry back on has to work
    // while it is off, and no control call is ever traffic a diagram should draw.
    if (path.startsWith(CONTROL)) {
      handleControl(exchange, path.substring(CONTROL.length()));
      return;
    }
    String method = exchange.getRequestMethod();
    if (!reachable) {
      // Recorded BEFORE the connection goes away — this is the one exchange whose evidence would
      // otherwise not exist, and it is the evidence the outage story is entirely about.
      record(method, path, DROPPED);
      exchange.close();
      return;
    }
    Answer answer = answers.get(path);
    if (answer == null) {
      // An unregistered route is this registry's genuine "no such thing". No arrangement needed,
      // and the body is JSON because both npmjs and a Distribution registry answer JSON here; a
      // maven repository answers text, and no story asserts on an upstream 404 body.
      record(method, path, "404");
      respond(exchange, 404, "application/json", "{\"error\":\"Not found\"}"
          .getBytes(StandardCharsets.UTF_8), Map.of(), !"HEAD".equals(method));
      return;
    }
    record(method, path, String.valueOf(answer.status));
    respond(
        exchange,
        answer.status,
        answer.contentType,
        answer.bytes,
        answer.headers,
        !"HEAD".equals(method));
  }

  private void record(String method, String path, String status) {
    recording.add(method + "\t" + path + "\t" + status);
  }

  private void handleControl(HttpExchange exchange, String command) throws java.io.IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    switch (command) {
      case "serve" -> {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange
            .getRequestHeaders()
            .forEach(
                (header, values) -> {
                  if (header.regionMatches(true, 0, "X-Answer-", 0, "X-Answer-".length())) {
                    headers.put(
                        header.substring("X-Answer-".length()), decodeHeader(values.getFirst()));
                  }
                });
        answers.put(
            decodeHeader(exchange.getRequestHeaders().getFirst("X-Path")),
            new Answer(
                200, exchange.getRequestHeaders().getFirst("X-Content-Type"), body, headers));
        respond(exchange, 200, "text/plain", new byte[0], Map.of(), false);
      }
      case "reachable" -> {
        reachable = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, "text/plain", new byte[0], Map.of(), false);
      }
      case "recording" -> {
        StringBuilder lines = new StringBuilder();
        // A copy under the list's own monitor: the launched process may be appending while this
        // renders, and a half-written view would shape half an edge.
        synchronized (recording) {
          for (String line : recording) {
            lines.append(line).append('\n');
          }
        }
        respond(
            exchange,
            200,
            "text/plain; charset=utf-8",
            lines.toString().getBytes(StandardCharsets.UTF_8),
            Map.of(),
            true);
      }
      default -> respond(exchange, 404, "text/plain", new byte[0], Map.of(), false);
    }
  }

  private static void respond(
      HttpExchange exchange,
      int status,
      String contentType,
      byte[] body,
      Map<String, String> headers,
      boolean withBody)
      throws java.io.IOException {
    if (contentType != null) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
    }
    headers.forEach((header, value) -> exchange.getResponseHeaders().add(header, value));
    if (!withBody || body.length == 0) {
      // -1 is "no body at all", which is what a HEAD and an empty control answer both want.
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  // --- the client half ---------------------------------------------------------------------------

  private void control(String command, Map<String, String> headers, byte[] body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(request::header);
    try {
      int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status != 200) {
        throw new IllegalStateException(name + " control " + command + " answered " + status);
      }
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  private String controlText(String command) {
    try {
      return http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command)).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  /**
   * A header value carrying an arbitrary path or an ETag. HTTP header values are ISO-8859-1 and
   * reject a newline outright, so the control plane percent-encodes them rather than trusting that
   * a package name never grows a character a header cannot hold.
   */
  private static String encodeHeader(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String decodeHeader(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  /** One registered route: what this registry answers at a path. */
  private record Answer(
      int status, String contentType, byte[] bytes, Map<String, String> headers) {}
}
