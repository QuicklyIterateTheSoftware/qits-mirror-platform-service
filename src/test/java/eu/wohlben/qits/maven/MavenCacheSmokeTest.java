package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The maven cache, end to end through this service: a {@code maven-metadata.xml} and a jar that
 * nothing here has seen are fetched from upstream, stored and then served from disk.
 *
 * <p>A <b>service</b> smoke test, on the {@code central} row the seeder makes, over the V1 schema on
 * a real PostgreSQL. The protocol's edge cases — TTL expiry, Last-Modified revalidation, serve-stale,
 * snapshot paths — are proved once in qits-registries-maven and are not repeated here.
 *
 * <p>Fixture content is unique per RUN as well as per test. Nothing wipes {@code
 * target/mirror-test-blobs} between runs and blobs dedupe globally, so reusing an earlier run's bytes
 * makes a fetch a blob-store hit and the upstream count comes out one short with nothing in the
 * failure to say why.
 */
@QuarkusTest
@TestProfile(MavenCacheSmokeTest.AgainstTheStubUpstream.class)
class MavenCacheSmokeTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  private static final String GROUP_PATH = "org/example";
  private static final String GROUP_ID = "org.example";

  public static class AgainstTheStubUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // The suite's default for this key is a closed port; opting in is deliberate and explicit.
      return Map.of("qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Inject MirrorRepositorySeeder seeder;

  @BeforeEach
  void seedAndResetUpstream() {
    seeder.ensureDefaults();
    StubMavenRepository.INSTANCE.reset();
  }

  @Test
  void metadataIsFetchedFromUpstreamAndServedWithAChecksumDerivedFromWhatWasServed() {
    String artifactId = "meta-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/maven-metadata.xml";
    StubMavenRepository.INSTANCE.hostMetadata(
        path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, "1.0.0"));

    try (MavenClient maven = client()) {
      HttpResponse<String> served = maven.getText(MirrorRepositorySeeder.MAVEN_CACHE, path);
      assertEquals(200, served.statusCode());
      assertTrue(served.body().contains("<version>1.0.0</version>"), served.body());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests(), "the miss costs one fetch");

      // maven-metadata.xml is the one maven document that mutates, so it is cached with a TTL — an
      // hour by default, which means the second read inside this test asks nobody.
      assertEquals(200, maven.getText(MirrorRepositorySeeder.MAVEN_CACHE, path).statusCode());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests());

      // Its checksum is DERIVED from the document served rather than proxied from upstream: a
      // proxied hash would be upstream's hash of a newer document while this cache still served the
      // old one, and would fail every client that checks.
      String sha1 =
          maven.getText(MirrorRepositorySeeder.MAVEN_CACHE, path + ".sha1").body().trim();
      assertEquals(TinyArtifact.hex(served.body().getBytes(java.nio.charset.StandardCharsets.UTF_8), "SHA-1"), sha1);
    }
  }

  @Test
  void aJarIsFetchedOnceAndThenServedFromDisk() {
    String artifactId = "lib-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    byte[] jar = TinyArtifact.jar(artifactId);
    StubMavenRepository.INSTANCE.hostFile(path, jar);

    try (MavenClient maven = client()) {
      HttpResponse<byte[]> first = maven.get(MirrorRepositorySeeder.MAVEN_CACHE, path);
      assertEquals(200, first.statusCode());
      assertArrayEquals(jar, first.body());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests());

      // Every path but maven-metadata.xml is immutable, so the second resolve is a sendFile off
      // local disk — the whole point of the cache, and what makes a build stop paying Central per
      // run.
      assertArrayEquals(jar, maven.get(MirrorRepositorySeeder.MAVEN_CACHE, path).body());
      assertEquals(
          1,
          StubMavenRepository.INSTANCE.fileRequests(),
          "a cached artifact must never be refetched");
    }
  }

  @Test
  void aDeployToTheCacheIsRefusedByType() {
    // Nothing deploys to this service. The refusal is the repository row's type, not a configuration
    // somebody could turn off.
    String artifactId = "rejected-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    try (MavenClient maven = client()) {
      int status =
          maven
              .put(MirrorRepositorySeeder.MAVEN_CACHE, path, TinyArtifact.jar(artifactId))
              .statusCode();
      assertTrue(status >= 400, "a deploy to a cache must not be accepted; was " + status);
    }
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
