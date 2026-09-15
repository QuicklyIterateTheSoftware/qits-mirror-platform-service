package eu.wohlben.qits.mirror.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The audience this service answers to is the platform's one machine audience, {@code
 * qits-platform}: qits-platform-idp puts it on every token it mints, so a caller addressed to it —
 * which is every caller there is — passes this service's OIDC check, and a token from anywhere else
 * does not.
 *
 * <p>This is the first test in the repository that turns the machine gate ON. The README says the
 * rollout gate stays off in normal operation, so no in-repo pattern exists to follow for a real
 * bearer token; this test brings its own signing key rather than a running qits-platform-idp, using
 * {@code quarkus.oidc.public-key} — a fixed key validated with no network call and no JWKS fetch,
 * which is what keeps this test clone-alone like the rest of the suite.
 */
@QuarkusTest
@TestProfile(MachinePlatformAudienceTest.GateOnWithAFixedSigningKey.class)
class MachinePlatformAudienceTest {

  private static final String DOOR = "/mirror/api/repositories/central/entries";
  private static final String SOME_ENTRY = "org/example/nothing/1.0.0/nothing-1.0.0.jar";
  private static final String ISSUER = "https://qits-mirror-test-issuer";

  /**
   * A FIXED RSA test key pair, test-only, checked in as plain base64 — nothing this key signs is
   * ever accepted outside this test file. It is fixed rather than freshly generated because {@link
   * QuarkusTestProfile#getConfigOverrides()} runs in a different call than the test methods (Quarkus
   * may invoke it more than once while deciding whether a restart is needed), so a key generated
   * there and stashed for the test methods to reread can end up mismatched — a fixed pair sidesteps
   * that entirely: both sides always agree.
   */
  private static final String PUBLIC_KEY_BASE64 =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA8V4tPb/z/19cD4Xrs4bmCz0dVzQHVrfDMwYvIeiiQzRx1eZETtZ95BrUSpEgalMVifDoWlMuiKAbqyhnEHUDJqrzdhYKFAaFHXS6UkEQVqh1NnpgTtZTIzQS+hHH7rOR64Faw7SNn6cyINOk58lYif0iWqRmqPqfPHPvqCqjvI6tOG6BudJTmcFgaEJvhiAt9gKL31e/7Isw1MmRayBAhPkHLnUPehj2uUhqHrmPqYhAJKMmrMVavTSkb6thGlsDu1pMIqQRJlVKMIpzPjD28Mi1u47T9c/aaH0ydfZpjmB+tAf2SMQJszaIqQMIVe+993VDe0pNvUwkKCYN1Je0rQIDAQAB";
  private static final String PRIVATE_KEY_BASE64 =
      "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDxXi09v/P/X1wPheuzhuYLPR1XNAdWt8MzBi8h6KJDNHHV5kRO1n3kGtRKkSBqUxWJ8OhaUy6IoBurKGcQdQMmqvN2FgoUBoUddLpSQRBWqHU2emBO1lMjNBL6Ecfus5HrgVrDtI2fpzIg06TnyViJ/SJapGao+p88c++oKqO8jq04boG50lOZwWBoQm+GIC32AovfV7/sizDUyZFrIECE+QcudQ96GPa5SGoeuY+piEAkoyasxVq9NKRvq2EaWwO7WkwipBEmVUowinM+MPbwyLW7jtP1z9pofTJ19mmOYH60B/ZIxAmzNoipAwhV7733dUN7Sk29TCQoJg3Ul7StAgMBAAECggEAUIsyQqhi9kVulZmFcWejLX3r5BUvG79/qm+2W7TjLNT75bqM8df+s5dGX0m1jPWDuv/CsZ2PajUvdnqpheLeNdGXI8EwBMW2NwGkSV1/48McNh05NSWtIZK3gG5djuiqWXHVhXgjSHMFNdIadeSJIKbxh1jUDzBu4b/4aeAsuncmKj5GC26p4jS29GCXbNNa/LZz+B9OLuzoMSHf6XtT2oM7ohcJJJP+vP+gvrVB51MP7qgUrQ0i/NleybSl/cODlTdGYHFH75fDbX2PkO6+u07SyE6lov1LVqD/lD1aVx8pZIucLpE3+iQaJ0maYLxOwaFaa+XIIgnxTPK1qqVDawKBgQD1utLu0IAcZD+KG9nCp2v9tnDj/RHVrVbYTAIKNyfiZGSQahtUK0ufJxFqDMYgi1rhblVVGro+JyhnMP4jVcqOIrYCTPi+MXhBKK9i7JjkVkukQM2Y/7JmOs3s0fB9AVFLtb+ObrdVj+Jkx0T0C+S2TqdSdq6614cwpyHhH4EVWwKBgQD7dK7JG1rry8F9yjLpsDqCRSLCPpTy8oxIxx+sG3MX3XU2rggLvi5io5l6c0jclu3Gc8SfbVkQ5Gb5oxh+TY0l/n7ep172cnDfmdPCN7t2loOHYPk+REUG/NaYYWCAqLDeKi92u82RYpd8bP9fTUiOQl8fP0NaSla+MnDlkqsUlwKBgQCihmiQz5KMNLnZzMXgev9wFUThZjZvj3BLKyqGOyjEEgZ5hdSBfQlNQHfCHqTETadURrdlzIG2LgwJBFxyXoHUNJ0RNHuov+t7nT8zPK17CKCLCNlzAluqReaBZfGbnE16t8D0ocHcc4gXg7zVdTBWa/s7DYhPx9GAmmpMDZxA1QKBgQDQ2+st6YXMoxJVTDSbvB/tAkXLt7V6UhJJhPN+vdIMOYrZXxYZwt6+WYrqlYUojEh1dMUVcvrut/f8VAbNKdcaftecUsLiys2MPDYSdL5F0uDu+pIrsQLwueP477D7TKKT9dA0u+sUXiLbW6GF5Chm2SQvTRnBjkuccUMqvHdTgwKBgQCpVvBDnocVdb6o5PG9HBXl6Pdhvmq90VJIoGZDWbQcAhxzoGNDOiFBEtKwVqsHWyzQoU/E26ROb+Am9723AEMvSMb1qUrVr2EaAzT/1CwYRhdJM5dBkWSv14n2SF+v8j7I5SjUzg8RQKr3Zuy+RS4dsgvvO3elhpc8+dCBbiFRBA==";

