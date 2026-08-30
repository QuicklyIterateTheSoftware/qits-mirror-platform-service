package eu.wohlben.qits.mirror.stories.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the command-line tools a story drives actually are — resolved once per JVM, and answerable
 * as a plain {@code boolean} so a class can gate itself with {@code @EnabledIf}.
 *
 * <p><b>The resolved value is passed as an ARGUMENT, never written into a template.</b> A story
 * spells {@code commands.run("{} install {}", Cli.npm(), registry)} rather than {@code run("npm
 * install …")}, for the same reason a URL is never spelled into one: the display line gets the real
 * program while the fingerprint keeps {@code {}}, so the story's {@code definitionHash} is the same
 * on a workstation, in CI and in a container that resolves the tool somewhere else entirely.
 *
 * <p><b>Missing is a SKIP, and the skip must happen before the story starts.</b> {@link
 * #npmPresent()} exists for {@code @EnabledIf} at class level; a story body must never call {@code
 * assumeTrue}, because the userflows extension has already opened a report by then and an aborted
 * story emits a red one. A skipped story emits nothing at all, which is the honest answer for "this
 * machine has no npm".
 *
 * <h2>Only npm, and that is a decision rather than a gap</h2>
 *
 * <p>This service caches three formats and only one of them is driven here by its real client.
 *
 * <ul>
 *   <li><b>npm is here</b> because an {@code npm install} through this mirror is the single flow the
 *       whole service exists for, and because it exercises two things no wire-level assertion can:
 *       npm <em>follows</em> the rewritten {@code dist.tarball} URL (which is not a config key and
 *       can only be right against a real client on a real port), and npm <em>verifies</em>
 *       upstream's {@code integrity} against the bytes this cache handed it end to end.
 *   <li><b>maven is not</b>, and the reason is the machine rather than the story. Resolving through
 *       the mirror with the real launcher means a second Maven JVM inside a build that is already
 *       running one plus a failsafe fork, a launched Quarkus artifact and an embedded postgres.
 *       {@code stories/maven} therefore drives the wire directly, and says so: what it gives up is
 *       "a real resolver accepted this", and what it keeps — the cold fetch, the warm hit, the
 *       upstream count and the derived checksum — is every claim that is about <em>this</em>
 *       service.
 *   <li><b>The OCI plane is not</b>, for a harder reason: a {@code docker pull} is executed by a
 *       daemon, not by the client, so the address a story would have to hand it is resolved in
 *       another process' network namespace and the registry would have to be HTTPS or in that
 *       daemon's insecure list. {@code skopeo}, which sibling repositories use to sidestep exactly
 *       that, is not installed in this container. {@code stories/oci} therefore drives {@code /v2}
 *       directly, which is what a client does anyway once its daemon has resolved the host.
 * </ul>
 */
public final class Cli {

  /** An explicit override for npm, for a machine that keeps it somewhere unusual. */
  public static final String NPM_PROPERTY = "qits.userflows.npm";

  /**
   * One resolution per tool per JVM. {@code @EnabledIf} is evaluated once per class and a story may
   * ask again, so the {@code PATH} walk should happen once; {@link Optional} rather than {@code
   * null} because {@link ConcurrentHashMap} admits no null value.
   */
  private static final Map<String, Optional<String>> RESOLVED = new ConcurrentHashMap<>();

  private Cli() {}

  /** npm: the override property, else {@code PATH}. */
  public static String npm() {
    return resolve("npm")
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no npm on this machine — a story needing it must be gated with @EnabledIf on"
                        + " Cli#npmPresent"));
  }

  public static boolean npmPresent() {
    return resolve("npm").isPresent();
  }

  private static Optional<String> resolve(String tool) {
    return RESOLVED.computeIfAbsent(
        tool, name -> declared(NPM_PROPERTY).or(() -> onPath(name)));
  }

  /**
   * A system property naming an executable. A property that is unset, blank or names something that
   * is not executable is <b>not</b> an error — it falls through to {@code PATH}, so a machine that
   * has the tool anyway is not failed by a stale override.
   */
  private static Optional<String> declared(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Path candidate = Path.of(value.strip());
    return Files.isExecutable(candidate) && !Files.isDirectory(candidate)
        ? Optional.of(candidate.toAbsolutePath().toString())
        : Optional.empty();
  }

  /**
   * The bare tool name, if some {@code PATH} entry holds it. The <b>name</b> rather than the
   * absolute path on purpose: {@link ProcessBuilder} resolves it identically, and a transcript
   * reading {@code npm install …} is the line a reader would retype.
   */
  private static Optional<String> onPath(String tool) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (entry.isBlank()) {
        continue;
      }
      Path candidate = Path.of(entry).resolve(tool);
      if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
        return Optional.of(tool);
      }
    }
    return Optional.empty();
  }
}
