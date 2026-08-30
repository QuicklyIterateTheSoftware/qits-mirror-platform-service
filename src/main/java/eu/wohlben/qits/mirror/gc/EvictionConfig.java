package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import java.util.Map;

/**
 * What the sweep does and how long a cached entry may sit unused before it is eligible:
 * {@code qits.mirror.eviction.*}.
 *
 * <p>The windows are keyed by the type's <b>wire name</b> — {@code npm-proxy}, {@code maven-proxy},
 * {@code oci-mirror} — because that is the spelling an operator reads in every report and every
 * repository listing, and a configuration key nobody can match to a report line is a knob nobody
 * turns.
 *
 * <p><b>A type with no window is an error, not a default.</b> {@link #requireWindow} throws rather
 * than assuming a number: a cache type nobody configured is a decision nobody took, and guessing a
 * window is guessing what may be deleted. Adding an evictor therefore means adding one line of
 * configuration, which {@code EvictionConfigTest} holds by looping over the registered evictors.
 */
@ConfigMapping(prefix = "qits.mirror.eviction")
public interface EvictionConfig {

  /**
   * Whether the scheduled sweep does anything at all.
   *
   * <p>The kill switch a deployment reaches for when something is wrong: false leaves the schedule
   * firing and the run logging that it is disabled, which is a line in the log rather than a silence
   * somebody has to infer.
   */
  boolean enabled();

  /**
   * Plan and report, delete nothing.
   *
   * <p>The mode a new deployment runs in until somebody has read a sweep's counts and agreed with
   * them. It is a property of the run rather than of a caller, because the only caller is a
   * schedule.
   */
  boolean dryRun();

  /**
   * When the sweep runs, as a cron expression.
   *
   * <p>Mapped here as well as read by {@code @Scheduled}, so the one key has one documented owner
   * and a deployment that mistypes it fails at boot rather than at three in the morning.
   */
  String cron();

  /** How long an identity may sit unaccessed before it is eligible, ISO-8601, per wire name. */
  Map<String, Duration> window();

  /**
   * One type's window, or a refusal naming the missing key.
   *
   * @param typeKey the stored key, e.g. {@code NPM_PROXY}
   * @throws IllegalStateException the type has no window configured
   */
  default Duration requireWindow(String typeKey) {
    String wireName = RepositoryTypeProfile.wireNameOf(typeKey);
    Duration window = window().get(wireName);
    if (window == null) {
      throw new IllegalStateException(
          "no eviction window configured for "
              + wireName
              + "; set qits.mirror.eviction.window."
              + wireName
              + " to an ISO-8601 duration such as P30D");
    }
    return window;
  }
}
