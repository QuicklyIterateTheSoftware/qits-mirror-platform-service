package eu.wohlben.qits.mirror.gc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A cache type that exists only in these cases, so the engine is tested on its rule and nothing
 * else.
 *
 * <p>The engine is generic by design, and a case driven through a real evictor would prove that one
 * type's enumeration works rather than that the rule does. This one answers the seam's two questions
 * — what exists, and how a row goes — with the smallest thing that can answer them.
 */
final class FakeCacheEvictor implements CacheEvictor {

  static final String REPO = "fake";

  private final List<CachedIdentity> candidates = new ArrayList<>();

  /** Records the plan {@link #delete} was handed, so the binding contract can be asserted. */
  EvictionPlan deleted;

  /** Adds one identity with its effective access time — creation already folded in. */
  FakeCacheEvictor add(String identity, Instant lastAccessAt, String... blobs) {
    candidates.add(new CachedIdentity(REPO, identity, lastAccessAt, Set.of(blobs)));
    return this;
  }

  @Override
  public String typeKey() {
    return "FAKE_CACHE";
  }

  @Override
  public List<CachedIdentity> enumerate() {
    return List.copyOf(candidates);
  }

  @Override
  public Applied delete(EvictionPlan plan, GraceWindow grace) {
    deleted = plan;
    return new Applied(plan.dead(), List.of(), List.of());
  }
}
