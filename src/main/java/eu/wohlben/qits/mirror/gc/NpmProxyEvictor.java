package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmRegistryCollection;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The npm cache's facts: two kinds of cached identity, when each was last wanted, and how a row goes
 * <b>without a tombstone</b>.
 *
 * <h2>Two identities, because the cache holds two things</h2>
 *
 * <ul>
 *   <li>A <b>cached version</b> — an {@code npm_version} row with its tarball blob, spelled {@code
 *       <package>@<version>} exactly as npm spells it.
 *   <li>A <b>cached packument</b> — an {@code npm_proxy_packument} row, spelled {@code <package>
 *       (packument)} so it can never be mistaken for a version. It is the store's largest single
 *       cost after the image layers: mostly documents for packages nothing has installed in months.
 * </ul>
 *
 * <h2>Staleness</h2>
 *
 * <p>A version's effective access is {@code max(created_at, accessed_at)} — the column every tarball
 * GET moves, and creation counts as the first access so something pulled through an hour ago is
 * young rather than never-read.
 *
 * <p>A packument's is {@code max(fetched_at, the newest access among that package's cached
 * versions)}. Both halves are needed and neither alone is honest: {@code fetched_at} only says when
 * the <em>document</em> was last revalidated upstream, which a TTL moves on its own, while a package
 * whose tarballs are still being installed is plainly in use even if its document has sat inside its
 * TTL. Folding the versions in also makes the two die together: a packument outlives every version
 * it lists, so the document goes only in a run where all of them are cold, and the surviving
 * versions of a warm package always keep their document.
 *
 * <h2>Eviction writes no tombstone, and that is the point</h2>
 *
 * <p>The hosted door ({@code NpmRegistryCollection.collect}) deletes a version and writes the
 * republish tombstone that spends its name forever. Here that would be wrong in the exact direction
 * that breaks a cache: the version is upstream's, and re-fetching it is what this service exists
 * for. So eviction goes through {@code evictProxiedVersion}/{@code evictProxiedPackument}, which
 * write none and refuse any repository that is not an {@code npm-proxy}.
 */
@Singleton
public class NpmProxyEvictor implements CacheEvictor {

  /**
   * What distinguishes a packument identity from a version's. A space cannot appear in a package
   * name or a version, so no coordinate can collide with this spelling, and it reads as what it is
   * in a report.
   */
  public static final String PACKUMENT = " (packument)";

  @Inject CacheRoots roots;
  @Inject NpmVersionRepository versions;
  @Inject NpmProxyPackumentRepository packuments;
  @Inject NpmRegistryCollection npm;

  @Override
  public String typeKey() {
    return NpmProxyProfile.KEY;
  }

  @Override
  public List<CachedIdentity> enumerate() {
    List<CachedIdentity> candidates = new ArrayList<>();
    for (String repository : roots.of(typeKey())) {
      collect(repository, candidates);
    }
    return List.copyOf(candidates);
  }

  /**
   * The caption this type's line carries: evicting a document reclaims no disk.
   *
   * <p>The documents are {@code text} columns, not files. Evicting one removes its characters from
   * the table; PostgreSQL then reuses that space for the next insert, and the data file shrinks only
   * under a {@code VACUUM FULL}, which nothing in this service runs — it takes an exclusive lock on
   * a table this service serves from. Without this line a reviewer reads {@code reclaimedBytes: 0}
   * beside a hundred condemned packuments and concludes the collector is broken.
   *
   * <p>The figure is computed fresh rather than remembered, so it can never be a number from a run
   * somebody else made.
   */
  @Override
  public String note() {
    return "cached packuments are text columns, not files: "
        + packuments.totalDocLength(roots.of(typeKey()))
        + " characters are cached as this report was produced. Evicting one frees its row for reuse"
        + " and reclaims 0 bytes on disk — PostgreSQL returns the space to the table rather than to"
        + " the filesystem, which only a VACUUM FULL does and nothing here runs one. The reclaimed"
        + " bytes on this line count tarball blobs and nothing else.";
  }

  /**
   * Versions first, then packuments — a document whose versions are still being deleted is the shape
   * a reader expects, and the reverse would briefly leave a packument-less package with tarballs
   * still in it. Either order is correct: the next request re-fetches whatever is missing.
   */
  @Override
  public Applied delete(EvictionPlan plan, GraceWindow grace) {
    List<JudgedIdentity> deleted = new ArrayList<>();
    List<JudgedIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (JudgedIdentity dead : plan.dead()) {
      if (!isPackument(dead)) {
        deleteVersion(dead, grace, deleted, withheld, errors);
      }
    }
    for (JudgedIdentity dead : plan.dead()) {
      if (isPackument(dead)) {
        deletePackument(dead, deleted, errors);
      }
    }
    return new Applied(deleted, withheld, errors);
  }

  /** One cache root: every cached version, then every cached document. */
  private void collect(String repository, List<CachedIdentity> candidates) {
    Map<String, Instant> newestVersionAccess = new HashMap<>();
    for (String packageName : versions.listPackageNames(repository)) {
      for (Object[] row : versions.listVersionRows(repository, packageName)) {
        String version = (String) row[0];
        String tarball = (String) row[1];
        Instant access = latest((Instant) row[2], (Instant) row[3]);
        newestVersionAccess.merge(packageName, access, NpmProxyEvictor::later);
        candidates.add(
            new CachedIdentity(
                repository, packageName + "@" + version, access, Set.of(tarball)));
      }
    }
    for (Object[] row : packuments.listCached(repository)) {
      String packageName = (String) row[0];
      Instant fetchedAt = (Instant) row[1];
      candidates.add(
          new CachedIdentity(
              repository,
              packageName + PACKUMENT,
              later(fetchedAt, newestVersionAccess.get(packageName)),
              // No blob: a packument is a database row, not a file. Releasing one frees no disk,
              // which is why the note says so on every report.
              Set.of()));
    }
  }

  private static boolean isPackument(JudgedIdentity identity) {
    return identity.identity().endsWith(PACKUMENT);
  }

  private void deleteVersion(
      JudgedIdentity dead,
      GraceWindow grace,
      List<JudgedIdentity> deleted,
      List<JudgedIdentity> withheld,
      List<String> errors) {
    // A scoped package starts with '@', so the LAST '@' is the separator; a version has none.
    int at = dead.identity().lastIndexOf('@');
    String packageName = dead.identity().substring(0, at);
    String version = dead.identity().substring(at + 1);
    try {
      NpmVersion row = versions.findOne(dead.repository(), packageName, version).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such version row — the store moved since planning");
        return;
      }
      if (grace.withinGrace(row.tarballBlobId)) {
        withheld.add(dead);
        return;
      }
      npm.evictProxiedVersion(dead.repository(), packageName, version);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * A packument names no blob, so nothing can be inside the grace window and it is never withheld.
   * The window exists to stop a row deletion from stranding a young file; there is no file.
   */
  private void deletePackument(
      JudgedIdentity dead, List<JudgedIdentity> deleted, List<String> errors) {
    String packageName =
        dead.identity().substring(0, dead.identity().length() - PACKUMENT.length());
    try {
      npm.evictProxiedPackument(dead.repository(), packageName);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /** Creation counts as the first access, so a version cached minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static Instant later(Instant one, Instant other) {
    if (one == null) {
      return other;
    }
    return other == null || other.isBefore(one) ? one : other;
  }
}
