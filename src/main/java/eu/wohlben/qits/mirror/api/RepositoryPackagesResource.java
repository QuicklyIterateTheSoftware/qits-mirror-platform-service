package eu.wohlben.qits.mirror.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Read-only drill-down from a cache root into the packages it currently holds. */
@Path("/repositories/{repository}/packages")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryPackagesResource {

  @Inject MirrorExplorer explorer;

  public record ListPackagesResponse(
      MirrorRepositoryRow repository, List<CachedPackageRow> packages) {}

  @GET
  public ListPackagesResponse list(@PathParam("repository") String repository) {
    MirrorRepositoryRow root =
        explorer.repositories().stream()
            .filter(candidate -> candidate.name().equals(repository))
            .findFirst()
            .orElseThrow(NotFoundException::new);
    return new ListPackagesResponse(root, explorer.packages(root));
  }
}
