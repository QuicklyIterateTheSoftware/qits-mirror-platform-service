package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Which repository rows belong to one cache type — the scope every evictor enumerates within.
 *
 * <p>One class rather than the same four lines in three evictors, because getting this wrong is the
 * one mistake a cache type can make. The format tables are shared between a format's hosted and
 * cached sides ({@code npm_version} holds both, so does {@code maven_artifact}), so the scope has to
 * come from the <b>repository row's type</b> rather than from anything about a coordinate. This
 * deployment registers no hosted type at all, which makes the mistake unreachable here today — and
 * the day somebody registers one, this is the line that keeps a published version out of a cache's
 * eviction rule rather than three copies of it, two of which were updated.
 */
@ApplicationScoped
public class CacheRoots {

  @Inject ArtifactRepositoryRepository repositories;

  /** Every repository of one stored type key, in the order the store lists them. */
  public List<String> of(String typeKey) {
    List<String> names = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type.equals(typeKey)) {
        names.add(repository.name);
      }
    }
    return names;
  }
}
