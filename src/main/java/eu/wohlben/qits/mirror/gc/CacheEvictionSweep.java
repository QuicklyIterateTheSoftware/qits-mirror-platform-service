package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.artifacts.control.BlobReclaim;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The run: plan every cache type, delete what each condemned, reclaim the blobs nothing reaches any
 * more. One schedule, one log line.
 *
 * <p><b>Nothing triggers this but the clock.</b> There is no route and no admin call — the whole
 * policy is "a cached entry unused for longer than its window is evicted", which is a decision the
 * configuration makes rather than one an operator takes per run. The admin surface is a separate
 * work package; when it lands, {@link #run()} is what it calls.
 *
 * <p><b>The order inside one run is the safety property.</b> Every type is planned against <b>one</b>
 * census and one clock, then every type deletes its own rows, then the blob sweep reconciles across
 * all of them. Planning a type after another type's deletions would judge two types against two
 * different stores, and reclaiming per type would unlink a layer a second type still names.
 *
 * <p><b>A type that fails is skipped whole and the rest of the run proceeds.</b> Its blobs are then
 * counted live by the reconciliation, so a broken type reclaims nothing rather than reclaiming
 * something nobody vouched for.
 *
 * <p><b>The plans handed to the blob sweep are the ones that were planned, not a recomputed
 * "applied" set.</b> An identity the grace window withheld keeps its rows, so the fresh pre-unlink
 * census still sees the blobs it names and the sweep counts them as still referenced. That is the
 * belt the withheld case rests on, and it is the same one that catches a pull arriving mid-run.
 */
@ApplicationScoped
public class CacheEvictionSweep {

  private static final Logger LOG = Logger.getLogger(CacheEvictionSweep.class);

  @Inject Instance<CacheEvictor> evictors;
  @Inject EvictionConfig config;
  @Inject MirrorBlobCensus census;
  @Inject MirrorBlobSweep sweep;
  @Inject BlobReclaim blobs;

  private final CacheEviction engine = new CacheEviction();

  /**
   * What one run did, per type and in total.
   *
   * @param startedAt the run's one clock — every type was judged against this instant
   * @param dryRun whether anything was deleted at all
   * @param types one line per registered evictor, in the order CDI found them
   * @param blobPlan what the reconciliation freed, or would free on a dry run
   * @param blobs the unlink loop's own counts; all zero on a dry run
   */
  public record SweepReport(
      Instant startedAt,
      boolean dryRun,
      List<TypeResult> types,
      MirrorBlobSweep.SweepPlan blobPlan,
      MirrorBlobSweep.SweepOutcome blobs) {

    public SweepReport {
      types = List.copyOf(types);
    }

    /** One type's line, by key. */
    public TypeResult type(String typeKey) {
      return types.stream()
          .filter(result -> result.typeKey().equals(typeKey))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("no such type in this run: " + typeKey));
    }
  }

  /**
   * One type's line.
   *
   * @param typeKey the stored type key
   * @param window the window it was judged against, or null when it failed before planning
   * @param condemned identities the plan condemned
   * @param kept identities it kept
   * @param deleted rows actually gone — zero on a dry run
   * @param withheldByGraceWindow identities left whole because a blob they release is still young
   * @param errors what could not be applied, each with its reason
   * @param failure why the type was skipped whole, or null
   * @param note the type's standing caption, or null
   */
  public record TypeResult(
      String typeKey,
      Duration window,
      int condemned,
      int kept,
      int deleted,
      int withheldByGraceWindow,
      List<String> errors,
      String failure,
      String note) {

    public TypeResult {
      errors = List.copyOf(errors);
    }
  }

  /**
   * The schedule. Off-hours and a cron rather than an interval: a run walks every cached row and
   * every manifest document, which is work worth doing when nothing is pulling.
   *
   * <p>{@code SKIP} on a concurrent execution, so a run that outlives its own schedule is never
   * joined by a second one planning against a store the first is deleting from.
   */
  @Scheduled(
      cron = "{qits.mirror.eviction.cron}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    if (!config.enabled()) {
      LOG.info("Cache eviction is disabled (qits.mirror.eviction.enabled=false); nothing swept.");
      return;
    }
    try {
      LOG.info(summary(run()));
    } catch (RuntimeException failed) {
      // A sweep is a background chore: a failure is a line in the log and a retry on the next
      // schedule, never a dead scheduler thread or a service that stops serving pulls.
      LOG.error("Cache eviction run failed; the next schedule retries.", failed);
    }
  }

  /**
   * Plans every type against one census and one clock, applies what is not a dry run, and reclaims.
   *
   * <p>{@code @ActivateRequestContext} because the persistence session is bound to it: a scheduled
   * method has no request of its own.
   */
  @ActivateRequestContext
  public SweepReport run() {
    Instant startedAt = Instant.now();
    MirrorBlobCensus.Census taken = census.take();

    Map<String, EvictionPlan> plans = new HashMap<>();
    List<TypeResult> results = new ArrayList<>();
    for (CacheEvictor evictor : evictors) {
      try {
        Duration window = config.requireWindow(evictor.typeKey());
        EvictionPlan plan = engine.plan(evictor, window, startedAt);
        plans.put(evictor.typeKey(), plan);
        results.add(
            new TypeResult(
                evictor.typeKey(),
                window,
                plan.dead().size(),
                plan.kept().size(),
                0,
                0,
                List.of(),
                null,
                evictor.note()));
      } catch (RuntimeException failed) {
        results.add(
            new TypeResult(
                evictor.typeKey(), null, 0, 0, 0, 0, List.of(), message(failed), evictor.note()));
      }
    }

    if (config.dryRun()) {
      return new SweepReport(
          startedAt,
          true,
          results,
          sweep.plan(taken, plans),
          MirrorBlobSweep.SweepOutcome.nothing());
    }

    CacheEvictor.GraceWindow grace = graceWindow();
    for (CacheEvictor evictor : evictors) {
      EvictionPlan plan = plans.get(evictor.typeKey());
      if (plan == null) {
        continue; // the type failed to plan; its blobs stay live for this run
      }
      try {
        apply(results, evictor.typeKey(), evictor.delete(plan, grace));
      } catch (RuntimeException failed) {
        // A type whose deletion blew up keeps its plan out of the reconciliation: its rows are in an
        // unknown state, and unlinking blobs against an unknown state is the one thing never worth
        // guessing at.
        plans.remove(evictor.typeKey());
        fail(results, evictor.typeKey(), message(failed));
      }
    }

    return new SweepReport(
        startedAt, false, results, sweep.plan(taken, plans), sweep.execute(taken, plans));
  }

  /** The one log line a run leaves behind. */
  static String summary(SweepReport report) {
    StringBuilder line = new StringBuilder(report.dryRun() ? "Cache eviction (dry run):" : "Cache eviction:");
    for (TypeResult type : report.types()) {
      line.append(' ').append(type.typeKey()).append('=');
      if (type.failure() != null) {
        line.append("failed(").append(type.failure()).append(')');
        continue;
      }
      line.append(type.condemned())
          .append(" condemned/")
          .append(type.deleted())
          .append(" deleted/")
          .append(type.kept())
          .append(" kept");
      if (type.withheldByGraceWindow() > 0) {
        line.append('/').append(type.withheldByGraceWindow()).append(" withheld");
      }
      if (!type.errors().isEmpty()) {
        line.append('/').append(type.errors().size()).append(" errors");
      }
    }
    if (report.dryRun()) {
      line.append(" blobs=")
          .append(report.blobPlan().blobCount())
          .append(" reclaimable (")
          .append(report.blobPlan().reclaimableBytes())
          .append(" bytes)");
      return line.toString();
    }
    line.append(" blobs=")
        .append(report.blobs().unlinked())
        .append(" unlinked (")
        .append(report.blobs().reclaimedBytes())
        .append(" bytes)");
    if (report.blobs().withheldByGraceWindow() > 0) {
      line.append(", ").append(report.blobs().withheldByGraceWindow()).append(" withheld");
    }
    if (report.blobs().stillReferenced() > 0) {
      line.append(", ").append(report.blobs().stillReferenced()).append(" still referenced");
    }
    if (report.blobs().alreadyGone() > 0) {
      line.append(", ").append(report.blobs().alreadyGone()).append(" already gone");
    }
    return line.toString();
  }

  /**
   * The grace window as an evictor asks it: is this blob's file younger than the store allows.
   *
   * <p>Read once per run against one instant, so every identity of every type is judged on the same
   * clock the unlink will use.
   */
  private CacheEvictor.GraceWindow graceWindow() {
    Instant graceStartsAt = Instant.now().minus(sweep.graceWindow());
    return blobId -> {
      Instant written = blobs.lastWrittenAt(blobId);
      return written != null && written.isAfter(graceStartsAt);
    };
  }

  private static void apply(
      List<TypeResult> results, String typeKey, CacheEvictor.Applied applied) {
    edit(
        results,
        typeKey,
        result ->
            new TypeResult(
                result.typeKey(),
                result.window(),
                result.condemned(),
                result.kept(),
                applied.deleted().size(),
                applied.withheldByGraceWindow().size(),
                applied.errors(),
                null,
                result.note()));
  }

  private static void fail(List<TypeResult> results, String typeKey, String failure) {
    edit(
        results,
        typeKey,
        result ->
            new TypeResult(
                result.typeKey(),
                result.window(),
                result.condemned(),
                result.kept(),
                result.deleted(),
                result.withheldByGraceWindow(),
                result.errors(),
                failure,
                result.note()));
  }

  private static void edit(
      List<TypeResult> results, String typeKey, java.util.function.UnaryOperator<TypeResult> edit) {
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i).typeKey().equals(typeKey)) {
        results.set(i, edit.apply(results.get(i)));
        return;
      }
    }
  }

  private static String message(RuntimeException failed) {
    String message = failed.getMessage();
    return failed.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
