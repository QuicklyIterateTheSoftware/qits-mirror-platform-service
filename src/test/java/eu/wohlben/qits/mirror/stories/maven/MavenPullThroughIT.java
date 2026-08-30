package eu.wohlben.qits.mirror.stories.maven;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.mirror.stories.support.RecordingUpstream;
import eu.wohlben.qits.mirror.stories.support.StoryNetwork;
import eu.wohlben.qits.mirror.stories.support.StoryProfile;
import eu.wohlben.qits.mirror.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The second plane, and the second time the crown-jewel negative has to hold: a JVM build
 * resolving through the mirror instead of through Maven Central.</b>
 *
 * <p>maven is not npm and the differences are the story. A maven repository has <em>two</em> kinds
 * of name under one URL space and the cache treats them oppositely, which is a decision this pair
 * makes visible:
 *
 * <ul>
 *   <li>Everything under a release version — the jar, the pom, and <b>upstream's own {@code .sha1}
 *       beside them</b> — is an immutable path. Fetched once, cached forever, and never revalidated.
 *   <li>{@code maven-metadata.xml} <b>mutates</b>: a version released upstream changes it with
 *       nothing here changing. So it is the one maven document with a TTL.
 * </ul>
 *
 * <p>And one asymmetry that is easy to get wrong and expensive to get wrong. Upstream's {@code
 * .sha1} for a <b>file</b> is <em>cached and served back untouched</em>, so the resolver's
 * verification stays end to end: a hash this service computed from bytes this service downloaded
 * agrees with itself whatever arrived, which removes the client's check while looking like it kept
 * it. The {@code .sha1} beside {@code maven-metadata.xml} is the exact opposite — it is
 * <b>derived</b> from the cached document, because upstream's copy hashes whatever its metadata says
 * <em>now</em>, which is a different document from the one inside our TTL the moment a version is
 * released. The first story asserts both, and asserts that they differ in provenance by showing the
 * derived one is the hash of the bytes served beside it.
 *
 * <h2>Driven on the wire, and what that gives up</h2>
 *
 * <p>This pair does not run the real {@code mvn}. {@link eu.wohlben.qits.mirror.stories.support.Cli}
 * records why: a second Maven JVM inside a build that already has one, plus a failsafe fork, a
 * launched artifact and an embedded postgres, is a memory bet rather than a test. What that gives up
 * is "a real resolver accepted this". What it keeps is every claim that is about <em>this</em>
 * service: the cold fetch, the warm hit, what upstream was asked, and which checksum is whose.
 *
 * <h2>{@code @TestMethodOrder} is load-bearing</h2>
 *
 * <p>Same reason as {@code stories/caching}: "warm" is a state the cold story creates, and the
 * cumulative upstream recording is attributed by a cursor, so the cold story's four fetches belong
 * on the cold story's diagram and the warm story's empty slice is what {@code assertNoEdgesTo}
 * reads.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MavenPullThroughIT {

  static final String CATEGORY = "maven";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String COLD_STORY = "A build resolves a jar it has never resolved through the mirror";

  static final String COLD_SLUG = Slugs.slug(COLD_STORY);

  static final String WARM_STORY = "The next build resolves the same jar without asking Central";

  static final String WARM_SLUG = Slugs.slug(WARM_STORY);

  /** This class's own coordinate. Nothing on the real Central is called this. */
  static final String GROUP_PATH = "org/example";

  public static final String GROUP_ID = "org.example";

  public static final String ARTIFACT_ID = "story-mirror-lib";

  /**
   * Dotted on purpose. A <b>bare numeric</b> path segment is rewritten to {@code {id}} by the
   * default label scrubber, so a version spelled {@code 1} would erase itself from every arrow in
   * this story's diagram.
   */
  public static final String VERSION = "1.0.0";

  private static final String BUILD = "a build";

  private static final String NEXT_BUILD = "the next build";

  // --- the paths, as the repository lays them out ------------------------------------------------

  private static final String VERSION_DIR = GROUP_PATH + "/" + ARTIFACT_ID + "/" + VERSION;

  static final String JAR_PATH = VERSION_DIR + "/" + ARTIFACT_ID + "-" + VERSION + ".jar";

  static final String POM_PATH = VERSION_DIR + "/" + ARTIFACT_ID + "-" + VERSION + ".pom";

  static final String JAR_SHA1_PATH = JAR_PATH + ".sha1";

  static final String METADATA_PATH = GROUP_PATH + "/" + ARTIFACT_ID + "/maven-metadata.xml";

  static final String METADATA_SHA1_PATH = METADATA_PATH + ".sha1";

  /** Everything upstream serves, and everything this service asks it for. */
  private static final java.util.List<String> UPSTREAM_FETCHES =
      java.util.List.of(METADATA_PATH, POM_PATH, JAR_PATH, JAR_SHA1_PATH);

  /** Everything a build asks THIS service for, in the order a resolve asks. */
  private static final java.util.List<String> RESOLVE_READS =
      java.util.List.of(METADATA_PATH, METADATA_SHA1_PATH, POM_PATH, JAR_PATH, JAR_SHA1_PATH);

  private static byte[] upstreamJar;

  private static String upstreamJarSha1;

  private static byte[] upstreamMetadata;

  @BeforeAll
  static void tapBothEndsAndHostTheArtifact() {
    StoryNetwork.install();
    RecordingUpstream central = central();

    upstreamJar = TinyArtifact.jar(ARTIFACT_ID + "-" + VERSION);
    upstreamJarSha1 = TinyArtifact.hex(upstreamJar, "SHA-1");
    upstreamMetadata = metadataDocument();

    central.serve("/" + JAR_PATH, "application/java-archive", upstreamJar);
    central.serve(
        "/" + POM_PATH, "application/xml", TinyArtifact.pom(GROUP_ID, ARTIFACT_ID, VERSION));
    // Upstream's own checksum file. It is CACHED rather than derived, which is what keeps the
    // resolver's verification end to end — see the class comment.
    central.serve(
        "/" + JAR_SHA1_PATH,
        "text/plain",
        upstreamJarSha1.getBytes(StandardCharsets.UTF_8));
    central.serve("/" + METADATA_PATH, "application/xml", upstreamMetadata);
  }

  private static RecordingUpstream central() {
    return RecordingUpstream.attach(StoryTarget.MAVEN_UPSTREAM);
  }

  /** What Central answers for the artifact's directory: the one document a maven cache TTLs. */
  private static byte[] metadataDocument() {
    return ("""
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <versioning>
            <latest>%s</latest>
            <release>%s</release>
            <versions>
              <version>%s</version>
            </versions>
          </versioning>
        </metadata>
        """)
        .formatted(GROUP_ID, ARTIFACT_ID, VERSION, VERSION, VERSION)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String served(String path) {
    return StoryTarget.MAVEN_BASE + "/" + path;
  }

  @UserStory(value = COLD_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A build resolves a coordinate nothing here has ever seen. Five reads on this side, four
      fetches on the other, and the difference between those two numbers is the point of the
      story.

      First `maven-metadata.xml` — the one maven document that mutates upstream, so the one with a
      TTL — and then its `.sha1`, which costs no second fetch because it is DERIVED from the
      document just cached. Deriving it is not a shortcut: upstream's own copy hashes whatever its
      metadata says now, which stops being the document inside our TTL the moment a version is
      released, so proxying it would hand every client a checksum that does not match the bytes
      beside it.

      Then the pom, the jar, and the jar's `.sha1` — three immutable paths, fetched once each and
      cached forever. And this checksum is upstream's own, cached and served back untouched,
      because a hash this service computed from bytes this service downloaded would agree with
      itself whatever arrived. Caching it is what keeps the resolver's verification end to end.

      The jar the build gets is upstream's bytes, and the checksum beside it is upstream's claim
      about them — neither of which this service is in a position to forge.
      """)
  @Order(1)
  void aBuildResolvesAJarItHasNeverResolved(Interactions story, Network net) {
    RecordingUpstream central = central();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the metadata document, the artifact rows and the jar bytes");

    NetworkCapture.actor(BUILD);

    byte[] metadata =
        given().get(served(METADATA_PATH)).then().statusCode(200).extract().asByteArray();
    assertArrayEquals(
        upstreamMetadata,
        metadata,
        "the metadata is upstream's document, served through rather than rebuilt from the versions"
            + " this cache happens to hold — a resolver told a subset would stop looking");
    assertEquals(1, central.requestsTo("/" + METADATA_PATH), "a cold metadata read is one fetch");

    String metadataSha1 =
        given().get(served(METADATA_SHA1_PATH)).then().statusCode(200).extract().asString().strip();
    assertEquals(
        TinyArtifact.hex(upstreamMetadata, "SHA-1"),
        metadataSha1,
        "the metadata checksum is DERIVED from the cached document, so it can never disagree with"
            + " the bytes served beside it");
    assertEquals(
        1,
        central.requestsTo("/" + METADATA_PATH),
        "and deriving it asked upstream nothing — the document was already here");
    assertEquals(
        0,
        central.requestsTo("/" + METADATA_PATH + ".sha1"),
        "upstream's own metadata checksum is never fetched: it hashes a document we are not serving");

    story
        .note(
            "maven-metadata.xml is the one maven document that mutates, so it is cached with a TTL"
                + " — and its checksum is derived from the copy being served, never proxied")
        .as("metadata-cached-and-its-checksum-derived");

    // --- the three immutable paths ------------------------------------------------------------------
    byte[] pom = given().get(served(POM_PATH)).then().statusCode(200).extract().asByteArray();
    assertTrue(
        new String(pom, StandardCharsets.UTF_8).contains("<artifactId>" + ARTIFACT_ID),
        "the pom must be upstream's document");

    byte[] jar = given().get(served(JAR_PATH)).then().statusCode(200).extract().asByteArray();
    assertArrayEquals(upstreamJar, jar, "the build must get UPSTREAM's bytes");

    String jarSha1 =
        given().get(served(JAR_SHA1_PATH)).then().statusCode(200).extract().asString().strip();
    assertEquals(
        upstreamJarSha1,
        jarSha1,
        "the jar's checksum is UPSTREAM's own, cached and served back untouched — which is what"
            + " keeps the resolver's verification end to end");
    assertNotEquals(
        metadataSha1,
        jarSha1,
        "the two checksums have different provenance and must not be confused for one another");

    for (String path : UPSTREAM_FETCHES) {
      assertEquals(
          1,
          central.requestsTo("/" + path),
          () -> "a cold resolve fetches " + path + " exactly once");
    }

    story
        .note(
            "the pom, the jar and the jar's checksum are immutable paths: fetched once each and"
                + " cached forever, with upstream's own checksum passed through unedited")
        .as("immutable-paths-pulled-through");
    story
        .note(
            "five reads on this side cost four fetches on the other — the fifth was derived from"
                + " what the first one cached")
        .as("four-fetches-for-five-reads");
  }

  @UserStory(value = WARM_STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The same resolve again, and Central hears nothing at all.

      Every one of the five reads answers byte-for-byte what the first build got. The metadata is
      inside its TTL; the pom, the jar and the checksum are immutable and were cached forever the
      moment they arrived. So there is nothing to ask anybody, and nobody is asked.

      That is the whole economic case for a mirror on the JVM plane, and it is the same claim the
      npm plane makes: a build that resolves through this service costs the upstream one fetch per
      artifact, once, for the lifetime of the cache — not once per build, per agent, per branch.

      And it is again a claim about a request that was never made, so the proof is again an
      absence: no edge in this story's diagram reaches Central, measured against a repository that
      is up and recording throughout.
      """)
  @Order(2)
  void theNextBuildAsksCentralNothing(Interactions story, Network net) {
    RecordingUpstream central = central();
    net.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached metadata, the artifact rows and the jar bytes");

    NetworkCapture.actor(NEXT_BUILD);

    assertArrayEquals(
        upstreamMetadata,
        given().get(served(METADATA_PATH)).then().statusCode(200).extract().asByteArray(),
        "the metadata is inside its TTL and comes back unchanged");
    assertEquals(
        TinyArtifact.hex(upstreamMetadata, "SHA-1"),
        given()
            .get(served(METADATA_SHA1_PATH))
            .then()
            .statusCode(200)
            .extract()
            .asString()
            .strip());
    assertTrue(
        given().get(served(POM_PATH)).then().statusCode(200).extract().asString()
            .contains("<artifactId>" + ARTIFACT_ID));
    assertArrayEquals(
        upstreamJar,
        given().get(served(JAR_PATH)).then().statusCode(200).extract().asByteArray(),
        "a cached jar must come back byte-for-byte what upstream served");
    assertEquals(
        upstreamJarSha1,
        given().get(served(JAR_SHA1_PATH)).then().statusCode(200).extract().asString().strip());

    for (String path : UPSTREAM_FETCHES) {
      assertEquals(
          1,
          central.requestsTo("/" + path),
          () -> "the warm resolve must not re-fetch " + path);
    }

    story
        .note(
            "all five reads answered from this process, byte for byte, and Central's recording"
                + " never moved")
        .as("the-whole-resolve-was-local");
    story
        .note(
            "an absence is not an edge: this story's diagram has no arrow to Central at all, which"
                + " is the mirror's whole economic case on the JVM plane")
        .as("central-was-never-dialled");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, COLD_SLUG, UserflowReport.PASSED);
    for (String path : RESOLVE_READS) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          COLD_SLUG,
          NetworkEdge.HTTP,
          BUILD,
          StoryTarget.SERVICE,
          StoryTarget.served("GET", served(path), 200));
    }
    for (String path : UPSTREAM_FETCHES) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          COLD_SLUG,
          NetworkEdge.HTTP,
          StoryTarget.SERVICE,
          StoryTarget.MAVEN_UPSTREAM,
          StoryTarget.fetched("GET", "/" + path, "200"));
    }
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        COLD_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "store the metadata document, the artifact rows and the jar bytes");
    // Five in, four out, one declared. The count is what says the derived checksum cost no fetch.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, COLD_SLUG, 10);
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, COLD_SLUG, "metadata-cached-and-its-checksum-derived");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "immutable-paths-pulled-through");
    ReportAssertions.assertStepId(CATEGORY_SLUG, COLD_SLUG, "four-fetches-for-five-reads");

    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    for (String path : RESOLVE_READS) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          WARM_SLUG,
          NetworkEdge.HTTP,
          NEXT_BUILD,
          StoryTarget.SERVICE,
          StoryTarget.served("GET", served(path), 200));
    }
    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "read the cached metadata, the artifact rows and the jar bytes");
    // THE CLAIM, on the second plane. Directional rather than assertNoEdgesFrom(SERVICE), which
    // would be false: this story's service legitimately dialled its own store and says so above.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, WARM_SLUG, StoryTarget.MAVEN_UPSTREAM);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, WARM_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, WARM_SLUG, java.util.List.of(NEXT_BUILD, StoryTarget.SERVICE));
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "the-whole-resolve-was-local");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "central-was-never-dialled");
  }
}
