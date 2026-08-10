package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The npm cache, end to end through this service: a packument nobody here has seen is fetched from
 * upstream, rewritten to point back at this host, and served — and the tarball it names is then
 * pulled through the same way.
 *
 * <p>This is a <b>service</b> smoke test, not a second copy of the npm library's suite. What it adds
 * over that suite is everything the library deliberately does not own: the seeded {@code npmjs} row
 * of the type this service registers, the V1 schema on a real PostgreSQL, and the routes mounted in
 * this application's Router. The protocol's edge cases — TTL expiry, serve-stale, scoped names —
 * stay where they are proved once, in qits-registries-npm.
 *
 * <p>The upstream is an in-process stub. A test that reached registry.npmjs.org would fail on an
 * aeroplane and pass in CI for reasons unrelated to this code — and would pass <em>wrongly</em>, since
 * npmjs answers 404 for a synthetic package exactly as an unconfigured cache would.
 */
@QuarkusTest
@TestProfile(NpmCacheSmokeTest.AgainstTheStubUpstream.class)
class NpmCacheSmokeTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /** New every JVM, so no package named here has ever been staged under {@code target/} before. */
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  public static class AgainstTheStubUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // The suite's default for this key is a closed port; opting in is deliberate and explicit.
      return Map.of("qits.artifacts.npm.proxy.upstream", StubNpmRegistry.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  /** The seeder is what a real boot runs; calling it here is what proves the row it makes works. */
  @Inject MirrorRepositorySeeder seeder;

  @BeforeEach
  void seedAndResetUpstream() {
    seeder.ensureDefaults();
    StubNpmRegistry.INSTANCE.reset();
  }

  @Test
  void thePackumentIsFetchedOnceRewrittenAndServedFromCacheAfterwards() {
    TinyPackage subject = TinyPackage.of("cached-" + RUN + "-" + UNIQUE.incrementAndGet(), "1.2.3");
    StubNpmRegistry.INSTANCE.hostPackage(subject);

    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      JsonNode packument = npm.packumentJson(MirrorRepositorySeeder.NPM_CACHE, subject.name());
      assertEquals("1.2.3", packument.path("dist-tags").path("latest").asText());
      assertEquals(
          "stub",
          packument.path("_upstream").asText(),
          "upstream's document is served through, not re-synthesised from what was parsed");
      assertEquals(1, StubNpmRegistry.INSTANCE.packumentRequests(), "the miss costs one fetch");

      // The one thing a cache MUST rewrite: a tarball url is a property of the request's authority.
      // An install that followed upstream's url would bypass this service entirely.
      String tarballUrl = NpmClient.tarballUrl(packument, "1.2.3");
      assertTrue(
          tarballUrl.contains("/artifacts/npm/" + MirrorRepositorySeeder.NPM_CACHE + "/"),
          tarballUrl);

      // The tarball itself, byte for byte, and upstream's own hashes untouched beside it — that is
      // what the installing client verifies end to end.
      assertArrayEquals(subject.tarball(), npm.tarball(tarballUrl).body());
      assertEquals(
          subject.integrity(),
          packument.path("versions").path("1.2.3").path("dist").path("integrity").asText());
      assertEquals(1, StubNpmRegistry.INSTANCE.tarballRequests(), "one tarball fetch, not two");

      // And now the claim that makes it a cache rather than a proxy: the shipped TTL is five
      // minutes, so nothing above is asked upstream again.
      npm.packumentJson(MirrorRepositorySeeder.NPM_CACHE, subject.name());
      assertArrayEquals(subject.tarball(), npm.tarball(tarballUrl).body());
      assertEquals(1, StubNpmRegistry.INSTANCE.packumentRequests());
      assertEquals(1, StubNpmRegistry.INSTANCE.tarballRequests());
    }
  }

  @Test
  void aPublishToTheCacheIsRefusedByType() {
    // The posture the whole service rests on, checked on the one wire that could break it. Nothing
    // publishes to this deployment — there is no hosted type registered at all — and the refusal is
    // the repository row's type, not a configuration somebody could turn off.
    TinyPackage subject = TinyPackage.of("rejected-" + RUN + "-" + UNIQUE.incrementAndGet(), "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      int status =
          npm.publish(
                  MirrorRepositorySeeder.NPM_CACHE, subject.name(), subject.publishDocument("latest"))
              .statusCode();
      assertTrue(status >= 400, "a publish to a cache must not be accepted; was " + status);
    }
  }
}
