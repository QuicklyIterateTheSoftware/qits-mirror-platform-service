package eu.wohlben.qits.mirror.stories.support;

import eu.wohlben.qits.mirror.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-platform-mirror for the whole story catalogue</b>, and every seam a story
 * moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * mirrors — two boots, two databases, two caches. For <em>this</em> service that is the sharpest
 * possible form of the problem: what a cache holds is the entire subject of the catalogue, and a
 * warm-read story reading a cache some other launch filled would prove nothing at all. Every story
 * class names this one, {@code PullThroughBootstrapIT} included; it is a story class like the
 * others and it happens to be the oldest.
 *
 * <h2>Why the PACKAGED artifact, and not a {@code @QuarkusTest}</h2>
 *
 * <p>Four things these stories are about exist only in a {@code NORMAL} launch, and no
 * {@code @QuarkusTest} in this repository can reach any of them:
 *
 * <ul>
 *   <li><b>The cache roots exist because the process booted.</b> {@code MirrorStartupSeed} runs in
 *       {@code NORMAL} and {@code DEVELOPMENT} and never under {@code TEST} — deliberately, so a
 *       suite is not testing the seeder in every class — so "a fresh deployment can be pulled
 *       through out of the box" is a claim no {@code @QuarkusTest} here is allowed to make.
 *   <li><b>The datasource expression resolves the generic resource contract.</b> The shipped config
 *       reads {@code ${QITS_RESOURCE_DB_URL}} and friends and has no defaults on purpose; the suite
 *       replaces the whole triple from {@code EmbeddedPgConfigSource} at ordinal 500 and never
 *       exercises the expression. The launched process is handed the three variables a deployment
 *       injects and nothing else, so Flyway either migrates or the boot dies naming the variable.
 *   <li><b>Absolute URLs are built from the request.</b> An npm packument's {@code dist.tarball} is
 *       not a config key — it can only be right or wrong against a real client on a real port, and
 *       {@code stories/npm} settles it with the real npm CLI following it.
 *   <li><b>The four surfaces are one process on one port.</b> A JAX-RS surface, three raw Vert.x
 *       route stacks from library jars and the non-application root are five registrations, and
 *       packaging is where a collision between them would show.
 * </ul>
 *
 * <h2>Every key here is a RUNTIME key</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on an artifact that was
 * already built, so a build-time key would be silently ignored and the catalogue would prove
 * something other than what it says. Everything that makes this service what it is stays exactly as
 * it ships: {@code quarkus.rest.path}, the non-application root, the arc exclusions that veto the
 * hosted profiles, the Flyway lineage, {@code qits.artifacts.blobs-datasource=mirror}, the body
 * ceiling and the eviction windows.
 *
 * <h2>What is moved, and why each one</h2>
 *
 * <ul>
 *   <li><b>The database triple</b> — the platform's generic resource contract, which
 *       qits-platform-deployments injects and the shipped datasource expression reads. It is the
 *       same embedded postgres the surefire suite spawns, under a database of this catalogue's own
 *       ({@code mirror_userflows_it}), so the launched process and the suite can never mean the same
 *       schema. Its url travels through a system property rather than a static field, because a test
 *       profile is instantiated in more than one classloader and a field written by one copy is not
 *       the field another reads — while the JVM has exactly one property table.
 *   <li><b>The three upstreams</b> — the seams the whole catalogue is about, each pointed at a
 *       {@link RecordingUpstream}. They are <b>real public addresses</b> in the shipped config
 *       (npmjs, Central, and whatever domain an {@code oci_mirror_upstream} row names), so leaving
 *       any of them at its shipped value would let a mistyped path reach the internet from a step
 *       container, where it would pass or fail for reasons unrelated to this code. Note the OCI key
 *       is an <em>override</em> that replaces the derivation for <b>every</b> registered upstream at
 *       once, which is exactly why it exists.
 *   <li><b>{@link #PACKUMENT_TTL}</b> — the one timing seam, and the only one. See below.
 *   <li><b>The access log</b> — {@link AccessLogTap#configOverrides()}. It is the only way a story
 *       driving a <em>real external tool</em> has a diagram at all: {@code npm} talks to the
 *       launched process over a socket this JVM never touches, so the server's own record of the
 *       request is the only place that traffic exists.
 *   <li><b>The eviction kill switch</b> — the service's own {@code qits.mirror.eviction.enabled},
 *       rather than a scheduler flag ({@code quarkus.scheduler.enabled} is build-time fixed and a
 *       {@code -D} on a built artifact would be read, accepted and ignored). The sweep is the only
 *       background work this process has and its only trigger is the clock at 03:20; a run that
 *       happened to start then would be deleting rows underneath the stories.
 *   <li><b>OTel</b> — dark outside a deployment, like {@code %dev}/{@code %test}. A neutralisation
 *       and not tidiness: the shipped config points the exporter at {@code
 *       http://qits-observability:8080}, a name that resolves on qits-net and nowhere else, and an
 *       exporter flushes on a schedule of its own, on its own thread, so its batches would draw
 *       arrows into whichever story happened to be open — a {@code networkHash} that never settles.
 *       So <b>no story here covers this service's self-export</b>, and none claims its absence
 *       either: an {@code assertNoEdgesTo} over an exporter this profile switched off would be a
 *       claim about the profile rather than about the service.
 * </ul>
 *
 * <h2>The packument TTL is a shared seam, and it couples two story classes</h2>
 *
 * <p>{@code qits.artifacts.npm.proxy.packument-ttl} ships at {@code PT5M}. A packument is the one
 * npm document that mutates, so it is the one thing this cache re-asks upstream about, and two
 * stories in this catalogue stand on opposite sides of that timer:
 *
 * <ul>
 *   <li>{@code PullThroughBootstrapIT} and {@code stories/npm} both read a package <b>twice</b> and
 *       claim the second read never left the process. That claim is true only <em>inside</em> the
 *       TTL, so their two reads must be close together — which they are: the two stories of each
 *       pair run back to back in one class, seconds apart.
 *   <li>{@code stories/outage} claims the opposite half — that <b>past</b> the TTL, an unreachable
 *       registry yields the stale document rather than a refusal, which is the whole reason CI keeps
 *       installing through an npmjs outage. It has to wait the TTL out, and at {@code PT5M} it would
 *       spend five minutes doing so.
 * </ul>
 *
 * <p>{@link #PACKUMENT_TTL} is the one number that serves both: <b>ten times</b> the gap the warm
 * pairs need, and short enough that waiting it out is a bounded poll rather than a coffee break.
 * Moving it down risks a warm-read story going red on a loaded machine; moving it up costs the
 * outage story the same seconds in wall clock. It is a seam and not an accident, which is why it is
 * a named constant both sides read rather than a literal in a map.
 */
public class StoryProfile implements QuarkusTestProfile {

  /** Where the url is parked for whichever copy of this class is asked second. */
  private static final String URL_PROPERTY = "qits.test.userflows-it.db-url";

  /** This catalogue's own database on the one embedded postgres. */
  private static final String DATABASE = "mirror_userflows_it";

  /** The shared timing seam. See the class comment — both sides of it read this constant. */
  public static final Duration PACKUMENT_TTL = Duration.ofSeconds(30);

  @Override
  public Map<String, String> getConfigOverrides() {
    // LinkedHashMap rather than Map.of: the order is the order this file explains them in, and a
    // reader diffing a launch command should find them in it.
    Map<String, String> overrides = new LinkedHashMap<>();

    // The platform's generic resource contract, exactly as a deployment fills it.
    overrides.put("QITS_RESOURCE_DB_URL", databaseUrl());
    overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
    overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);

    // The three registries this service is a cache OF. Started here rather than in a story class
    // because the launched process needs their addresses in its command line, which is built from
    // exactly this map — and `named` is start-or-attach, so the second classloader to arrive gets
    // the first one's ports.
    overrides.put(
        "qits.artifacts.npm.proxy.upstream",
        RecordingUpstream.named(StoryTarget.NPM_UPSTREAM).baseUrl());
    overrides.put(
        "qits.artifacts.maven.proxy.upstream",
        RecordingUpstream.named(StoryTarget.MAVEN_UPSTREAM).baseUrl());
    overrides.put(
        "qits.artifacts.oci.mirror.endpoint-override",
        RecordingUpstream.named(StoryTarget.OCI_UPSTREAM).baseUrl());

    // The one timing seam. ISO-8601, because that is how a Duration config value is spelled.
    overrides.put("qits.artifacts.npm.proxy.packument-ttl", PACKUMENT_TTL.toString());

    // The only way a story driving a real CLI has a diagram at all.
    overrides.putAll(AccessLogTap.configOverrides());

    // Nothing may delete rows underneath a story about what is cached.
    overrides.put("qits.mirror.eviction.enabled", "false");

    // Dark outside a deployment, and the only dial-out this process has besides its three upstreams
    // and its datasource. See the class comment for why it is a neutralisation, not tidiness.
    overrides.put("quarkus.otel.sdk.disabled", "true");

    return Map.copyOf(overrides);
  }

  private static synchronized String databaseUrl() {
    String recorded = System.getProperty(URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    // localhost resolves for the launched process too — it is a child of this JVM on this host.
    String url = EmbeddedPg.url(DATABASE);
    System.setProperty(URL_PROPERTY, url);
    return url;
  }
}
