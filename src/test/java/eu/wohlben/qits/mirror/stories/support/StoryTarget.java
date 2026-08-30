package eu.wohlben.qits.mirror.stories.support;

import eu.wohlben.qits.mirror.MirrorRepositorySeeder;

/**
 * The one launched qits-platform-mirror, as every story in this catalogue addresses it and as every
 * diagram names it.
 *
 * <h2>Four surfaces on one port, and three of them are not JAX-RS</h2>
 *
 * <p>That is this service's whole shape, and it is why the constants below come from four different
 * places rather than from one config key:
 *
 * <ul>
 *   <li><b>{@link #NPM_BASE}, {@link #MAVEN_BASE} and {@link #OCI_BASE}</b> are raw Vert.x routes
 *       whose prefixes are <em>literals in the qits-registries jars</em> ({@code NpmPaths.BASE},
 *       {@code MavenPaths.BASE}, {@code RegistryPaths}' {@code /v2}). No configuration moves them,
 *       and no test in this repository would notice if one drifted — which is exactly why they are
 *       spelled once, here.
 *   <li><b>{@link #EXPLORER}</b> is the only JAX-RS surface: {@code quarkus.rest.path=/mirror/api},
 *       two read-only listings and a drill-down.
 *   <li><b>{@link #READY}</b> sits under {@code quarkus.http.non-application-root-path=/mirror/q}.
 * </ul>
 *
 * <h2>The shipped tap's default skip was checked against this service</h2>
 *
 * <p>{@link eu.wohlben.qits.userflows.NetworkTaps#restAssured(String)} skips any path carrying a
 * {@code /q/} <b>segment</b> rather than a leading one, which is exactly this service's case:
 * {@code /mirror/q} is nested under the application root. Nothing else on this surface can contain
 * one — the three protocol roots are fixed literals, a repository name is
 * {@code [a-z0-9][a-z0-9._-]*} and an npm package name may not contain a slash — so no story class
 * overrides the predicate.
 *
 * <h2>Labels: what is generated here, and what only looks it</h2>
 *
 * <p>{@link eu.wohlben.qits.userflows.Labels} rewrites a whole path segment it can tell was
 * generated. On this surface that is exactly one thing and it is the right one: an <b>OCI digest</b>
 * ({@code sha256:…}) becomes {@code {digest}}, which is what keeps a manifest-by-digest pull from
 * moving a story's {@code networkHash} on every run.
 *
 * <p>Everything else a story spells here is <b>authored</b> and survives untouched, deliberately:
 * a package name, a dotted version, a maven coordinate path, a tag. That is the point of the
 * diagram — {@code GET /artifacts/npm/npmjs/story-cold-install -> 200} says which package a build
 * asked for, and a scrubber that erased it would leave an arrow saying nothing. There is one shape
 * to be careful of and this catalogue avoids it by construction: a <b>bare numeric</b> path segment
 * is rewritten to {@code {id}}, so no fixture here is ever named after a number alone.
 *
 * <p>Which is why {@link #NORMALIZER} is the identity — and not vacuously. Claiming the framework's
 * single normalizer slot from one place ({@link StoryNetwork#install()}) is what makes "the default
 * scrubbing is the whole of what this service needs" a decision on the record rather than an
 * omission, and {@link #served} routes an assertion's expected label through the very same function
 * so the two sides move together if it is ever given a job.
 */
public final class StoryTarget {

  private StoryTarget() {}

  /** How every diagram in this catalogue names the launched process, on both sides of an edge. */
  public static final String SERVICE = "qits-platform-mirror";

  /**
   * The store, for the {@code declare}d edge. This service's entire state — cached metadata rows
   * <em>and</em> the blob bytes themselves, since {@code qits.artifacts.blobs-datasource=mirror} —
   * is one PostgreSQL database, which is what makes the container stateless and a cache re-warmable.
   * No tap can see a JDBC connection, so a story that reads or writes rows declares it.
   */
  public static final String STORE = "postgresql";

  // --- the three upstreams this service is a cache OF ---------------------------------------------

  /**
   * The npm registry, named as {@code qits.artifacts.npm.proxy.upstream} ships pointing at it — so
   * the diagram names the address a deployment really dials rather than a fixture's alias.
   */
  public static final String NPM_UPSTREAM = "registry.npmjs.org";

  /** Maven Central, as {@code qits.artifacts.maven.proxy.upstream} ships pointing at it. */
  public static final String MAVEN_UPSTREAM = "repo1.maven.org";

  /**
   * The container registry the {@code quay} namespace fronts — a <b>row</b> in {@code
   * oci_mirror_upstream}, prefilled by V1, rather than a config key. {@code
   * qits.artifacts.oci.mirror.endpoint-override} is what points every registered upstream at the
   * stand-in, which is the one switch that stops a cold miss dialling a public registry.
   */
  public static final String OCI_UPSTREAM = "quay.io";

  // --- the wire roots ------------------------------------------------------------------------------

  /** {@code npmjs} — the seeded npm cache root, as {@link MirrorRepositorySeeder} names it. */
  public static final String NPM_CACHE = MirrorRepositorySeeder.NPM_CACHE;

  /** {@code central} — the seeded maven cache root beside it. */
  public static final String MAVEN_CACHE = MirrorRepositorySeeder.MAVEN_CACHE;

  /** {@code quay} — an OCI namespace, prefilled by V1 together with the upstream row it fronts. */
  public static final String OCI_NAMESPACE = "quay";

  /** {@code /artifacts/npm/npmjs} — the npm registry base plus the cache root. */
  public static final String NPM_BASE = "/artifacts/npm/" + NPM_CACHE;

  /** {@code /artifacts/maven/central} — the maven repository base plus the cache root. */
  public static final String MAVEN_BASE = "/artifacts/maven/" + MAVEN_CACHE;

  /** {@code /v2/quay} — the Distribution API at the host root, plus the namespace segment. */
  public static final String OCI_BASE = "/v2/" + OCI_NAMESPACE;

  /** {@code /mirror/api/repositories} — the explorer's first listing. */
  public static final String EXPLORER = "/mirror/api/repositories";

  /** {@code /mirror/api/upstreams} — which container registries this service mirrors. */
  public static final String UPSTREAMS = "/mirror/api/upstreams";

  /** Readiness, under the non-application root — the one path the shipped tap skips. */
  public static final String READY = "/mirror/q/health/ready";

  // --- labels ---------------------------------------------------------------------------------------

  /**
   * The catalogue's label normalizer, composed over the default scrubber by {@link
   * StoryNetwork#install()}. The identity, for the reason the class comment gives.
   */
  public static final java.util.function.UnaryOperator<String> NORMALIZER =
      java.util.function.UnaryOperator.identity();

  /**
   * The label an incoming request produces, run through the very same two functions an observation
   * is — so an assertion and an observation cannot disagree about what a generated segment became.
   */
  public static String served(String method, String path, int status) {
    return NORMALIZER.apply(
        eu.wohlben.qits.userflows.Labels.scrub(method + " " + path + " -> " + status));
  }

  /** The label an <b>outgoing</b> fetch produces, on the far side's own path grammar. */
  public static String fetched(String method, String path, String status) {
    return NORMALIZER.apply(
        eu.wohlben.qits.userflows.Labels.scrub(method + " " + path + " -> " + status));
  }
}
