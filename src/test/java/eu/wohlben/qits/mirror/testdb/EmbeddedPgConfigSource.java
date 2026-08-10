package eu.wohlben.qits.mirror.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply: {@code jdbc.url}, {@code username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250), so this wins over the {@code
 * QITS_RESOURCE_DB_*} expressions the shipped config carries. Those expressions are unresolvable in
 * a suite by design (the refuse-to-boot stance), which is exactly why they have to be replaced
 * rather than defaulted. Registered through {@code META-INF/services}, which is how a config source
 * joins a Quarkus application without being a bean.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This service's database on the embedded instance. */
  private static final String DATABASE = "mirror";

  private static final String PREFIX = "quarkus.datasource.mirror.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
