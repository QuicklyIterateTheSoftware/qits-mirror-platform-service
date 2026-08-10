package eu.wohlben.qits.mirror.gc;

import java.util.List;
import java.util.Set;

/**
 * What one cache type's plan says: the identities, and the two blob sets the reconciliation needs.
 *
 * <p><b>The two sets may overlap, and that overlap is the point of reporting both.</b> A layer under
 * a dying tag and a surviving one is released <em>and</em> retained; subtracting is {@link
 * MirrorBlobSweep}'s job, so a type never has to reason about the store beyond itself.
 *
 * @param dead the identities this run would delete, each naming the rule that condemned it
 * @param kept the identities it would keep, each naming the rule that saved it — the half of a
 *     dry-run report that makes the other half reviewable
 * @param blobsReleased every blob the dead identities reference
 * @param blobsRetained every blob this type still references once the dead ones are gone — the
 *     type's live set <em>after</em> the plan, not the delta
 */
public record EvictionPlan(
    List<JudgedIdentity> dead,
    List<JudgedIdentity> kept,
    Set<String> blobsReleased,
    Set<String> blobsRetained) {

  public EvictionPlan {
    dead = List.copyOf(dead);
    kept = List.copyOf(kept);
    blobsReleased = Set.copyOf(blobsReleased);
    blobsRetained = Set.copyOf(blobsRetained);
  }

  /** A type with nothing to do — the honest answer for a cache whose every entry is warm. */
  public static EvictionPlan nothingDies(List<JudgedIdentity> kept, Set<String> live) {
    return new EvictionPlan(List.of(), kept, Set.of(), live);
  }
}
