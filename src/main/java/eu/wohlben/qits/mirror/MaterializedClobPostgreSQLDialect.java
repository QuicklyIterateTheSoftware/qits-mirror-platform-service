package eu.wohlben.qits.mirror;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.jdbc.ClobJdbcType;

/**
 * PostgreSQL, with {@code @Lob String} read and written as an ordinary {@code text} column instead of
 * a large object.
 *
 * <p><b>Why this class has to exist.</b> Three library entities carry a {@code @Lob String}: {@code
 * NpmProxyPackument.doc}, {@code NpmVersion.manifestJson} and {@code MavenProxyMetadata.doc} — the
 * cached documents that are the whole point of an npm and a maven cache. Hibernate's default JDBC
 * binding for {@code CLOB} on PostgreSQL is a <b>LOB locator</b>: the driver hands back a {@code
 * PgClob} and streams the value out of {@code pg_largeobject} on demand. PostgreSQL refuses that
 * outside a transaction, so a plain serve of a cached packument — a read with no write, and therefore
 * no transaction — dies with
 *
 * <pre>org.postgresql.util.PSQLException: Large Objects may not be used in auto-commit mode.</pre>
 *
 * <p>and the client gets a 500 for a document sitting in the database. It is not a configuration
 * mistake and no amount of DDL fixes it: the binding is chosen by the dialect, not by the column
 * type, so declaring the column {@code text} (which V1 does) and leaving the default in place gives a
 * schema that is right and a read path that is wrong.
 *
 * <p>{@link ClobJdbcType#MATERIALIZED} binds and extracts the value as a plain string, which is what
 * a {@code text} column wants and what these three documents are: a packument is tens of kilobytes,
 * read whole, parsed whole, and served whole. Nothing here streams a CLOB, so there is nothing the
 * locator buys.
 *
 * <p><b>Why here and not in the entities.</b> The alternative is {@code
 * @JdbcTypeCode(SqlTypes.LONGVARCHAR)} on the three fields, which is the more usual fix — but those
 * fields are in qits-blobstore and qits-registries, shared with qits-artifacts, which still runs on
 * H2 where the default binding is correct. A dialect is the right seam for a difference that is a
 * property of <em>this deployment's database</em> rather than of the mapping. When the artifacts
 * Postgres migration lands, the annotation becomes the better answer and this class goes away.
 *
 * <p>Named in {@code quarkus.hibernate-orm.mirror.dialect}. The three constructors are Hibernate's
 * own resolution contract: it instantiates a dialect through whichever one it can, and a subclass
 * that declares only the no-arg one is not resolvable from a live connection's version.
 */
public class MaterializedClobPostgreSQLDialect extends PostgreSQLDialect {

  public MaterializedClobPostgreSQLDialect() {
    super();
  }

  public MaterializedClobPostgreSQLDialect(DialectResolutionInfo info) {
    super(info);
  }

  public MaterializedClobPostgreSQLDialect(DatabaseVersion version) {
    super(version);
  }

  @Override
  public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
    super.contributeTypes(typeContributions, serviceRegistry);
    // Last contribution for a JDBC type code wins, so this replaces whatever the superclass
    // registered for CLOB.
    typeContributions.contributeJdbcType(ClobJdbcType.MATERIALIZED);
  }

  /**
   * {@code length(x)} counts characters of a {@code text} column, rather than of a large object.
   *
   * <p>The binding above is only half the story. {@code PostgreSQLDialect} also registers a
   * <b>second</b> rendering of {@code length()} for a CLOB-typed argument — {@code
   * length(lo_get(?1), pg_client_encoding())} — and the choice is made from the attribute's mapped
   * type, not from its JDBC binding. So the three {@code @Lob String} documents keep taking that
   * branch, and the eviction queries that ask how many characters a cache holds die with
   *
   * <pre>ERROR: function lo_get(text) does not exist</pre>
   *
   * <p>because the column is a {@code text} and there is no large object to fetch. Re-registering
   * the plain pattern after the superclass has run puts every {@code length()} in this service on
   * the one form its columns actually support. It is the same argument the type contribution above
   * makes, at the other end of the same mismatch: qits-artifacts still runs H2, where both
   * renderings work, so the fix belongs to this deployment's dialect rather than to the shared
   * entities.
   */
  @Override
  public void initializeFunctionRegistry(FunctionContributions functionContributions) {
    super.initializeFunctionRegistry(functionContributions);
    BasicType<Integer> integer =
        functionContributions
            .getTypeConfiguration()
            .getBasicTypeRegistry()
            .resolve(StandardBasicTypes.INTEGER);
    functionContributions.getFunctionRegistry().registerPattern("length", "length(?1)", integer);
    functionContributions
        .getFunctionRegistry()
        .registerPattern("character_length", "length(?1)", integer);
  }
}
