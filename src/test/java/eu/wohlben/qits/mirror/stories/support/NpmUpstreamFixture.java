package eu.wohlben.qits.mirror.stories.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.npm.TinyPackage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the npm registry this service caches actually holds, for the stories that need it hosted.
 *
 * <p>The documents are <b>npmjs' own shapes</b> rather than invented ones: a packument with {@code
 * dist-tags}, {@code versions} and a {@code dist} block per version, and a tarball served at {@code
 * <name>/-/<unscoped>-<version>.tgz}. Anything got wrong here would make the stories prove a
 * registry that does not exist — and would do it quietly, because this service passes a packument
 * <em>through</em> rather than rebuilding it, so a malformed one would sail past every assertion
 * that is not about its content.
 *
 * <p>The tarball is a <b>real gzipped USTAR archive</b> ({@link TinyPackage}), and {@code
 * dist.shasum} / {@code dist.integrity} are that archive's genuine SHA-1 and SHA-512. That costs
 * nothing where a story only compares bytes — this service verifies neither hash, deliberately, and
 * re-emits both untouched so the installing client checks the bytes against a hash this process
 * never computed and could not forge — and it is the whole reason {@code stories/npm} can exist at
 * all: the real npm CLI unpacks the archive and refuses the install if the integrity does not match.
 *
 * <p>Every name a story hosts is <b>synthetic and story-scoped</b>. Nothing here exists on the real
 * npmjs, so a fixture that failed to register could never be papered over by the internet answering
 * plausibly instead; and no two stories share a name, so no story's assertion about what upstream
 * was asked can be satisfied by another story's traffic.
 */
public final class NpmUpstreamFixture {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * A marker no rewrite may drop. This service serves upstream's document through, moving one field
   * per version; a member it never reads still has to reach the client, which is what lets npmjs add
   * a field tomorrow with no release here.
   */
  public static final String UPSTREAM_MARKER = "the recording registry";

  private NpmUpstreamFixture() {}

  /** Where the packument for {@code name} is asked for, on the registry's own path grammar. */
  public static String packumentPath(String name) {
    return "/" + name;
  }

  /** Where the tarball of one version lives — npmjs' layout, which this service re-emits. */
  public static String tarballPath(String name, String version) {
    return "/" + name + "/-/" + name + "-" + version + ".tgz";
  }

  /**
   * Host one version of {@code name} upstream: the packument that names it {@code latest}, and the
   * archive that packument points at.
   *
   * @return the archive, so a story can compare what the client got against what upstream served
   */
  public static TinyPackage host(RecordingUpstream registry, String name, String version) {
    TinyPackage archive = TinyPackage.of(name, version);
    registry.serve(
        packumentPath(name),
        "application/json",
        packument(registry.baseUrl(), name, version, archive, null),
        Map.of("ETag", "\"" + version + "-" + archive.shasum().substring(0, 8) + "\""));
    registry.serve(
        tarballPath(name, version), "application/octet-stream", archive.tarball());
    return archive;
  }

  /**
   * Host a packument naming <b>two</b> versions but only the archive of the first — the shape {@code
   * stories/outage} needs. One version is pullable and the other's bytes are only reachable while
   * upstream answers, which is what makes "what is cached keeps installing and what is not says so"
   * two observable outcomes rather than one.
   */
  public static TinyPackage hostTwoVersionsWithOneArchive(
      RecordingUpstream registry, String name, String cached, String uncached) {
    TinyPackage archive = TinyPackage.of(name, cached);
    TinyPackage other = TinyPackage.of(name, uncached);
    registry.serve(
        packumentPath(name),
        "application/json",
        packumentOfBoth(registry.baseUrl(), name, cached, archive, uncached, other),
        Map.of("ETag", "\"" + cached + "-" + archive.shasum().substring(0, 8) + "\""));
    registry.serve(tarballPath(name, cached), "application/octet-stream", archive.tarball());
    registry.serve(tarballPath(name, uncached), "application/octet-stream", other.tarball());
    return archive;
  }

  /**
   * Host a packument whose {@code dist.tarball} names an address that refuses the connection, and
   * <b>no</b> archive at all.
   *
   * <p>This is an upstream whose index resolves and whose bytes do not — the distinction {@code
   * stories/refusals} is entirely about. The dial for the archive goes to {@code deadAddress} and
   * therefore reaches this registry's recording <b>nowhere</b>, which is what turns "the tarball was
   * never asked for here" into the proof that the 502 was an outage and not a refusal.
   */
  public static void hostWithUnreachableArchive(
      RecordingUpstream registry, String name, String version, String deadAddress) {
    TinyPackage archive = TinyPackage.of(name, version);
    registry.serve(
        packumentPath(name),
        "application/json",
        packument(registry.baseUrl(), name, version, archive, deadAddress),
        Map.of("ETag", "\"" + version + "-gone\""));
  }

  // --- the documents ---------------------------------------------------------------------------

  private static byte[] packument(
      String base, String name, String version, TinyPackage archive, String tarballBaseOverride) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("_id", name);
    document.put("name", name);
    document.put("dist-tags", Map.of("latest", version));
    document.put(
        "versions",
        Map.of(
            version,
            versionManifest(
                (tarballBaseOverride == null ? base : tarballBaseOverride)
                    + tarballPath(name, version),
                archive)));
    document.put("_upstream", UPSTREAM_MARKER);
    return encode(document);
  }

  private static byte[] packumentOfBoth(
      String base,
      String name,
      String firstVersion,
      TinyPackage first,
      String secondVersion,
      TinyPackage second) {
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put(
        firstVersion, versionManifest(base + tarballPath(name, firstVersion), first));
    versions.put(
        secondVersion, versionManifest(base + tarballPath(name, secondVersion), second));

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("_id", name);
    document.put("name", name);
    // `latest` stays on the version a story pulled FIRST: an npm client resolving a range picks
    // it, so the outage arm's "install what is cached" is the plain `npm install <name>` case.
    document.put("dist-tags", Map.of("latest", firstVersion));
    document.put("versions", versions);
    document.put("_upstream", UPSTREAM_MARKER);
    return encode(document);
  }

  /**
   * One version's manifest, as it sits inside a packument: the package's own {@code package.json}
   * members plus the {@code dist} block upstream computes.
   *
   * <p>{@code dist.tarball} is the field this service rewrites at serve time — pointing it back at
   * the request's own authority, which is the whole reason an install routed here stays routed here.
   * Everything beside it, {@code shasum} and {@code integrity} included, must reach the client
   * exactly as written.
   */
  private static Map<String, Object> versionManifest(String tarballUrl, TinyPackage archive) {
    Map<String, Object> manifest = new LinkedHashMap<>(archive.manifest());
    manifest.put("_id", archive.name() + "@" + archive.version());
    manifest.put(
        "dist",
        Map.of(
            "tarball", tarballUrl,
            "shasum", archive.shasum(),
            "integrity", archive.integrity()));
    return manifest;
  }

  private static byte[] encode(Map<String, Object> document) {
    try {
      return JSON.writeValueAsBytes(document);
    } catch (Exception unwritable) {
      throw new IllegalStateException(unwritable);
    }
  }

  /** The members of a packument a story checks reached the client unedited. */
  public static List<String> passthroughMarkers(TinyPackage archive) {
    return List.of(archive.shasum(), archive.integrity(), UPSTREAM_MARKER);
  }

  /** A document as text, for the byte-for-byte comparisons the warm stories make. */
  public static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
