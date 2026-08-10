package eu.wohlben.qits.mirror;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Startup gate that self-seeds the cache roots, so a fresh deployment can be pulled through out of
 * the box. Mirrors {@code ArtifactsStartupSeed}.
 *
 * <p>Runs in {@link LaunchMode#NORMAL} and {@code DEVELOPMENT} (so {@code quarkus:dev} is usable
 * immediately) but never under {@code TEST}: a suite that started with rows it did not create would
 * be testing the seeder in every class instead of once. {@code MirrorSeedTest} calls the seeder
 * directly, which is the honest place to prove it.
 *
 * <p>A failure is <b>logged and swallowed</b>, not fatal. The three OCI namespaces come from the V1
 * prefill and are already there; what this adds is two rows, and an instance serving the mirror
 * namespaces is worth more than an instance that refused to boot over a seed it will retry next
 * time. The ensure is trivial and local, so it runs inline on the startup thread.
 */
@ApplicationScoped
public class MirrorStartupSeed {

  private static final Logger LOG = Logger.getLogger(MirrorStartupSeed.class);

  @Inject MirrorRepositorySeeder seeder;

  @ConfigProperty(name = "qits.mirror.startup-seed.enabled", defaultValue = "true")
  boolean enabled;

  void onStart(@Observes StartupEvent event) {
    if (!shouldSeed(LaunchMode.current(), enabled)) {
      return;
    }
    try {
      seeder.ensureDefaults();
    } catch (RuntimeException e) {
      LOG.error("Mirror cache-root seed failed — instance is usable; retried next boot.", e);
    }
  }

  /** Packaged and dev launches only, and only when enabled — the test suites never self-seed. */
  static boolean shouldSeed(LaunchMode mode, boolean enabled) {
    return enabled && (mode == LaunchMode.NORMAL || mode == LaunchMode.DEVELOPMENT);
  }
}
