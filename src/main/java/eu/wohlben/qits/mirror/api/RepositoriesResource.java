package eu.wohlben.qits.mirror.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The explorer's first page: every cache root this service holds.
 *
 * <p>Served at {@code /mirror/api/repositories} — the path here is relative to {@code
 * quarkus.rest.path}, which carries the segment. One GET, no query parameters and no paging: the
 * table has five rows on a shipped deployment and grows by an operator registering an upstream.
 *
 * <p>The response is wrapped in an object with one key rather than being a bare array, so a field
 * about the listing itself can be added later without changing the shape every reader parses.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoriesResource {

  @Inject MirrorExplorer explorer;

  /** The listing envelope. */
  public record ListRepositoriesResponse(List<MirrorRepositoryRow> repositories) {}

  /**
   * Every cache root.
   *
   * <p><b>Nothing is caught here.</b> A database that cannot be read throws out of this method and
   * Quarkus answers 500, which is the whole contract — see {@link MirrorExplorer} for why an empty
   * list would be the expensive lie. Adding a try/catch that answers {@code {"repositories":[]}}
   * would make a page that says "this mirror caches nothing" during an outage.
   */
  @GET
  public ListRepositoriesResponse list() {
    return new ListRepositoriesResponse(explorer.repositories());
  }
}
