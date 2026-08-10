package eu.wohlben.qits.npm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * A minimal but real npm package, built in memory: a gzipped tar with a {@code package/} prefix, a
 * manifest, and the publish document {@code npm publish} would have sent for it.
 *
 * <p>Synthesised rather than committed, exactly as {@code registry/TinyImage} is and for the same
 * reason: {@code mvn verify} may not assume npm, pnpm or a network is present, so the publish/install
 * round trip has to be provable from a clone with nothing but a JDK. Synthesising also makes the
 * hashes deterministic per salt, so two publishes of the same salt really are the same tarball and a
 * dedupe assertion means something.
 *
 * <p>The tarball is a genuine USTAR archive under gzip — {@code tar tzf} lists it — because the
 * point of the round trip is that the bytes that come back out are installable, not merely equal.
 */
public record TinyPackage(String name, String version, byte[] tarball, Map<String, Object> manifest) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A package whose content is determined entirely by its name and version. */
  public static TinyPackage of(String name, String version) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("name", name);
    manifest.put("version", version);
    manifest.put("description", "a synthetic package for " + name);
    manifest.put("main", "index.js");
    manifest.put("license", "MIT");

    byte[] tar =
        tar(
            Map.of(
                "package/package.json", json(manifest),
                "package/index.js",
                ("module.exports = " + JSON.valueToTree(name) + ";\n")
                    .getBytes(StandardCharsets.UTF_8)));
    return new TinyPackage(name, version, gzip(tar), manifest);
  }

  /** {@code dist.shasum}: sha1, hex. */
  public String shasum() {
    return HexFormat.of().formatHex(digest("SHA-1", tarball));
  }

  /** {@code dist.integrity}: {@code sha512-<base64>}. */
  public String integrity() {
    return "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball));
  }

  /**
   * The document {@code npm publish} PUTs: the manifest under {@code versions}, the dist-tag to
   * move, and the tarball base64-encoded under {@code _attachments} — keyed by {@code
   * <full name>-<version>.tgz}, the scoped spelling npm actually uses.
   */
  public byte[] publishDocument(String distTag) {
    return publishDocument(distTag, shasum(), integrity());
  }

  /** The same document with the client's hash claims forced, for the mismatch cases. */
  public byte[] publishDocument(String distTag, String claimedShasum, String claimedIntegrity) {
    Map<String, Object> dist = new LinkedHashMap<>();
    dist.put("shasum", claimedShasum);
    dist.put("integrity", claimedIntegrity);

    Map<String, Object> versionManifest = new LinkedHashMap<>(manifest);
    versionManifest.put("_id", name + "@" + version);
    versionManifest.put("dist", dist);

    Map<String, Object> attachment = new LinkedHashMap<>();
    attachment.put("content_type", "application/octet-stream");
    attachment.put("data", Base64.getEncoder().encodeToString(tarball));
    attachment.put("length", tarball.length);

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("_id", name);
    document.put("name", name);
    document.put("dist-tags", Map.of(distTag, version));
    document.put("versions", Map.of(version, versionManifest));
    document.put("access", "public");
    document.put("_attachments", Map.of(name + "-" + version + ".tgz", attachment));
    return json(document);
  }

  /** The file name this package's tarball is served under: the UNSCOPED name plus the version. */
  public String tarballFile() {
    String unscoped = name.startsWith("@") ? name.substring(name.indexOf('/') + 1) : name;
    return unscoped + "-" + version + ".tgz";
  }

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] json(Map<String, Object> document) {
    try {
      return JSON.writeValueAsBytes(document);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] gzip(byte[] bytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out.toByteArray();
  }

  /** A USTAR archive. Thirty lines beats a new test dependency, as in {@code TinyImage}. */
  private static byte[] tar(Map<String, byte[]> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String path : List.copyOf(entries.keySet()).stream().sorted().toList()) {
      byte[] content = entries.get(path);
      out.writeBytes(header(path, content.length));
      out.writeBytes(content);
      out.writeBytes(new byte[(512 - content.length % 512) % 512]);
    }
    out.writeBytes(new byte[1024]); // two empty blocks terminate the archive
    return out.toByteArray();
  }

  private static byte[] header(String name, int length) {
    byte[] header = new byte[512];
    write(header, 0, name);
    write(header, 100, "0000644"); // mode
    write(header, 108, "0000000"); // uid
    write(header, 116, "0000000"); // gid
    write(header, 124, String.format("%011o", length));
    write(header, 136, String.format("%011o", 0)); // mtime — fixed, so the digest is stable
    Arrays.fill(header, 148, 156, (byte) ' '); // checksum counts as spaces while summing
    header[156] = '0'; // regular file
    write(header, 257, "ustar");
    header[263] = '0';
    header[264] = '0';

    int checksum = 0;
    for (byte b : header) {
      checksum += b & 0xFF;
    }
    write(header, 148, String.format("%06o", checksum));
    header[154] = 0;
    header[155] = ' ';
    return header;
  }

  private static void write(byte[] target, int offset, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, target, offset, bytes.length);
  }
}
