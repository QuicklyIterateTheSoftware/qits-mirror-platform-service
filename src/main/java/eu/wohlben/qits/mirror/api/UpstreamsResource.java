package eu.wohlben.qits.mirror.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The explorer's second page: which container registries this service mirrors.
 *
 * <p>Served at {@code /mirror/api/upstreams}. Read-only, like its sibling — registering an upstream
 * writes two rows in one transaction and is qits-artifacts' CRUD surface today; this service shows
 * what is registered and nothing more.
 */
@Path("/upstreams")
@Produces(MediaType.APPLICATION_JSON)
public class UpstreamsResource {

  @Inject MirrorExplorer explorer;

  /** The listing envelope. */
  public record ListUpstreamsResponse(List<OciUpstreamRow> upstreams) {}

  /**
   * Every registered upstream.
   *
   * <p>Nothing is caught, for the reason {@link RepositoriesResource#list()} states: an empty
   * {@code upstreams} array means "this mirror fronts no registry", which during a database outage
   * is false and looks like a configuration someone should go and repair.
   */
  @GET
  public ListUpstreamsResponse list() {
    return new ListUpstreamsResponse(explorer.upstreams());
  }
}
