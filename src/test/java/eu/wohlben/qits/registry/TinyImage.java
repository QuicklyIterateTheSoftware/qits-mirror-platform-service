package eu.wohlben.qits.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * A minimal but real OCI image, built in memory: one gzipped-tar layer, a JSON config blob, and a
 * manifest naming both by digest.
 *
 * <p>Synthesised rather than committed, following {@code ArtifactsTestMedia} — this repo commits no
 * binary fixtures. Synthesising also makes the digests deterministic per salt, so two pushes of the
 * same salt really are the same image and a deduplication assertion means something.
 *
 * <p>Valid enough for a real runtime rather than merely well-formed: the layer is a genuine tar with
 * one regular file (a hand-written 512-byte USTAR header, so no new test dependency) under gzip, and
 * the config carries the {@code rootfs.diff_ids} the spec requires. That is what lets the same bytes
 * be inspected with {@code skopeo} during manual acceptance.
 */
public record TinyImage(Blob layer, Blob config, byte[] manifest, String manifestMediaType) {

  public record Blob(String digest, byte[] bytes, String mediaType) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  public static final String LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip";
  public static final String CONFIG_TYPE = "application/vnd.oci.image.config.v1+json";
  public static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  public static final String INDEX_TYPE = "application/vnd.oci.image.index.v1+json";

  /** A single-architecture image whose content is determined entirely by {@code salt}. */
  public static TinyImage of(String salt) {
    byte[] content = ("hello from " + salt + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] tar = tar("hello.txt", content);
    byte[] gzipped = gzip(tar);
    Blob layer = new Blob(digest(gzipped), gzipped, LAYER_TYPE);

    Map<String, Object> configDocument =
        Map.of(
            "architecture",
            "amd64",
            "os",
            "linux",
            "config",
            Map.of("Cmd", List.of("/bin/sh")),
            "rootfs",
            Map.of("type", "layers", "diff_ids", List.of(digest(tar))));
    byte[] configBytes = json(configDocument);
    Blob config = new Blob(digest(configBytes), configBytes, CONFIG_TYPE);

    byte[] manifest =
        json(
            ordered(
                "schemaVersion", 2,
                "mediaType", MANIFEST_TYPE,
                "config", descriptor(config),
                "layers", List.of(descriptor(layer))));
    return new TinyImage(layer, config, manifest, MANIFEST_TYPE);
  }

  /** The multi-arch case: an index over already-pushed children, as buildx and podman send it. */
  public static byte[] index(TinyImage... children) {
    List<Map<String, Object>> descriptors = new ArrayList<>();
    String[] architectures = {"amd64", "arm64", "riscv64"};
    for (int i = 0; i < children.length; i++) {
      TinyImage child = children[i];
      descriptors.add(
          ordered(
              "mediaType", child.manifestMediaType(),
              "digest", digest(child.manifest()),
              "size", child.manifest().length,
              "platform", Map.of("architecture", architectures[i % architectures.length], "os", "linux")));
    }
    return json(ordered("schemaVersion", 2, "mediaType", INDEX_TYPE, "manifests", descriptors));
  }

  public String manifestDigest() {
    return digest(manifest);
  }

  private static Map<String, Object> descriptor(Blob blob) {
    return ordered(
        "mediaType", blob.mediaType(), "digest", blob.digest(), "size", blob.bytes().length);
  }

  /** {@code sha256:<hex>} — the wire form, which is what a manifest carries. */
  public static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

  /** Insertion-ordered, so a manifest's bytes — and therefore its digest — are reproducible. */
  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
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

  /** A one-file USTAR archive. Thirty lines beats a new test dependency for this. */
  private static byte[] tar(String name, byte[] content) {
    byte[] header = new byte[512];
    write(header, 0, name);
    write(header, 100, "0000644"); // mode
    write(header, 108, "0000000"); // uid
    write(header, 116, "0000000"); // gid
    write(header, 124, String.format("%011o", content.length));
    write(header, 136, String.format("%011o", 0)); // mtime — fixed, so the digest is stable
    Arrays.fill(header, 148, 156, (byte) ' '); // checksum field counts as spaces while summing
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

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(header);
    out.writeBytes(content);
    out.writeBytes(new byte[(512 - content.length % 512) % 512]); // pad to a block
    out.writeBytes(new byte[1024]); // two empty blocks terminate the archive
    return out.toByteArray();
  }

  private static void write(byte[] target, int offset, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, target, offset, bytes.length);
  }
}
