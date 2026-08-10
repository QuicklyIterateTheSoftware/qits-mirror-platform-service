package eu.wohlben.qits.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A synthetic maven artifact: a real jar synthesised in memory, plus its pom — about as many lines
 * as it takes to say what a deploy is.
 *
 * <p>It exists for the reason {@code registry/TinyImage} and {@code npm/TinyPackage} do: {@code mvn
 * verify} may not assume maven is installed, and this repo's suite has no network at all, so the
 * deploy/resolve round trip still has to be proved on every build. The jar is a real zip — the
 * store treats it as bytes, but a client of this fixture should not be able to tell.
 *
 * <p>Every entry carries the epoch as its timestamp, so two jars built from the same content are
 * the same bytes — which is what makes the idempotent-redeploy case an exact-bytes assertion rather
 * than a hope.
 */
public final class TinyArtifact {

  private TinyArtifact() {}

  /** A real jar: a manifest and one class-path entry carrying the given unique content. */
  public static byte[] jar(String uniqueContent) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      write(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      write(zip, "eu/wohlben/qits/Tiny.class", uniqueContent.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** A minimal pom — what {@code mvn deploy} sends alongside the jar. */
  public static byte[] pom(String groupId, String artifactId, String version) {
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>"
            + groupId
            + "</groupId>\n"
            + "  <artifactId>"
            + artifactId
            + "</artifactId>\n"
            + "  <version>"
            + version
            + "</version>\n"
            + "</project>\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** The lowercase hex of one digest, for the checksum assertions. */
  public static String hex(byte[] bytes, String jcaAlgorithm) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(jcaAlgorithm).digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }
}
