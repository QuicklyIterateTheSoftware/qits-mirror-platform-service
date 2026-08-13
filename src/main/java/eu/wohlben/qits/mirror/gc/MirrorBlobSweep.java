package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.artifacts.control.BlobReclaim;
import eu.wohlben.qits.artifacts.control.BlobStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one mechanism that frees storage, and it carries no policy at all.
 *
 * <p>Evictors kill identities and free nothing. This reconciles what they did: a blob dies only when
 * <b>no type</b> reaches it any more, because the store dedupes globally across types and
 * repositories. That split is what makes "one evictor per type" safe by construction — an evictor
 * cannot free a blob another type still needs, because an evictor never frees anything.
 *
 * <p>{@link #plan} deletes nothing: it is what a dry run reports, and it must stay readable without
 * side effects. {@link #execute} is the unlink loop, and it is the <b>only</b> caller of {@link
 * BlobReclaim#delete} in this service.
 */
@ApplicationScoped
public class MirrorBlobSweep {

  @Inject BlobReclaim blobs;
  @Inject MirrorBlobCensus census;

  /**
   * What a run would free.
   *
   * @param blobCount how many blobs would be removed
   * @param reclaimableBytes what they occupy
   * @param withheldByGraceWindow blobs held back because they are younger than the window
   * @param withheldBytes what those occupy
   * @param blobIds the candidates themselves, in a stable order
   */
  public record SweepPlan(
      int blobCount,
      long reclaimableBytes,
      int withheldByGraceWindow,
      long withheldBytes,
      List<String> blobIds) {

    public SweepPlan {
      blobIds = List.copyOf(blobIds);
    }
  }

  /**
   * What a run actually did.
   *
   * @param unlinked blobs removed
   * @param reclaimedBytes what they occupied
   * @param withheldByGraceWindow candidates younger than the window
   * @param withheldBytes what those occupy
   * @param stillReferenced candidates something referenced again between planning and unlinking
   * @param alreadyGone candidates that were not there any more
   */
  public record SweepOutcome(
      int unlinked,
      long reclaimedBytes,
      int withheldByGraceWindow,
      long withheldBytes,
      int stillReferenced,
      int alreadyGone) {

    static SweepOutcome nothing() {
      return new SweepOutcome(0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Blobs no type reaches once every given plan is applied, with what they would free.
   *
   * <p>The reconciliation, in one line: a candidate is released by some plan and retained by none. A
   * type with no plan keeps its whole census set, which is what makes a partial run — one evictor
   * enabled, two not — as safe as a full one.
   *
   * @param census the store as read; a type absent from {@code plans} contributes its live set
   * @param plans the evictors' answers, at most one per type key
   */
  public SweepPlan plan(MirrorBlobCensus.Census census, Map<String, EvictionPlan> plans) {
    return reconcile(census, plans, true);
  }

  private SweepPlan reconcile(
      MirrorBlobCensus.Census census, Map<String, EvictionPlan> plans, boolean applyGraceWindow) {
    Set<String> released = new TreeSet<>();
    Set<String> live = new HashSet<>();
    // The union of both key sets, so a plan for a type the census listed no rows for still
    // contributes, and a type nobody planned still protects everything it reaches.
    Set<String> typeKeys = new HashSet<>(census.liveByType().keySet());
    typeKeys.addAll(plans.keySet());
    for (String typeKey : typeKeys) {
      EvictionPlan plan = plans.get(typeKey);
      if (plan == null) {
        live.addAll(census.live(typeKey).keySet());
      } else {
        released.addAll(plan.blobsReleased());
        live.addAll(plan.blobsRetained());
      }
    }

    Set<String> rowless = census.rowless();
    Instant graceStartsAt = Instant.now().minus(graceWindow());
    List<String> sweepable = new ArrayList<>();
    long reclaimable = 0;
    int withheld = 0;
    long withheldBytes = 0;
    for (String blobId : released) {
      if (live.contains(blobId)) {
        continue; // something that survives still names it — the whole point of reconciling
      }
      Long size = census.onDisk().get(blobId);
      if (size == null) {
        continue; // a row outliving its bytes frees nothing
      }
      // Unreachable by construction — a released blob was in its type's live set, so it had a row —
      // and checked anyway, because an evictor that invented a digest must not reach the unlink.
      if (rowless.contains(blobId)) {
        continue;
      }
      if (applyGraceWindow) {
        Instant written = blobs.lastWrittenAt(blobId);
        if (written != null && written.isAfter(graceStartsAt)) {
          withheld++;
          withheldBytes += size;
          continue;
        }
      }
      sweepable.add(blobId);
      reclaimable += size;
    }
    return new SweepPlan(sweepable.size(), reclaimable, withheld, withheldBytes, sweepable);
  }

  /**
   * Unlinks what the applied plans freed — the one loop in this service that deletes bytes.
   *
   * <p>Called after the evictors' row deletions, with the census the plans were computed from. The
   * safety order inside:
   *
   * <ol>
   *   <li><b>Candidates are structural</b>: released by some plan, retained by none, stored, and
   *       rowed in the planning census.
   *   <li><b>Grace-withheld candidates never reach the unlink.</b> Their identities were withheld
   *       too (rows intact — the evictor's gate), so they are counted once, as withheld.
   *   <li><b>The pre-unlink re-census</b>: one fresh reading taken here, after the row deletions. A
   *       candidate something references again — a withheld identity of another type, a pull since
   *       planning — is skipped and counted. The same set backs the {@link BlobStore.SweepGuard}
   *       asked again inside the store's write lock, so the check and the unlink cannot be separated
   *       by a write.
   *   <li>{@link BlobStore#delete} enforces the grace window and the guard once more, per blob,
   *       inside the lock. Every refusal is a counted outcome, never an exception.
   * </ol>
   */
  public SweepOutcome execute(
      MirrorBlobCensus.Census planned, Map<String, EvictionPlan> plans) {
    SweepPlan structural = reconcile(planned, plans, false);
    SweepPlan matured = reconcile(planned, plans, true);
    Set<String> withheldIds = new HashSet<>(structural.blobIds());
    withheldIds.removeAll(matured.blobIds());

    MirrorBlobCensus.Census fresh = census.take();
    Set<String> referenced = fresh.referenced();
    BlobStore.SweepGuard guard = blobId -> !referenced.contains(blobId);

    int unlinked = 0;
    long reclaimed = 0;
    int withheld = matured.withheldByGraceWindow();
    long withheldBytes = matured.withheldBytes();
    int stillReferenced = 0;
    int alreadyGone = 0;
    for (String blobId : structural.blobIds()) {
      if (withheldIds.contains(blobId)) {
        continue; // counted in the withheld figures already; its identity rows are intact too
      }
      if (referenced.contains(blobId)) {
        stillReferenced++;
        continue;
      }
      long size = fresh.onDisk().getOrDefault(blobId, planned.onDisk().getOrDefault(blobId, 0L));
      switch (blobs.delete(blobId, guard)) {
        case DELETED -> {
          unlinked++;
          reclaimed += size;
        }
        case STILL_REFERENCED -> stillReferenced++;
        case ALREADY_GONE, NOT_A_BLOB_ID -> alreadyGone++;
        case WITHIN_GRACE_WINDOW -> {
          // The store's own belt: the reconcile above judged this blob mature, the store's clock
          // says otherwise at the unlink. Counted as withheld — that is what it is.
          withheld++;
          withheldBytes += size;
        }
      }
    }
    return new SweepOutcome(
        unlinked, reclaimed, withheld, withheldBytes, stillReferenced, alreadyGone);
  }

  /** How long a blob must sit untouched before the store will remove it. */
  public Duration graceWindow() {
    return blobs.graceWindow();
  }
}
