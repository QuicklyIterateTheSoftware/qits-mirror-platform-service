package eu.wohlben.qits.mirror.stories.support;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>The whole capture wiring of this catalogue, in one call</b> — so a story class's {@code
 * @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>This service is a <b>cache</b>, which is the only kind of subject whose diagram has to be
 * complete on <em>both</em> sides to say anything at all. "Served from cache" is not a claim about a
 * response; it is a claim about a request that was never made, and a diagram drawn from the client's
 * end alone cannot tell a hit from a miss. So there are four feeds:
 *
 * <ul>
 *   <li>{@link NetworkTaps#restAssured} — the shipped incoming tap. Every request a story sends
 *       becomes {@code <actor> -> qits-platform-mirror}, labelled {@code METHOD <scrubbed path> ->
 *       <status>} with the status this service really answered.
 *   <li>Three {@link NetworkCapture#source} registrations, one per {@link RecordingUpstream} — the
 *       outgoing half. Each is <b>cumulative</b>: the supplier hands over the whole recording every
 *       time it is asked and the framework remembers how much of it earlier stories consumed, so
 *       each upstream fetch is attributed to exactly one story.
 * </ul>
 *
 * <p>{@link AccessLogTap} is a fifth feed and is deliberately <b>not</b> installed here: it is armed
 * by the one story class whose client is a real CLI, and installing it for everybody would draw
 * every RestAssured edge a second time. Read that class's comment before touching either tap.
 *
 * <h2>Story order IS load-bearing here, and this is why</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so traffic recorded before a drain lands in
 * whichever story drains <b>first</b>. That is not a hazard to be tolerated in this catalogue — it
 * is the mechanism that makes the central claim assertable. A warm read produces <em>no</em>
 * upstream edge by construction, so the story that says "upstream was never dialled" is proved by
 * the cursor finding nothing new, and it can only find nothing new if the cold story that dialled
 * already drained. Every class whose stories share a cache therefore pins {@code
 * @TestMethodOrder(MethodOrderer.OrderAnnotation.class)} and says so in its javadoc.
 *
 * <p>Across classes the same discipline is kept by <b>namespacing</b> instead of by ordering: every
 * story owns its own package names, coordinates and image names, and no story counts the whole
 * recording. So the class order the framework picks cannot make one story's assertion depend on
 * another's leftovers.
 *
 * <h2>The far side records what was ANSWERED, which is the half a path cannot supply</h2>
 *
 * <p>{@code "GET /never-published-here -> 404"} is evidence that upstream said no, not merely that
 * it was asked; {@code "GET /story-outage -> dropped"} is evidence that a registry was dialled and
 * was not there. Both are edges. What is <b>not</b> an edge, ever, is a cache hit — nothing left the
 * process, so nothing observed anything — which is exactly the shape the diagram should have, and
 * why {@code assertNoEdgesTo} on a warm story is the assertion this catalogue is built around.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Every call below is idempotent: {@link NetworkTaps#restAssured(String)} installs at most one
 * filter per service name (RestAssured's filter list <i>appends</i>), {@link NetworkCapture#source}
 * re-registering under an id replaces the supplier but keeps its cursor, and the normalizer is a
 * single JVM slot every class sets to the same function. So every story class may call {@link
 * #install()} from its own {@code @BeforeAll} without the diagram doubling an edge.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /** Install the incoming tap, claim the label-normalizer slot, and register the three far sides. */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    NetworkCapture.labelNormalizer(StoryTarget.NORMALIZER);
    farSide(StoryTarget.NPM_UPSTREAM);
    farSide(StoryTarget.MAVEN_UPSTREAM);
    farSide(StoryTarget.OCI_UPSTREAM);
  }

  /**
   * Register one registry's recording as this service's outgoing traffic.
   *
   * <p>The source id is the upstream's own name rather than a fixture alias, and so is the edge's
   * {@code to}: these are the addresses the shipped configuration really dials, so the diagram names
   * a deployment's dependency rather than a test's.
   *
   * <p>The kind is {@link NetworkEdge#HTTP} on both sides of this service, and that is deliberate.
   * It would be tempting to call the outbound half {@code package} — it <em>is</em> a package
   * download — but the incoming half of the very same exchange is already {@code http} when a story
   * drives the wire, and a diagram whose two arrows for one fetch carry different kinds reads as two
   * different things happening. {@code package} is reserved for the one place it says something the
   * transport does not: {@link AccessLogTap}, where the client really is a package manager doing a
   * package manager's job.
   */
  private static void farSide(String upstream) {
    NetworkCapture.source(
        upstream,
        () ->
            RecordingUpstream.attach(upstream).recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(StoryTarget.SERVICE, upstream, request.label()))
                .toList());
  }
}
