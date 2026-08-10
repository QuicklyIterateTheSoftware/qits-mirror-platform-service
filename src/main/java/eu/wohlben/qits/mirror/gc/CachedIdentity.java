package eu.wohlben.qits.mirror.gc;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One cached identity, reduced to the three facts the eviction engine reads.
 *
 * <p>This is the whole translation between a cache type and the rule. What an identity <em>is</em> —
 * a mirrored tag, a cached npm version, a cached maven path — stays inside its {@link CacheEvictor};
 * what the engine sees is this record, and nothing here names a protocol.
 *
 * @param repository the {@code artifact_repository} row it lives in
 * @param identity the type's own coordinate, spelled the way that type's tools spell it, so a report
 *     can be looked up without translating
 * @param lastAccessAt the <b>effective access time</b>: {@code max(created/updated/fetched,
 *     accessed_at)}. Creation counts as a first access, so something cached an hour ago is young
 *     rather than never-read, and null therefore never reaches the engine
 * @param blobs every blob this identity names. The engine puts them in the released or the retained
 *     set; which of them may actually be unlinked stays {@link MirrorBlobSweep}'s answer alone
 */
public record CachedIdentity(
    String repository, String identity, Instant lastAccessAt, Set<String> blobs) {

  public CachedIdentity {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(
        lastAccessAt,
        "lastAccessAt: an evictor must fold creation into the access time, so that a row with no"
            + " read yet reads as young rather than as unknown");
    blobs = Set.copyOf(blobs);
  }

  /** Whether this identity was last touched before the cut — the access rule, said once. */
  boolean unaccessedSince(Instant cut) {
    return lastAccessAt.isBefore(cut);
  }
}
