package eu.wohlben.qits.mirror;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The mirror datasource carries the platform's resilience baseline — the patient driver, validation
 * at borrow, and a 15s acquisition timeout.
 *
 * <p>{@code @QuarkusTest} so it judges the config the application actually runs on: the shipped
 * application.properties merged with the test overrides, not a file read by hand. It runs on the
 * default profile, so it costs no extra start.
 *
 * <p>What it buys: a cutover that no longer turns catalog reads into "no such artifact" — an answer
 * every docker and maven client caches as fact.
 */
@QuarkusTest
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
