package eu.wohlben.qits.mirror.gc;

import java.util.List;

/**
 * One cache type's own facts: what it holds, when each entry was last wanted, and how a row goes.
 *
 * <p>The seam is drawn where it is for one reason: an identity — a tag, a version, a cached path —
 * has a meaning only inside its type, while a blob has none anywhere. So an evictor answers only
 * about identities and the blobs they name, and <b>never</b> decides that a blob may be unlinked.
 * Which blobs lost their last reference across all three types is {@link MirrorBlobSweep}'s answer,
 * and only its answer.
 *
 * <p><b>No rule lives in an implementation of this.</b> The rule is {@link CacheEviction}'s, written
 * once, because all three types run the same one: a cached entry unused for longer than the window
 * is evicted. An implementation contributes an enumeration and a deletion, which is all that differs
 * between npm, maven and OCI.
 *
 * <p>Registering one is a CDI bean of this type and nothing else. Exactly one may claim a type key.
 */
public interface CacheEvictor {

  /** The stored {@code artifact_repository.type} key this evictor collects, e.g. {@code NPM_PROXY}. */
  String typeKey();

  /**
   * Every cached identity of this type, with its effective access time and the blobs it names.
   *
   * <p>Read-only: this is what a dry-run report is built from, and a report that changed the store
   * would be the one nobody could review.
   */
  List<CachedIdentity> enumerate();

  /**
   * A standing sentence a report carries beside this type's line, or null.
   *
   * <p>Exists for the two document caches, whose reclaimable-bytes figure needs a caption: evicting
   * a cached document frees rows, not blob files.
   */
  default String note() {
    return null;
  }

  /**
   * Deletes the identity rows of a plan this evictor's type produced <b>moments ago</b>. A stored or
   * hand-edited plan must never reach this method: the store moves, and applying a stale plan is how
   * the wrong identity dies.
   *
   * <p><b>The grace window gates identities here, not just blobs at the unlink.</b> A blob may only
   * be swept by <em>losing</em> its last identity row, so deleting a row while the blob's file is
   * still inside the grace window would strand that blob forever — row-less, therefore untouchable,
   * and never reclaimed. An identity whose released blobs include one still in grace is <b>withheld
   * whole</b>: its rows stay, the next run re-plans it, and the run after the window matures deletes
   * row and file together.
   *
   * <p>Failures are collected per identity rather than thrown, because a half-applied type must
   * report what it did — the rows already deleted are deleted, and hiding them behind an exception
   * would leave their blobs unswept with nothing in the report to say why.
   *
   * @param plan this evictor's own freshly computed plan
   * @param grace answers whether a blob's file is still inside the grace window
   */
  Applied delete(EvictionPlan plan, GraceWindow grace);

  /** Whether a blob's file is younger than the sweep's grace window. Supplied by the sweep. */
  @FunctionalInterface
  interface GraceWindow {
    boolean withinGrace(String blobId);
  }

  /**
   * What {@link #delete} actually did, identity by identity.
   *
   * @param deleted the identities whose rows are gone now
   * @param withheldByGraceWindow identities left whole because a blob they release is still inside
   *     the grace window — not lost, re-planned next run
   * @param errors identities that could not be applied, each with its reason; the rest of the plan
   *     was still applied
   */
  record Applied(
      List<JudgedIdentity> deleted, List<JudgedIdentity> withheldByGraceWindow, List<String> errors) {

    public Applied {
      deleted = List.copyOf(deleted);
      withheldByGraceWindow = List.copyOf(withheldByGraceWindow);
      errors = List.copyOf(errors);
    }

    public static Applied nothing() {
      return new Applied(List.of(), List.of(), List.of());
    }
  }
}
