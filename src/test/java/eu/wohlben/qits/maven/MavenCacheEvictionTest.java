package eu.wohlben.qits.maven;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The invalidate door, proved against the thing it exists to undo: a cached entry that is served
 * from here and never fetched again.
 *
 * <p>Every claim in this file is about the pair, never about the DELETE on its own. A door that
 * removed a row would be trivially testable and would prove nothing worth having — the property is
 * that the cache goes back to being a cache: the entry is gone, the very next resolve pays upstream
 * for it once, and what comes back is upstream's bytes rather than a repair's approximation of them.
 * That is what makes eviction safe to hand an operator, and it is why the assertions here count
 * upstream requests as closely as the smoke test does.
 *
 * <p><b>Why this rides {@code MavenCacheSmokeTest}'s profile rather than declaring its own.</b> Two
 * profiles is two Quarkus restarts and two embedded postgres lifetimes for one upstream address, and
 * the address is all this needs. Sharing it also puts both classes in one profile group, which is
 * the ordering the suite's class orderer works in.
 *
 * <p>The guard is a separate file — {@code mirror.api.MirrorEvictionGuardTest} — because proving a
 * refusal needs the {@code %test} dev-user blanked, and that IS a different profile.
 */
@QuarkusTest
@TestProfile(MavenCacheSmokeTest.AgainstTheStubUpstream.class)
class MavenCacheEvictionTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  private static final String GROUP_PATH = "org/example";
  private static final String GROUP_ID = "org.example";
  private static final String CACHE = MirrorRepositorySeeder.MAVEN_CACHE;
  private static final String DOOR = "/mirror/api/repositories/{repository}/entries";

  @TestHTTPResource("/")
  URL root;

  @Inject MirrorRepositorySeeder seeder;

  @BeforeEach
  void seedAndResetUpstream() {
    seeder.ensureDefaults();
    StubMavenRepository.INSTANCE.reset();
  }

  /**
   * The whole point, in one test: cached, evicted, fetched again, and byte-identical to what
   * upstream holds.
   *
   * <p>The byte comparison is not ceremony. An eviction that dropped the row but left the blob
   * bound, or one that refetched through a path that re-derived rather than re-fetched, would pass a
   * status-code test and hand every client on the platform bytes that are not upstream's. The
   * upstream counter is the other half: it is what says the second read genuinely left the process.
   */
  @Test
  void theDoorEvictsACachedFileAndTheNextReadRefetchesItByteForByte() {
    String artifactId = "evicted-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    byte[] jar = TinyArtifact.jar(artifactId);
    StubMavenRepository.INSTANCE.hostFile(path, jar);

    try (MavenClient maven = client()) {
      assertArrayEquals(jar, maven.get(CACHE, path).body());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests(), "the miss costs one fetch");
      assertArrayEquals(jar, maven.get(CACHE, path).body());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests(), "and the hit costs none");

      operator()
          .queryParam("path", path)
          .when()
          .delete(DOOR, CACHE)
          .then()
          .statusCode(200)
          .body("repository", equalTo(CACHE))
          .body("path", equalTo(path))
          // A jar is a `maven_artifact` row, which the door calls a file — the other kind is the
          // TTL'd metadata document below.
          .body("kind", equalTo("file"))
          // ONE row for one primary key is the healthy answer. The number is reported rather than
          // swallowed because more than one is the fault this door was built for: a table holding
          // two heap tuples under `maven_artifact_pkey` is what made a coordinate answer 500 to
          // every request on 2026-09-05, and an operator has to be able to see that they cleared
          // two rows and not one.
          .body("rowsRemoved", equalTo(1));

      HttpResponse<byte[]> refetched = maven.get(CACHE, path);
      assertEquals(200, refetched.statusCode(), "an evicted entry must be fetchable again");
      assertArrayEquals(jar, refetched.body(), "and must come back as upstream's bytes");
      assertEquals(
          2,
          StubMavenRepository.INSTANCE.fileRequests(),
          "the read after an eviction is a miss and must reach upstream");

      // And the cache is a cache again rather than permanently cold: the read after the refetch
      // costs nothing. An eviction that left the row unwritable would show up here and nowhere else.
      assertArrayEquals(jar, maven.get(CACHE, path).body());
      assertEquals(2, StubMavenRepository.INSTANCE.fileRequests());
    }
  }

  /**
   * The other kind of cached thing: {@code maven-metadata.xml} is a TTL'd document in a table of its
   * own, not a blob, and evicting it means the next read revalidates upstream instead of waiting out
   * the hour.
   *
   * <p>Worth its own test because the door has to route to the right table off the filename alone. A
   * door that sent every path to {@code maven_artifact} would answer 404 here and leave the one
   * cached document a stale resolve actually reads from exactly where it was.
   */
  @Test
  void evictingACachedMetadataDocumentMakesTheNextReadRevalidate() {
    String artifactId = "meta-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/maven-metadata.xml";
    StubMavenRepository.INSTANCE.hostMetadata(
        path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, "1.0.0"));

    try (MavenClient maven = client()) {
      assertEquals(200, maven.getText(CACHE, path).statusCode());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests());
      assertEquals(200, maven.getText(CACHE, path).statusCode());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests(), "inside the TTL, no fetch");

      operator()
          .queryParam("path", path)
          .when()
          .delete(DOOR, CACHE)
          .then()
          .statusCode(200)
          .body("kind", equalTo("metadata"))
          .body("path", equalTo(path));

      assertEquals(200, maven.getText(CACHE, path).statusCode());
      assertEquals(
          2,
          StubMavenRepository.INSTANCE.metadataRequests(),
          "an evicted document is revalidated on the next read, TTL or no TTL");
    }
  }

  /**
   * A metadata checksum sibling names no cached row — the proxy derives it from the document it is
   * serving — so the door resolves it to the document rather than answering "nothing cached there".
   *
   * <p>This is the one place the door knows something about maven the caller should not have to.
   * Somebody clearing a bad checksum pastes the path their build failed on, and the honest thing is
   * to evict what actually backs it.
   */
  @Test
  void evictingAMetadataChecksumEvictsTheDocumentItIsDerivedFrom() {
    String artifactId = "sum-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/maven-metadata.xml";
    StubMavenRepository.INSTANCE.hostMetadata(
        path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, "1.0.0"));

    try (MavenClient maven = client()) {
      assertEquals(200, maven.getText(CACHE, path + ".sha1").statusCode());

      operator()
          .queryParam("path", path + ".sha1")
          .when()
          .delete(DOOR, CACHE)
          .then()
          .statusCode(200)
          .body("kind", equalTo("metadata"))
          .body("path", equalTo(path));
    }
  }

  /**
   * An upstream 404 is passed through and REMEMBERED NOWHERE, and the door does not turn that into a
   * loop.
   *
   * <p>The failure this rules out is the one a self-healing cache invites: treat every unhappy read
   * as a poisoned entry, and a path upstream genuinely does not have becomes an eviction on every
   * request forever — writes and log lines with nothing to heal. A refusal that came from upstream
   * is upstream's answer and not a fault in the store, so nothing is cached, nothing is evicted, and
   * the door says plainly that there was nothing there.
   */
  @Test
  void anUpstreamRefusalCachesNothingAndIsNotAnEvictionLoop() {
    String artifactId = "absent-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";

    try (MavenClient maven = client()) {
      assertEquals(404, maven.get(CACHE, path).statusCode());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests());

      // The no was not remembered: the second ask reaches upstream too. (The mirror's own rule —
      // a wrong "there is no such thing" is cached by every client that asked.)
      assertEquals(404, maven.get(CACHE, path).statusCode());
      assertEquals(2, StubMavenRepository.INSTANCE.fileRequests());

      operator()
          .queryParam("path", path)
          .when()
          .delete(DOOR, CACHE)
          .then()
          .statusCode(404)
          .body(containsString("nothing cached at " + path));

      // And the door changed nothing: the read after it behaves exactly as the two before it.
      assertEquals(404, maven.get(CACHE, path).statusCode());
      assertEquals(3, StubMavenRepository.INSTANCE.fileRequests());
    }
  }

  /**
   * Eviction is a cache operation, and the door checks the type rather than assuming it. {@code hub}
   * is an OCI mirror: also a cache, also evictable in principle, and not spelled by a path — so the
   * refusal names what it is instead of pretending the entry is missing.
   */
  @Test
  void aCacheRootThatIsNotAMavenProxyIsRefusedByTypeAndNotByAbsence() {
    operator()
        .queryParam("path", "some/thing")
        .when()
        .delete(DOOR, "hub")
        .then()
        .statusCode(409)
        .body(containsString("an oci-mirror"));
  }

  @Test
  void anUnknownCacheRootIsA404ThatNamesTheListing() {
    operator()
        .queryParam("path", "some/thing")
        .when()
        .delete(DOOR, "nope")
        .then()
        .statusCode(404)
        .body(containsString("/mirror/api/repositories"));
  }

  @Test
  void aDoorCallWithNoPathIsA400AndNeverAWholeRepository() {
    // The one refusal that is about blast radius rather than about bookkeeping: an empty ?path=
    // must not be read as "everything".
    operator().when().delete(DOOR, CACHE).then().statusCode(400).body(containsString("?path="));
  }

  /**
   * The suite's {@code %test} dev-user already carries {@code qits:admin}, so these headers are
   * belt and braces here — they are the identity the deployed door actually sees, and spelling them
   * keeps this file honest if the fallback ever changes. What the guard REFUSES is proved in {@code
   * MirrorEvictionGuardTest}, which blanks that fallback.
   */
  private static io.restassured.specification.RequestSpecification operator() {
    return given().header("X-Qits-User", "eviction-test").header("X-Qits-Roles", "qits:admin");
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
