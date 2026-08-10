package eu.wohlben.qits.mirror.gc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine: delete everything unaccessed past the window, keep everything else.
 *
 * <p>It is short because a cache has nothing to protect structurally. Every byte this service holds
 * came from upstream and can be fetched again, so the only question worth asking is whether anything
 * still uses it. Being wrong costs one re-download; being wrong in the other direction costs disk
 * forever.
 *
 * <p><b>Unaccessed means the effective access time is older than the window</b>, and the effective
 * access time is {@code max(created/updated/fetched, accessed_at)} — creation counts as a first
 * access, which is what keeps a tag cached ten minutes ago from reading as never-read. The evictor
 * computes it ({@link CachedIdentity#lastAccessAt()}); this engine only compares it.
 *
 * <p><b>No release rule lives here, deliberately.</b> A cache's content has no releases of
 * <em>ours</em> to protect: a mirrored {@code jdk-25} is upstream's release, and keeping it forever
 * because upstream calls it a release is how a mirror never shrinks. Version protection is
 * qits-artifacts' business, earned by own-ness, and this service holds nothing of its own.
 *
 * <p><b>No pin rule either.</b> qits-deployments pins application image shas and qits-ci pins daemon
 * versions; both name content of the platform's <em>own</em> registries, which live in qits-artifacts
 * and never in this store. Reading pins here would make a cache sweep fail-closed on another
 * service's availability to protect content that service does not hold.
 *
 * <p>Stateless and not a bean: something that binds a configured window to a {@link CacheEvictor}
 * constructs one. Deletion is the evictor's {@link CacheEvictor#delete}, because eviction removes
 * exactly the identities this plan condemns and adds nothing of its own.
 */
public final class CacheEviction {

  /** The rule sentence a report echoes for one cache type. */
  public static String rule(Duration window) {
    return "cache: delete every identity unaccessed for longer than "
        + iso(window)
        + ". Creation counts as the first access, so nothing is eligible before the window has"
        + " passed since it was cached.";
  }

  /** The sentence on a kept line. */
  public static String keptAccessed(Duration window) {
    return "accessed inside the " + iso(window) + " window";
  }

  /** The sentence on a condemned line. */
  public static String deadUnaccessed(Duration window) {
    return "cached content unaccessed for longer than " + iso(window);
  }

  /**
   * Reads the evictor's identities and says what would die. Deletes nothing.
   *
   * @param evictor the type's own facts — what exists, and when each was last touched
   * @param window the configured eviction window for that type
   * @param now the run's clock, passed in rather than read here so a plan and its receipt judge
   *     every identity against one instant
   */
  public EvictionPlan plan(CacheEvictor evictor, Duration window, Instant now) {
    Instant cut = now.minus(window);
    List<JudgedIdentity> dead = new ArrayList<>();
    List<JudgedIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();

    for (CachedIdentity candidate : evictor.enumerate()) {
      if (candidate.unaccessedSince(cut)) {
        dead.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), deadUnaccessed(window)));
        released.addAll(candidate.blobs());
      } else {
        kept.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), keptAccessed(window)));
        retained.addAll(candidate.blobs());
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty()
        ? EvictionPlan.nothingDies(kept, retained)
        : new EvictionPlan(dead, kept, released, retained);
  }

  /**
   * ISO-8601, in the spelling the configuration uses.
   *
   * <p>{@code Duration.toString()} normalises a whole number of days to hours ({@code P30D} comes
   * back as {@code PT720H}), and a report that answered in hours for a window somebody wrote in days
   * reads as a different number than the one they set.
   */
  static String iso(Duration window) {
    return window.toDaysPart() > 0 && window.minusDays(window.toDaysPart()).isZero()
        ? "P" + window.toDaysPart() + "D"
        : window.toString();
  }

  static final Comparator<JudgedIdentity> BY_IDENTITY =
      Comparator.comparing(JudgedIdentity::repository).thenComparing(JudgedIdentity::identity);
}
