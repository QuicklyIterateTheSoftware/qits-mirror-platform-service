package eu.wohlben.qits.mirror.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The guard on the invalidate door, and — just as load-bearing — the absence of a guard everywhere
 * else.
 *
 * <p>This service had no identity at all until the door landed, and the risk of adding one is not
 * that the door is under-protected. It is that the protection SPREADS. Three wire protocols here are
 * pulled anonymously by dockerd, npm and maven on qits-net, and a 401 on any of them is not a
 * message anyone reads: it is every client on the platform caching "this artifact cannot be had".
 * So the pair of claims below is the whole file — the DELETE refuses a caller without the role, and
 * the listings beside it still answer a caller with no identity whatsoever.
 *
 * <p><b>The dev-user fallback is blanked, which is what makes any of this observable.</b>
 * qits-auth-core ships a {@code %test} dev user carrying {@code qits:admin}, so under an ordinary
 * {@code @QuarkusTest} every request passes every {@code @RolesAllowed} before the annotation is
 * consulted — a guard test written without this profile passes whether the annotation is there or
 * not, which is the worst kind of green. Clearing {@code qits.auth.forward.dev-user} restores the
 * deployed posture: no header, no identity.
 *
 * <p>The happy path is asserted here only far enough to show the guard ADMITS — a {@code 404},
 * because nothing is cached at a made-up path in a suite that pulls nothing. What an admitted call
 * actually does is {@code maven.MavenCacheEvictionTest}'s subject, where there is an upstream to
 * refetch from.
 */
@QuarkusTest
@TestProfile(MirrorEvictionGuardTest.NoDevUser.class)
class MirrorEvictionGuardTest {

  private static final String DOOR = "/mirror/api/repositories/central/entries";
  private static final String SOME_ENTRY = "org/example/nothing/1.0.0/nothing-1.0.0.jar";

  /**
   * The deployed posture: an unauthenticated request is unauthenticated. An empty value reads as
   * ABSENT to the {@code Optional} config property behind the fallback, which is how it is switched
   * off rather than set to an empty name.
   */
  public static class NoDevUser implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.auth.forward.dev-user", "");
    }
  }

  @Test
  void anAnonymousCallerCannotEvictAnything() {
    given().queryParam("path", SOME_ENTRY).when().delete(DOOR).then().statusCode(401);
  }

  @Test
  void anIdentityWithoutTheRoleIsRefused() {
    // A signed-in reader of the explorer page is exactly this caller: known, and not an operator.
    // 403 rather than 401 — the platform's distinction, and the one that tells somebody whether to
    // log in or to ask for a grant.
    given()
        .header("X-Qits-User", "reader")
        .header("X-Qits-Roles", "qits:user")
        .queryParam("path", SOME_ENTRY)
        .when()
        .delete(DOOR)
        .then()
        .statusCode(403);
  }

  @Test
  void anOperatorIsAdmitted() {
    given()
        .header("X-Qits-User", "operator")
        .header("X-Qits-Roles", "qits:admin")
        .queryParam("path", SOME_ENTRY)
        .when()
        .delete(DOOR)
        .then()
        // Past the guard and into the store, which has nothing at that path. Any of 401/403 here
        // would mean the annotation is refusing the role it names.
        .statusCode(404);
  }

  @Test
  void aMachineIsAdmittedToo() {
    // qits:system is the second half of the operator-door idiom: a repair job clears a poisoned
    // entry without a person, on the same route and with the same effect.
    given()
        .header("X-Qits-User", "some-repair-job")
        .header("X-Qits-Roles", "qits:system")
        .queryParam("path", SOME_ENTRY)
        .when()
        .delete(DOOR)
        .then()
        .statusCode(404);
  }

  /**
   * THE PROPERTY THE GUARD MUST NOT COST. The listings the explorer draws are reads of what a cache
   * holds and stay open to a caller with no identity at all — which is also the shape of every
   * request on the three wire protocols beside them.
   *
   * <p>If a {@code quarkus.http.auth.permission.*} block is ever added to this service, this is the
   * test that goes red first, and it should.
   */
  @Test
  void theReadOnlyListingsAreStillAnonymous() {
    given().when().get("/mirror/api/repositories").then().statusCode(200);
    given().when().get("/mirror/api/upstreams").then().statusCode(200);
  }
}
