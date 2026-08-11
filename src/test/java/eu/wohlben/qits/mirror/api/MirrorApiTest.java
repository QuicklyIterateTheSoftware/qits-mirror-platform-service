package eu.wohlben.qits.mirror.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin API at {@code /mirror/api} — the explorer's whole server side.
 *
 * <p>Two claims per resource, and the second is the one worth the file. The happy path says the
 * listing names what this service holds; the failure path says a database that cannot be read
 * reaches the caller as a <b>500</b> rather than as an empty list. The second is not a hypothetical:
 * qits-githost swallowed a {@code JDBCConnectionException} from a catalog read on 2026-08-11 and
 * answered "no such repository" for one that exists, and every caller downstream took it as fact.
 * Here the same mistake would be an explorer page reporting a mirror that caches nothing, and — far
 * worse if the habit spread to the wire routes — a "no such artifact" every client caches.
 *
 * <p>Quinoa is disabled in tests, so nothing here says anything about {@code /mirror/}. What the SPA
 * is served as is provable only against the packaged artifact.
 */
@QuarkusTest
class MirrorApiTest {

  @Inject MirrorRepositorySeeder seeder;

  /**
   * The suite never self-seeds (see {@code MirrorStartupSeed}), so the two cache roots have to be
   * ensured here. Idempotent, and the three OCI namespaces come from the V1 prefill either way.
   */
  @BeforeEach
  void seed() {
    seeder.ensureDefaults();
  }

  @Test
  void theRepositoriesListingNamesEveryCacheRootAndWhatItFronts() {
    given()
        .when()
        .get("/mirror/api/repositories")
        .then()
        .statusCode(200)
        .contentType("application/json")
        // Sorted by name, which is the promise that keeps a table from reordering under a cursor.
        .body("repositories.name", contains("central", "hub", "npmjs", "quay", "redhat"))
        // The kebab wire form, never the stored screaming-snake key, and only the three this
        // service registers.
        .body(
            "repositories.type",
            contains("maven-proxy", "oci-mirror", "npm-proxy", "oci-mirror", "oci-mirror"))
        // What each root is a cache OF: a config key for the two package caches — pointed at a
        // closed port by the test config, which is the value under test — and the registry domain
        // for an OCI namespace, read from its upstream row.
        .body("repositories.find { it.name == 'npmjs' }.upstream", is("http://localhost:1"))
        .body("repositories.find { it.name == 'central' }.upstream", is("http://localhost:1"))
        .body("repositories.find { it.name == 'quay' }.upstream", is("quay.io"))
        .body("repositories.find { it.name == 'hub' }.upstream", is("docker.io"));
  }

  @Test
  void theUpstreamsListingNamesTheRegistriesThisServiceFronts() {
    given()
        .when()
        .get("/mirror/api/upstreams")
        .then()
        .statusCode(200)
        .contentType("application/json")
        // `host` is the field the client is promised: the registry a docker client names.
        .body("upstreams.host", hasItems("docker.io", "quay.io", "registry.access.redhat.com"))
        .body("upstreams.namespace", hasItems("hub", "quay", "redhat"))
        // Zero is the normal state of a fresh upstream, so the claim is only that the count is a
        // real one — the smoke suites are where a pull-through is proved.
        .body("upstreams.cachedImages", everyItem(greaterThanOrEqualTo(0)));
  }

  @Test
  void aFailedRepositoryReadIsA500AndNotAnEmptyListing() {
    QuarkusMock.installMockForType(new UnreadableStore(), MirrorExplorer.class);
    given().when().get("/mirror/api/repositories").then().statusCode(500);
  }

  @Test
  void aFailedUpstreamReadIsA500AndNotAnEmptyListing() {
    QuarkusMock.installMockForType(new UnreadableStore(), MirrorExplorer.class);
    given().when().get("/mirror/api/upstreams").then().statusCode(500);
  }

  @Test
  void aMistypedApiPathIsA404() {
    // Cheap here and load-bearing later: once Quinoa is on in a packaged process, this is the
    // assertion that /api stays out of the SPA fallback's reach.
    given().when().get("/mirror/api/nope").then().statusCode(404);
  }

  /**
   * A store whose connection died mid-read.
   *
   * <p>{@link JDBCConnectionException} is the exact type this platform's incident produced and the
   * one {@code DbRetry} recognises. Thrown straight out of the seam rather than through the retry, so
   * the claim under test is the resource's — that it lets a failure through — and the test costs no
   * retry deadline.
   */
  static class UnreadableStore extends MirrorExplorer {

    @Override
    public List<MirrorRepositoryRow> repositories() {
      throw failure();
    }

    @Override
    public List<OciUpstreamRow> upstreams() {
      throw failure();
    }

    private static JDBCConnectionException failure() {
      return new JDBCConnectionException(
          "the connection attempt failed", new SQLException("connection refused", "08006"));
    }
  }
}
