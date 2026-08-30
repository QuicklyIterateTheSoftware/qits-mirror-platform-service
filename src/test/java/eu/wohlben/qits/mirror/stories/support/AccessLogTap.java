package eu.wohlben.qits.mirror.stories.support;

import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>incoming</b> tap for the stories whose client is a real command-line tool: the launched
 * process' own access log, read back as {@link NetworkCapture} edges.
 *
 * <p>{@link eu.wohlben.qits.userflows.NetworkTaps#restAssured} cannot serve those stories. The
 * subject is a real external program — {@code npm install} — talking to the packaged process over a
 * socket this JVM never touches, so nothing in the test process is on that path and the only place
 * the traffic exists is the server's own record of it. {@code quarkus.http.access-log.*} is that
 * record and {@link #configOverrides()} is the whole configuration.
 *
 * <h2>ARMED, because this catalogue has TWO incoming taps and one wire</h2>
 *
 * <p>This is the difference from the sibling repositories' copy of this idea, and it is worth
 * spelling out because getting it wrong is silent. Every RestAssured story here already observes its
 * own requests through the shipped tap — and the launched process writes an access-log line for
 * those requests too. A source that simply returned every line would therefore draw <b>every
 * RestAssured edge twice</b>: once as {@code http} from the filter and once as {@code package} from
 * the log, with two different actors, and every presence assertion in the catalogue would still pass
 * while every {@code assertEdgeCount} silently doubled.
 *
 * <p>So the source is <b>armed</b>. A line logged while no story holds the arm is consumed and
 * dropped, never emitted. Exactly one story class arms it, around exactly the calls a CLI makes.
 *
 * <p>The emitted list therefore only ever <b>grows</b> — dropped lines leave no hole in it. That is
 * a requirement rather than a nicety: {@link NetworkCapture#source} attributes a cumulative
 * recording with an index into the returned list, so a list that shrank or renumbered between drains
 * would hand one story another's traffic. Nothing here re-floors, resets or clears.
 *
 * <h2>Attribution: one CLI actor and one kind per story</h2>
 *
 * <p>The actor and the kind are read at <b>drain</b> time, which means a story gets one initiator
 * and one kind for every line it drains. Every story that arms this tap is one tool doing one job,
 * so that is exactly right — but it is a real limitation, and a story that ever drove two different
 * tools would need its edges telling apart another way.
 *
 * <p>{@link #arm} sets both halves together on purpose: {@link NetworkCapture} resets the actor at
 * every story border but nothing can reset a kind this class invented, so a story that set only the
 * actor would silently inherit the previous story's kind.
 *
 * <h2>The one thing to re-check</h2>
 *
 * <p>{@link #disarm()} belongs in the arming class's {@code @AfterAll} and <b>not</b> at the end of
 * a story body: the framework drains a story's edges <em>after</em> the body returns, so disarming
 * inside the body would drop the story's own traffic on the floor. Between two stories of one class
 * nothing else runs, so holding the arm across the pair costs nothing.
 */
public final class AccessLogTap {

  /** The {@code to} of every edge this tap observes — the launched process, as a diagram names it. */
  public static final String SERVICE = StoryTarget.SERVICE;

  /** One registration per JVM; re-registering under this id would keep the cursor anyway. */
  private static final String SOURCE_ID = "access-log";

  /**
   * The file name halves. Quarkus resolves the access log as {@code
   * <log-directory>/<base-file-name><log-suffix>}, and {@code rotate=false} keeps it at that one
   * name for the life of the build — a rotated file would leave the tail of a run in a sibling this
   * class never reads.
   */
  private static final String BASE_FILE_NAME = "story-access";

  private static final String LOG_SUFFIX = ".log";

  /**
   * How long {@link #awaitLogged} waits for a line to reach disk. The receiver writes on its own
   * executor and flushes per batch, so the gap between a tool's response and the line existing is
   * milliseconds — this is a ceiling, not a budget.
   */
  private static final Duration FLUSH_PATIENCE = Duration.ofSeconds(10);

  private static final long POLL_MILLIS = 25;

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** Whether lines arriving now belong to a story. See the class comment. */
  private static volatile boolean armed;

  /** How many complete lines this tap has already decided about — emitted or dropped. */
  private static int consumedLines;

  /** Everything ever emitted, in order. Only grows; the framework's cursor indexes it. */
  private static final List<NetworkEdge> EMITTED = new ArrayList<>();

  /** The story-scoped edge kind, read at drain time exactly as the actor is. */
  private static volatile String kind = NetworkEdge.PACKAGE;

  private AccessLogTap() {}

  // --- configuration -------------------------------------------------------------------------------

  /**
   * Where the launched process writes, as an <b>absolute</b> path. The process is started with a
   * working directory this suite does not choose, so a relative {@code log-directory} would put the
   * file somewhere nothing here could find; and it sits under {@code target/} so a {@code clean}
   * takes it.
   */
  public static Path logDirectory() {
    return Path.of(System.getProperty("user.dir"), "target", "userflows-it", "access-log")
        .toAbsolutePath();
  }

  /** The single file {@link #configOverrides()} configures and this class reads. */
  public static Path logFile() {
    return logDirectory().resolve(BASE_FILE_NAME + LOG_SUFFIX);
  }

  /**
   * The access-log block the launched process needs. Every key is <b>runtime</b> configuration, so
   * it reaches an already-built artifact as a {@code -D} flag and nothing re-augments.
   *
   * <p>{@code %m %U %s} — method, requested URL, response status. {@code %U} is {@code
   * HttpServerRequest.uri()}, so it carries a query string as well as the path; nothing this service
   * serves is addressed by query, so nothing is gained or lost by that, and {@code %R} is the
   * path-only spelling if it ever matters.
   *
   * <p>The file is <b>truncated at every build</b>, which the sibling repositories' copy of this
   * class deliberately does not do — there the log is append-only and a floor bounds what a story
   * can see. Here the file is this catalogue's alone and a stale tail from an earlier build would be
   * traffic no story in <em>this</em> run made, so deleting it is both simpler and stricter than a
   * floor. It also makes {@link #consumedLines} start from zero for the same reason a fresh cursor
   * does.
   */
  public static Map<String, String> configOverrides() {
    try {
      Files.createDirectories(logDirectory());
      Files.deleteIfExists(logFile());
    } catch (IOException unwritable) {
      throw new IllegalStateException("cannot prepare " + logDirectory(), unwritable);
    }
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("quarkus.http.access-log.enabled", "true");
    overrides.put("quarkus.http.access-log.log-to-file", "true");
    overrides.put("quarkus.http.access-log.pattern", "%m %U %s");
    overrides.put("quarkus.http.access-log.log-directory", logDirectory().toString());
    overrides.put("quarkus.http.access-log.base-file-name", BASE_FILE_NAME);
    overrides.put("quarkus.http.access-log.log-suffix", LOG_SUFFIX);
    overrides.put("quarkus.http.access-log.rotate", "false");
    return overrides;
  }

  // --- what a story calls ---------------------------------------------------------------------------

  /**
   * Take the arm: name the initiator and the kind of traffic this story's tool is about to make, and
   * register the source if this is the first story to do so.
   *
   * <p>Called at the <b>start</b> of a story body, before its first command. Both halves travel
   * together because both are read at drain time and only one of them is reset for you.
   */
  public static void arm(String actor, String edgeKind) {
    synchronized (LOCK) {
      if (!registered) {
        // Everything already in the file belongs to a story that did not arm — consume it now so it
        // can never be emitted, and register the supplier the framework reads at every drain.
        consumedLines = readLines().size();
        NetworkCapture.source(SOURCE_ID, AccessLogTap::edges);
        registered = true;
      }
      armed = true;
    }
    NetworkCapture.actor(actor);
    kind = edgeKind;
  }

  /**
   * Give the arm back. Belongs in the arming class's {@code @AfterAll} — see the class comment for
   * why it must not be called from inside a story body.
   */
  public static void disarm() {
    armed = false;
  }

  /**
   * Wait, briefly and without asserting anything, for a line containing {@code fragment} to reach
   * the log file.
   *
   * <p>The receiver writes off the request thread, so a tool's response can be back before the line
   * is on disk — and a line that lands after the story's drain is a line in the <i>next</i> story's
   * diagram, or in no story's at all once the arm is given back. A story therefore calls this once
   * its last interesting request has answered.
   *
   * <p>Deliberately silent on timeout: this is a latency hedge, not a proof. The proof is the {@code
   * assertEdge} in the class's {@code @AfterAll}, and a failure there names the missing edge, which
   * a timeout here would only obscure.
   */
  public static void awaitLogged(String fragment) {
    long deadline = System.nanoTime() + FLUSH_PATIENCE.toNanos();
    while (true) {
      for (String line : readLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // --- the source -------------------------------------------------------------------------------------

  /**
   * The whole emitted recording, every time — the contract {@link NetworkCapture#source} states.
   * Lines logged while the tap is not armed are consumed here and dropped, which is what keeps the
   * RestAssured stories' traffic out of a diagram that already carries it from the shipped tap.
   */
  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      List<String> lines = readLines();
      String actor = NetworkCapture.actor();
      String edgeKind = kind;
      for (int index = consumedLines; index < lines.size(); index++) {
        NetworkEdge edge = armed ? edgeOf(lines.get(index), actor, edgeKind) : null;
        if (edge != null) {
          EMITTED.add(edge);
        }
      }
      consumedLines = lines.size();
      return List.copyOf(EMITTED);
    }
  }

  /** One access-log line as an edge, or {@code null} for a line that describes no request. */
  private static NetworkEdge edgeOf(String line, String actor, String edgeKind) {
    // "%m %U %s" — three fields, no quoting, and a URI can carry no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3) {
      return null;
    }
    String method = fields[0];
    String uri = fields[1];
    String status = fields[2];
    // An attribute the handler could not resolve is written as "-"; such a line describes no
    // request anybody made and is not an edge.
    if (!uri.startsWith("/") || !status.chars().allMatch(Character::isDigit)) {
      return null;
    }
    // The probe root is /mirror/q here, so the check is on the segment rather than a prefix —
    // the same rule the shipped RestAssured tap applies.
    if (uri.contains("/q/")) {
      return null;
    }
    return new NetworkEdge(
        edgeKind, actor, SERVICE, method + " " + Labels.scrub(uri) + " -> " + status);
  }

  /**
   * The log file's complete lines. A missing file is an empty recording rather than a failure — the
   * catalogue must stay green on a machine that skipped every CLI story — and an <b>unterminated
   * tail is dropped</b>, because the writer is appending while this reads and half a line would
   * shape half an edge. The next drain sees it whole.
   */
  private static List<String> readLines() {
    Path file = logFile();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }
}
