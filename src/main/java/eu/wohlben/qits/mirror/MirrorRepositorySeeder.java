package eu.wohlben.qits.mirror;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorUpstreams;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Idempotently ensures the cache roots exist: the npm cache ({@code npmjs}), the maven cache ({@code
 * central}), and the three OCI mirror namespaces with the upstream each of them fronts ({@code hub},
 * {@code quay}, {@code redhat}).
 *
 * <p><b>Only cache types.</b> This is the whole difference from {@code ArtifactsRepositorySeeder},
 * which this is modelled on: no {@code ci-screenshots}, no {@code qits} image repository, no {@code
 * npm} / {@code maven} hosted roots, no {@code daemons}, no {@code docs}. Those are qits-artifacts'
 * and always were; here they are not merely unseeded but unwritable — their profiles are excluded
 * from bean discovery and their keys are outside {@code ck_artifact_repository_type}, so an attempt
 * to ensure one is a 400 naming the three that are registered.
 *
 * <p>Purely additive — re-running is a no-op via {@link ArtifactRepositoryService#ensure}, which also
 * makes a repository's type immutable, so a name that somehow arrived as the wrong type is an error
 * rather than a silent conversion.
 *
 * <p>The OCI trio is written by {@link OciMirrorUpstreams#ensureDefaults()} rather than by three more
 * lines here, because each of them is a <b>pair</b>: a repository row with no upstream is a namespace
 * nothing can be fetched into, and an upstream row with no repository is a namespace nothing resolves
 * to. {@code V1} prefills both; this re-ensures them, so a deployment that lost one gets it back on
 * the next boot.
 */
@ApplicationScoped
public class MirrorRepositorySeeder {

  /**
   * The pull-through cache of npmjs, at {@code /artifacts/npm/npmjs/}. A pipeline's install names
   * this root from environment alone, so the one namespace nobody chooses must not also be a manual
   * step — and this service is a <b>core</b> service in the bootstrap order, which means the row has
   * to be there before the first CI build resolves a third-party dependency.
   *
   * <p>Named after what it fronts, and kept the same name qits-artifacts used, so the cutover is a
   * client-config change rather than a rename.
   */
  public static final String NPM_CACHE = "npmjs";

  /**
   * The pull-through cache of Maven Central, at {@code /artifacts/maven/central/}. Same argument as
   * {@link #NPM_CACHE}, and the same name it had.
   */
  public static final String MAVEN_CACHE = "central";

  @Inject ArtifactRepositoryService repositoryService;

  @Inject OciMirrorUpstreams mirrorUpstreams;

  @ActivateRequestContext
  public void ensureDefaults() {
    repositoryService.ensure(NPM_CACHE, NpmProxyProfile.KEY);
    repositoryService.ensure(MAVEN_CACHE, MavenProxyProfile.KEY);
    mirrorUpstreams.ensureDefaults();
  }
}