  /**
   * Turns the gate on ({@code qits.auth.machine.required=true}, which {@code
   * quarkus.oidc.tenant-enabled} follows) and points token validation at the fixed test key instead
   * of a JWKS endpoint. The dev user is blanked, as every guard test here does (see {@link
   * MirrorEvictionGuardTest.NoDevUser}): a signed-in {@code %test} fallback would make every request
   * pass the guard whether the bearer is read at all.
   */
  public static class GateOnWithAFixedSigningKey implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.machine.required", "true",
          "quarkus.oidc.public-key", PUBLIC_KEY_BASE64,
          "quarkus.oidc.token.issuer", ISSUER,
          "qits.auth.forward.dev-user", "",
          // BLANKED, NOT JUST THE PUBLIC KEY ADDED: quarkus-oidc only takes the fully offline path
          // (no HTTP client, no connection attempt at all) when auth-server-url is ALSO absent —
          // with it set, the shipped jwks-path still makes it build a remote client and try to
          // reach qits-platform-idp, so every request 503s "retry" against an idp this test never
          // starts. Blanking the url is what selects the local-key-only path.
          "quarkus.oidc.auth-server-url", "");
    }
  }

  private static PrivateKey signingPrivateKey() {
    try {
      byte[] encoded = Base64.getDecoder().decode(PRIVATE_KEY_BASE64);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not load the fixed test signing key", e);
    }
  }

  private static String token(String... audience) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject("some-repair-job")
        .audience(Set.of(audience))
        .groups(Set.of("qits:system"))
        .sign(signingPrivateKey());
  }

  @Test
  void aTokenAddressedToThePlatformAudienceIsAccepted() {
    given()
        .header("Authorization", "Bearer " + token("qits-platform"))
        .queryParam("path", SOME_ENTRY)
        .when()
        .delete(DOOR)
        .then()
        // Past the guard and into the store, which has nothing at that path — the same "admitted"
        // shape MirrorEvictionGuardTest uses. 401 here would mean qits-platform was not accepted.
        .statusCode(404);
  }

  @Test
  void aTokenForAnAudienceThatIsNotThisPlatformIsRejected() {
    // The negative control: without it, a broken audience check would pass this test file too. The
    // audience is deliberately not a sibling service's — a peer on this platform carries
    // qits-platform as well and is admitted here, with its roles deciding what it may do. What this
    // asserts is the outer edge: a token addressed to something that is not this platform at all.
    given()
        .header("Authorization", "Bearer " + token("some-other-platform"))
        .queryParam("path", SOME_ENTRY)
        .when()
        .delete(DOOR)
        .then()
        .statusCode(401);
  }
}
