package eu.wohlben.qits.mirror.api;

import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorUpstreams;
import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The reads behind {@code /mirror/api} — what is cached here, and what each cache fronts.
 *
 * <p><b>Read-only, and that is the shape of the whole surface.</b> There is no create, no delete and
 * no "evict now" button: what this service holds is decided by what somebody pulled, and what it
 * drops is decided by a window in configuration and a clock (see the eviction block in
 * application.properties). A page that could delete a cached tag would be a second eviction policy
 * with no record of why it ran.
 *
 * <p><b>A FAILED READ MUST REACH THE CALLER AS A FAILURE.</b> Nothing here catches a database error
 * to answer an empty list, and nothing may be added that does. This service is the one place on the
 * platform where the cost of the opposite is already written down: a wrong "there is no such thing"
 * is cached by every docker, npm and maven client that asked. The rule is the same one qits-githost
 * paid for on 2026-08-11 — "I could not ask" and "the answer is no" are different answers and must
 * not share a status code — and here the empty list is that mistake's shape. An unmapped exception
 * out of a JAX-RS resource is a 500, which is the answer we want, so the correctness of this class is
 * mostly in what it does <em>not</em> do.
 *
 * <p><b>{@link DbRetry} is the seam under that.</b> The pool settings ({@code validate-on-borrow},
 * {@code acquisition-timeout}, {@code PatientPgDriver}) cover a connection that was dead before the
 * read began; they cannot cover one that dies mid-flight, after statements ran. These are reads a
 * caller is waiting on, so they are that method's documented call site. It retries connection-class
 * failures only and rethrows everything else at once — and a retry whose session the first failure
 * already poisoned simply fails again and is rethrown, which is still a 5xx. What it buys is the
 * common case: postgres came back during the deadline.
 */
@ApplicationScoped
public class MirrorExplorer {

  @Inject ArtifactRepositoryRepository repositories;

  @Inject OciMirrorUpstreams upstreams;

  /**
   * The address the npm cache fronts, and the maven one below it.
   *
   * <p>{@code Optional<String>} rather than {@code String} for the reason recorded against {@code
   * qits.artifacts.oci.mirror.endpoint-override}: SmallRye reads a configured-<b>empty</b> value as
   * ABSENT, so a plain {@code String} injection would fail the packaged boot of any deployment that
   * blanked the key while every test, which sets a real value, stayed green.
   */
  @ConfigProperty(name = "qits.artifacts.npm.proxy.upstream")
  Optional<String> npmUpstream;

  @ConfigProperty(name = "qits.artifacts.maven.proxy.upstream")
  Optional<String> mavenUpstream;

  /**
   * Every cache root, by name, with what it is a cache of.
   *
   * <p>One query over a table that holds five rows on a shipped deployment, plus one lookup per OCI
   * namespace to name its registry. Sorted by name so two calls a second apart cannot reorder a
   * table under someone's cursor.
   */
  public List<MirrorRepositoryRow> repositories() {
    return DbRetry.call(
        "list the mirror's cache roots",
        () -> {
          List<MirrorRepositoryRow> rows = new ArrayList<>();
          for (ArtifactRepository repository : repositories.listAll(Sort.ascending("name"))) {
            rows.add(
                new MirrorRepositoryRow(
                    repository.name,
                    RepositoryTypeProfile.wireNameOf(repository.type),
                    upstreamOf(repository),
                    repository.createdAt));
          }
          return rows;
        });
  }

  /** Every registered OCI upstream, by namespace, with how much of it has been pulled through. */
  public List<OciUpstreamRow> upstreams() {
    return DbRetry.call(
        "list the mirror's OCI upstreams",
        () -> {
          List<OciUpstreamRow> rows = new ArrayList<>();
          for (MirrorUpstreamSummary summary : upstreams.list()) {
            rows.add(
                new OciUpstreamRow(
                    summary.domain(), summary.slug(), summary.cachedImages(), summary.createdAt()));
          }
          return rows;
        });
  }

  /**
   * What a root caches, by its type — the one place the three types differ in this API.
   *
   * <p>npm and maven each front one address that is a config key; an OCI namespace fronts a registry
   * that is a <b>row</b>, because which registries are mirrored has a CRUD surface while a config key
   * is invisible. An OCI row with no upstream row answers null rather than inventing a name: that is
   * a namespace whose upstream was deleted, still serving what it cached and unable to fetch anything
   * new, and the empty cell says exactly that.
   */
  private String upstreamOf(ArtifactRepository repository) {
    if (NpmProxyProfile.KEY.equals(repository.type)) {
      return npmUpstream.orElse(null);
    }
    if (MavenProxyProfile.KEY.equals(repository.type)) {
      return mavenUpstream.orElse(null);
    }
    if (OciMirrorProfile.KEY.equals(repository.type)) {
      return upstreams.bySlug(repository.name).map(upstream -> upstream.domain).orElse(null);
    }
    // Unreachable while quarkus.arc.exclude-types holds the registered set to the three above, and
    // deliberately not an exception: a listing that refused to render because one row is unfamiliar
    // would hide the four rows that are fine.
    return null;
  }
}
