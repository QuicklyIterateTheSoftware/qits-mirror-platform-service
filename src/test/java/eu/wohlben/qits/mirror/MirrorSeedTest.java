package eu.wohlben.qits.mirror;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import eu.wohlben.qits.artifacts.control.OciMirrorUpstreams;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What this service <b>is</b>: three cache types, five rows, and no way to be anything else.
 *
 * <p>Everything here is a claim about the boundary rather than about a protocol — the protocol round
 * trips are the three smoke suites. It runs on the default test profile, so it is also the suite that
 * proves the shipped configuration boots at all: the V1 baseline applies to a real PostgreSQL, the
 * persistence unit resolves every entity the library jars map, and readiness answers under the
 * segment the gateway would route.
 */
@QuarkusTest
class MirrorSeedTest {

  @Inject RepositoryTypeProfiles repositoryTypes;
  @Inject ArtifactRepositoryService repositoryService;
  @Inject ArtifactRepositoryRepository repositories;
  @Inject MirrorRepositorySeeder seeder;
  @Inject OciMirrorUpstreams upstreams;

  @Test
  void theServiceRegistersTheThreeCacheTypesAndNothingElse() {
    // The load-bearing assertion of the whole service. Each qits-registries module ships BOTH sides
    // of its format, so NpmPackagesProfile, MavenPackagesProfile and OciImagesProfile are on this
    // classpath — as are the blob core's two CI profiles. quarkus.arc.exclude-types is what keeps
    // them out of the bean archive, and this is what would notice if that line were dropped or
    // misspelled: a misspelling is silent, and the first symptom would be a hosted repository
    // someone created in a service that cannot serve one.
    assertEquals(
        Set.of(NpmProxyProfile.KEY, MavenProxyProfile.KEY, OciMirrorProfile.KEY),
        repositoryTypes.keys());
  }

  @Test
  void aHostedTypeIsRefusedByName() {
    // The other side of the same coin, at the API boundary: not "there is no row of that type" but
    // "that type does not exist here", answered before anything is written. The check constraint in
    // V1 would refuse the row too — this is what keeps that from being the thing a user meets.
    BadRequestException refused =
        assertThrows(
            BadRequestException.class, () -> repositoryService.ensure("npm", "NPM_PACKAGES"));
    assertTrue(refused.getMessage().contains("NPM_PACKAGES"), refused.getMessage());
    assertTrue(refused.getMessage().contains(NpmProxyProfile.KEY), refused.getMessage());
  }

  @Test
  void theMigrationPrefillsTheThreeMirrorNamespacesAndTheirUpstreams() {
    // These come from V1 rather than from the seeder: three public registries with static domains
    // are lineage material, and each is a PAIR — a namespace with no upstream is a namespace nothing
    // can be fetched into.
    for (String slug : List.of("hub", "quay", "redhat")) {
      ArtifactRepository row = repositories.findById(slug);
      assertNotNull(row, "V1 must prefill the " + slug + " namespace");
      assertEquals(OciMirrorProfile.KEY, row.type);
    }
    Map<String, String> bySlug =
        upstreams.list().stream()
            .collect(Collectors.toMap(MirrorUpstreamSummary::slug, MirrorUpstreamSummary::domain));
    assertEquals(
        Map.of(
            "hub", "docker.io",
            "quay", "quay.io",
            "redhat", "registry.access.redhat.com"),
        bySlug);
  }

  @Test
  void theSeederAddsTheTwoCacheRootsAndIsIdempotent() {
    // The suite never self-seeds (see MirrorStartupSeed), so this is where the seeder runs. Twice,
    // because "purely additive" is the property a boot depends on.
    seeder.ensureDefaults();
    seeder.ensureDefaults();

    ArtifactRepository npm = repositories.findById(MirrorRepositorySeeder.NPM_CACHE);
    assertNotNull(npm, "the npmjs cache root must exist after a seed");
    assertEquals(NpmProxyProfile.KEY, npm.type);

    ArtifactRepository maven = repositories.findById(MirrorRepositorySeeder.MAVEN_CACHE);
    assertNotNull(maven, "the central cache root must exist after a seed");
    assertEquals(MavenProxyProfile.KEY, maven.type);

    // And nothing hosted appeared alongside them: every row this service owns is a cache.
    Set<String> types =
        repositories.listAll().stream().map(row -> row.type).collect(Collectors.toSet());
    assertEquals(
        Set.of(NpmProxyProfile.KEY, MavenProxyProfile.KEY, OciMirrorProfile.KEY), types);
  }

  @Test
  void theSuiteNeverSelfSeeds() {
    assertFalse(MirrorStartupSeed.shouldSeed(LaunchMode.TEST, true));
    assertTrue(MirrorStartupSeed.shouldSeed(LaunchMode.NORMAL, true));
    assertTrue(MirrorStartupSeed.shouldSeed(LaunchMode.DEVELOPMENT, true));
    assertFalse(MirrorStartupSeed.shouldSeed(LaunchMode.NORMAL, false));
  }

  @Test
  void readinessAnswersUnderTheSegmentTheGatewayWouldRoute() {
    // quarkus.http.non-application-root-path is what qits-cd's health gate curls, and it is the one
    // surface of this service that is not a third-party wire protocol.
    given().when().get("/mirror/q/health/ready").then().statusCode(200);
  }
}
