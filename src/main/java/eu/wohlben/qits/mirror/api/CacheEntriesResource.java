package eu.wohlben.qits.mirror.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The invalidate door: {@code DELETE /mirror/api/repositories/{repository}/entries?path=…}.
 *
 * <p>The third thing under a cache root, beside {@code /repositories} and {@code
 * /repositories/{repository}/packages} — the same grammar, one segment further in. {@code packages}
 * is what a root holds folded into coordinates for a page to draw; {@code entries} is the flat thing
 * the wire actually serves, one row per path, which is what an eviction has to name. A caller pastes
 * the path out of a failing build's log and it is already right.
 *
 * <p><b>The only authenticated route this service has.</b> Every other surface here — the two
 * listings, the packages drill-down, and all three wire protocols — is open, and must stay open: a
 * cache that authenticated its readers would be a cache nothing could read. This one writes, so it
 * takes the platform's operator-door idiom, which is {@code @RolesAllowed} over an identity
 * qits-auth-core assembles from qits-gateway's {@code X-Qits-User} / {@code X-Qits-Roles} or from a
 * bearer minted for {@code qits-platform-mirror}. Two roles, because both callers are real: {@code
 * qits:admin} is the operator clearing a fault from the explorer, {@code qits:system} is a repair job
 * doing the same thing without a person. See the identity block in application.properties for why the
 * guard is an annotation on this method and never a path policy.
 *
 * <p><b>Nothing is caught here</b>, exactly as in {@link RepositoriesResource}. {@link
 * MirrorEntryEviction} throws the refusal it means, with its reason in the body, and a database that
 * cannot be written reaches the caller as a 500 — which is the answer an operator needs, because the
 * alternative is a door that reports "evicted" and left the fault in place.
 */
@Path("/repositories/{repository}/entries")
@Produces(MediaType.APPLICATION_JSON)
public class CacheEntriesResource {

  @Inject MirrorEntryEviction eviction;

  /**
   * Drops one cached entry so the next request for it fetches from upstream again.
   *
   * <p>Answers the eviction rather than {@code 204}, and that is deliberate: {@code rowsRemoved} is
   * the number that tells an operator whether they cleared a cold entry or a corrupt one, and a
   * no-content answer would throw it away. See {@link MirrorEntryEviction.Evicted}.
   *
   * <p>Not idempotent in the {@code 204}-forever sense: a second call is a {@code 404}, because
   * "nothing is cached there" and "the entry is gone now" are different facts and an operator
   * clearing a poisoned coordinate has to be able to tell a hit from a typo.
   *
   * @param repository a cache root from {@code GET /mirror/api/repositories}
   * @param path the entry exactly as the wire serves it, repository-relative
   */
  @DELETE
  @RolesAllowed({"qits:admin", "qits:system"})
  public MirrorEntryEviction.Evicted evict(
      @PathParam("repository") String repository, @QueryParam("path") String path) {
    return eviction.evict(repository, path);
  }
}
