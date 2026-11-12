package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationSqlTest {
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2025, 12, 31);

  @Test
  void acceptsOneSelectWithJoinsSubqueriesAndConfiguredSchemas() {
    assertDoesNotThrow(
        () ->
            IntegrationSql.validate(
                "WITH daily AS (SELECT id FROM public.visits WHERE day = :biz_date) "
                    + "SELECT d.id FROM daily d JOIN clinical.patients p ON d.id = p.id "
                    + "WHERE EXISTS (SELECT 1 FROM public.departments x WHERE x.id = p.id)",
                Set.of("public", "clinical"),
                "public"));
  }

  @Test
  void rejectsWritesMultipleStatementsLocksAndRemoteReferences() {
    for (String query :
        List.of(
            "DELETE FROM public.visits WHERE day = :biz_date",
            "UPDATE public.visits SET day = :biz_date",
            "INSERT INTO public.visits SELECT :biz_date",
            "WITH changed AS (DELETE FROM public.visits RETURNING *) SELECT :biz_date FROM changed",
            "SELECT id INTO public.copy FROM public.visits WHERE day = :biz_date",
            "SELECT id FROM public.visits WHERE day = :biz_date FOR UPDATE",
            "SELECT id FROM public.visits WHERE day = :biz_date FOR SHARE",
            "SELECT id FROM public.visits WHERE day = :biz_date; SELECT 1",
            "SELECT id FROM other_db.public.visits WHERE day = :biz_date",
            "SELECT id FROM public.visits@remote WHERE day = :biz_date",
            "SELECT id FROM dblink('remote', 'SELECT id FROM visits') WHERE day = :biz_date",
            "SELECT pg_catalog.nextval('public.ids'), :biz_date FROM public.visits",
            "SELECT nextval('public.ids'), :biz_date FROM public.visits")) {
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"),
                  query)
              .code(),
          query);
    }
  }

  @Test
  void rejectsOracleSequenceAdvancementEvenWhenParsedAsAColumn() {
    for (String sequence :
        List.of(
            "ids.NEXTVAL",
            "ids.nextval",
            "public.ids.NeXtVaL",
            "\"ids\".NEXTVAL",
            "ids.\"NEXTVAL\"")) {
      String query = "SELECT " + sequence + " AS id, :biz_date FROM public.visits";
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"),
                  query)
              .code());
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      IntegrationSql.renderForSource(
                          query, BUSINESS_DATE, "public", JdbcDialect.Oracle),
                  query)
              .code());
    }
    assertDoesNotThrow(
        () ->
            IntegrationSql.validate(
                "SELECT ids.CURRVAL AS id, ':biz_date NEXTVAL', :biz_date FROM public.visits /* ids.NEXTVAL */",
                Set.of("public"),
                "public"));
  }

  @Test
  void rejectsKnownPostgresSessionMutationsAndLocksWithQualifiedOrQuotedNames() {
    for (String invocation :
        List.of(
            "pg_notify('events', 'payload')",
            "set_config('application_name', 'changed', false)",
            "pg_advisory_lock(1)",
            "pg_advisory_lock_shared(1)",
            "pg_advisory_xact_lock(1)",
            "pg_advisory_xact_lock_shared(1)",
            "pg_try_advisory_lock(1)",
            "pg_try_advisory_lock_shared(1)",
            "pg_try_advisory_xact_lock(1)",
            "pg_try_advisory_xact_lock_shared(1)",
            "pg_advisory_unlock(1)",
            "pg_advisory_unlock_shared(1)",
            "pg_advisory_unlock_all()",
            "pg_terminate_backend(123)",
            "pg_cancel_backend(123)")) {
      int open = invocation.indexOf('(');
      String name = invocation.substring(0, open);
      String arguments = invocation.substring(open);
      for (String function :
          List.of(
              invocation,
              "pg_catalog." + invocation,
              "\"pg_catalog\".\"" + name.toUpperCase(Locale.ROOT) + "\"" + arguments)) {
        String query = "SELECT " + function + ", :biz_date FROM public.visits";
        assertEquals(
            "INVALID_SOURCE_QUERY",
            assertThrows(
                    IntegrationException.class,
                    () -> IntegrationSql.validate(query, Set.of("public"), "public"),
                    query)
                .code());
      }
    }
    assertDoesNotThrow(
        () ->
            IntegrationSql.validate(
                "SELECT COALESCE(name, 'pg_notify'), :biz_date FROM public.visits -- pg_advisory_lock(1)",
                Set.of("public"),
                "public"));
  }

  @Test
  void rejectsBuiltinDynamicQueriesAndXmlExportsThatHidePhysicalTableReferences() {
    for (String expression :
        List.of(
            "query_to_xml('SELECT * FROM private.visits', true, false, '')",
            "pg_catalog.query_to_xmlschema('SELECT * FROM private.visits', true, false, '')",
            "query_to_xml_and_xmlschema('SELECT * FROM private.visits', true, false, '')",
            "table_to_xml('private.visits'::regclass, true, false, '')",
            "table_to_xmlschema('private.visits'::regclass, true, false, '')",
            "table_to_xml_and_xmlschema('private.visits'::regclass, true, false, '')",
            "schema_to_xml('private', true, false, '')",
            "schema_to_xmlschema('private', true, false, '')",
            "database_to_xml(true, false, '')",
            "database_to_xml_and_xmlschema(true, false, '')",
            "cursor_to_xml('private_cursor', 100, true, false, '')",
            "DBMS_XMLGEN.GETXML('SELECT * FROM private.visits')",
            "SYS.DBMS_XMLGEN.GETXMLTYPE('SELECT * FROM private.visits')",
            "\"SYS\".\"DBMS_XMLGEN\".\"GETXML\"('SELECT * FROM private.visits')")) {
      String query = "SELECT " + expression + ", :biz_date FROM public.visits";
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"),
                  query)
              .code());
    }
  }

  @Test
  void rejectsSchemaEscapesInEveryExpressionAndCteScope() {
    for (String query :
        List.of(
            "SELECT id FROM private.visits WHERE day = :biz_date",
            "WITH a AS (SELECT id FROM private.visits) SELECT id FROM a WHERE day = :biz_date",
            "SELECT (SELECT id FROM private.visits) FROM public.visits WHERE day = :biz_date",
            "SELECT id FROM public.visits WHERE day = :biz_date ORDER BY (SELECT id FROM private.visits)",
            "SELECT COUNT(*) FROM public.visits WHERE day = :biz_date GROUP BY (SELECT id FROM private.visits)",
            "SELECT id FROM public.visits WHERE day = :biz_date UNION SELECT id FROM private.visits",
            "SELECT id FROM (WITH visits AS (SELECT id FROM public.visits) SELECT id FROM visits) a "
                + "UNION SELECT id FROM visits WHERE day = :biz_date")) {
      String defaultSchema = query.contains("UNION SELECT id FROM visits") ? "private" : "public";
      assertEquals(
          "SCHEMA_NOT_ALLOWED",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), defaultSchema),
                  query)
              .code());
    }
  }

  @Test
  void requiresRecognizedDateParametersOutsideLiteralsAndComments() {
    for (String query :
        List.of(
            "SELECT id FROM public.visits",
            "SELECT ':biz_date' FROM public.visits -- :window_start",
            "SELECT $$:biz_date$$ FROM public.visits /* :window_end */")) {
      assertEquals(
          "SQL_DATE_PARAMETER_REQUIRED",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"))
              .code());
    }
    for (String query :
        List.of(
            "SELECT :unknown, :biz_date FROM public.visits",
            "SELECT ?, :biz_date FROM public.visits",
            "SELECT :biz_date_suffix FROM public.visits")) {
      assertEquals(
          "SQL_PARAMETER_UNSUPPORTED",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"))
              .code());
    }
  }

  @Test
  void rendersFixedQuotedDatesAcrossYearAndLeapBoundaries() {
    String query = "SELECT :biz_date, :window_start, :window_end FROM public.visits";
    assertEquals(
        "SELECT '2025-12-31', '2025-12-31 00:00:00', '2026-01-01 00:00:00' FROM public.visits",
        IntegrationSql.render(query, BUSINESS_DATE));
    assertEquals(
        "SELECT '2024-02-29', '2024-02-29 00:00:00', '2024-03-01 00:00:00' FROM public.visits",
        IntegrationSql.render(query, LocalDate.of(2024, 2, 29)));
  }

  @Test
  void preservesStringsCommentsQuotedNamesAndPostgresCasts() {
    String query =
        "SELECT ':biz_date''x', $$:window_start$$, \"biz:window_end\", id::text, :biz_date::date "
            + "FROM public.visits /* :window_start */ WHERE updated_at >= :window_start::timestamp "
            + "-- :window_end\nAND updated_at < CAST(:window_end AS timestamp)";
    assertEquals(
        "SELECT ':biz_date''x', $$:window_start$$, \"biz:window_end\", id::text, '2025-12-31'::date "
            + "FROM public.visits /* :window_start */ WHERE updated_at >= '2025-12-31 00:00:00'::timestamp "
            + "-- :window_end\nAND updated_at < CAST('2026-01-01 00:00:00' AS timestamp)",
        IntegrationSql.render(query, BUSINESS_DATE));
  }

  @Test
  void physicalReferencesExcludeCtesAndPreserveQuotedIdentifiers() {
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("public", "visits"),
            new IntegrationSql.TableReference("clinical", "Patients")),
        IntegrationSql.references(
            "WITH v AS (SELECT id FROM visits WHERE day = :biz_date) "
                + "SELECT v.id FROM v JOIN \"clinical\".\"Patients\" p ON p.id = v.id",
            "public"));
  }

  @Test
  void sourceRenderingQualifiesOnlyPhysicalUnqualifiedReferences() {
    assertEquals(
        "WITH v AS (SELECT id FROM \"clinical\".visits WHERE day = '2025-12-31') "
            + "SELECT v.id, 'FROM visits' FROM v -- keep\nJOIN \"public\".patients p ON p.id = v.id",
        IntegrationSql.renderForSource(
            "WITH v AS (SELECT id FROM visits WHERE day = :biz_date) "
                + "SELECT v.id, 'FROM visits' FROM v -- keep\nJOIN public.patients p ON p.id = v.id",
            BUSINESS_DATE,
            "clinical",
            JdbcDialect.Postgres));
  }

  @Test
  void boundsSqlSizeAndRejectsUnclosedLexicalRegions() {
    for (String query :
        List.of(
            "SELECT :biz_date /*" + "x".repeat(16384),
            "SELECT :biz_date FROM public.visits /* unclosed",
            "SELECT :biz_date, 'unclosed FROM public.visits")) {
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"))
              .code());
    }
  }

  @Test
  void preservesOracleAlternativeLiteralsAndPostgresEscapedStrings() {
    String query =
        "SELECT q'[patient's :biz_date]', E'patient\\'s :window_start', :window_end FROM public.visits";
    assertEquals(
        "SELECT q'[patient's :biz_date]', E'patient\\'s :window_start', '2026-01-01 00:00:00' FROM public.visits",
        IntegrationSql.render(query, BUSINESS_DATE));
    assertEquals(
        "SQL_DATE_PARAMETER_REQUIRED",
        assertThrows(
                IntegrationException.class,
                () ->
                    IntegrationSql.validate(
                        "SELECT q'[patient's :biz_date]' FROM public.visits",
                        Set.of("public"),
                        "public"))
            .code());
  }

  @Test
  void acceptsPostgresEscapedLiteralsWhenValidatingAndRenderingTheSource() {
    String query =
        "SELECT E'patient\\'s :window_start' AS payload, id FROM public.visits WHERE day = :biz_date";
    assertDoesNotThrow(() -> IntegrationSql.validate(query, Set.of("public"), "public"));
    assertEquals(
        "SELECT E'patient\\'s :window_start' AS payload, id FROM \"public\".visits WHERE day = '2025-12-31'",
        IntegrationSql.renderForSource(query, BUSINESS_DATE, "public", JdbcDialect.Postgres));
  }

  @Test
  void rejectsExecutableOrDialectAmbiguousCommentsAndUnknownNumericParameters() {
    for (String query :
        List.of(
            "SELECT id FROM public.visits WHERE day = :biz_date /*! INTO OUTFILE '/tmp/data' */",
            "SELECT id FROM public.visits WHERE day = :biz_date /*M! dangerous */",
            "SELECT :biz_date--column FROM private.visits")) {
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.validate(query, Set.of("public"), "public"),
                  query)
              .code());
    }
    assertEquals(
        "SQL_PARAMETER_UNSUPPORTED",
        assertThrows(
                IntegrationException.class,
                () ->
                    IntegrationSql.validate(
                        "SELECT :biz_date, :1 FROM public.visits", Set.of("public"), "public"))
            .code());
  }

  @Test
  void preservesQuotedCteCaseSoASeparatePhysicalTableCannotEscapeScopeValidation() {
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("public", "visits"),
            new IntegrationSql.TableReference("public", "v")),
        IntegrationSql.references(
            "WITH \"V\" AS (SELECT id FROM visits WHERE day = :biz_date) SELECT id FROM v",
            "public"));
  }

  @Test
  void qualificationPreservesCrLfTabsCommentsAndNestedCteScope() {
    String query =
        "SELECT id FROM (WITH visits AS (SELECT id FROM public.actual WHERE day = :biz_date) "
            + "SELECT id FROM visits) v\r\nUNION SELECT id\r\n\tFROM visits /* tail */";
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("public", "actual"),
            new IntegrationSql.TableReference("public", "visits")),
        IntegrationSql.references(query, "public"));
    assertEquals(
        query
            .replace(":biz_date", "'2025-12-31'")
            .replace("public.actual", "\"public\".actual")
            .replace("\tFROM visits", "\tFROM \"public\".visits"),
        IntegrationSql.renderForSource(query, BUSINESS_DATE, "public", JdbcDialect.Postgres));
  }

  @Test
  void quotesExplicitSchemasSoCaseFoldingCannotSelectAnUnconfiguredSchema() throws Exception {
    for (JdbcDialect dialect : List.of(JdbcDialect.Postgres, JdbcDialect.Oracle)) {
      boolean postgres = dialect == JdbcDialect.Postgres;
      String allowed = postgres ? "Clinical" : "clinical";
      String other = postgres ? "clinical" : "CLINICAL";
      String jdbc =
          "jdbc:h2:mem:"
              + UUID.randomUUID()
              + (postgres ? ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" : ";MODE=Oracle");
      try (var connection = DriverManager.getConnection(jdbc);
          var statement = connection.createStatement()) {
        for (String schema : List.of(allowed, other)) {
          statement.execute("CREATE SCHEMA \"" + schema + "\"");
          statement.execute("CREATE TABLE \"" + schema + "\".visits (id BIGINT, biz_day DATE)");
          statement.execute(
              "INSERT INTO \""
                  + schema
                  + "\".visits VALUES ("
                  + (schema.equals(allowed) ? 11 : 22)
                  + ", DATE '2025-12-31')");
        }
        String query =
            "SELECT id FROM " + allowed + ".visits WHERE biz_day = CAST(:biz_date AS DATE)";
        IntegrationSql.validate(query, Set.of(allowed), allowed);
        String rendered = IntegrationSql.renderForSource(query, BUSINESS_DATE, allowed, dialect);
        assertEquals(
            "SELECT id FROM \"" + allowed + "\".visits WHERE biz_day = CAST('2025-12-31' AS DATE)",
            rendered);
        try (var rows = statement.executeQuery(rendered)) {
          assertTrue(rows.next());
          assertEquals(11, rows.getInt(1));
          assertFalse(rows.next());
        }
      }
    }
  }

  @Test
  void dialectReferencesFoldOnlyUnquotedTableIdentifiersForCatalogMetadata() {
    String query =
        "SELECT id FROM Clinical.VISITS WHERE biz_day = :biz_date "
            + "UNION SELECT id FROM \"Clinical\".\"MixedVisits\" WHERE biz_day = :biz_date";
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("Clinical", "visits"),
            new IntegrationSql.TableReference("Clinical", "MixedVisits")),
        IntegrationSql.references(query, "Clinical", JdbcDialect.Postgres));
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("Clinical", "VISITS"),
            new IntegrationSql.TableReference("Clinical", "MixedVisits")),
        IntegrationSql.references(
            query.replace("Clinical.VISITS", "Clinical.visits"), "Clinical", JdbcDialect.Oracle));
  }

  @Test
  void oracleCteScopeFoldsUnquotedNamesUpperAndKeepsQuotedNamesExact() throws Exception {
    String query =
        "WITH \"v\" AS (SELECT id FROM CLINICAL.visits WHERE biz_day = CAST(:biz_date AS DATE)) SELECT id FROM v";
    String quotedReference =
        "WITH v AS (SELECT id FROM CLINICAL.visits WHERE biz_day = CAST(:biz_date AS DATE)) SELECT id FROM \"V\"";
    IntegrationSql.validate(query, Set.of("CLINICAL"), "CLINICAL", JdbcDialect.Oracle);
    assertEquals(
        List.of(
            new IntegrationSql.TableReference("CLINICAL", "VISITS"),
            new IntegrationSql.TableReference("CLINICAL", "V")),
        IntegrationSql.references(query, "CLINICAL", JdbcDialect.Oracle));
    assertEquals(
        List.of(new IntegrationSql.TableReference("CLINICAL", "VISITS")),
        IntegrationSql.references(quotedReference, "CLINICAL", JdbcDialect.Oracle));
    try (var connection =
            DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle");
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA CLINICAL");
      statement.execute("CREATE TABLE CLINICAL.visits (id BIGINT, biz_day DATE)");
      statement.execute("INSERT INTO CLINICAL.visits VALUES (11, DATE '2025-12-31')");
      statement.execute("CREATE TABLE CLINICAL.v (id BIGINT)");
      statement.execute("INSERT INTO CLINICAL.v VALUES (33)");
      statement.execute("CREATE TABLE PUBLIC.v (id BIGINT)");
      statement.execute("INSERT INTO PUBLIC.v VALUES (99)");
      try (var rows =
          statement.executeQuery(
              IntegrationSql.renderForSource(
                  query, BUSINESS_DATE, "CLINICAL", JdbcDialect.Oracle))) {
        assertTrue(rows.next());
        assertEquals(33, rows.getInt(1));
      }
      assertEquals(
          quotedReference
              .replace("CLINICAL.visits", "\"CLINICAL\".visits")
              .replace(":biz_date", "'2025-12-31'"),
          IntegrationSql.renderForSource(
              quotedReference, BUSINESS_DATE, "CLINICAL", JdbcDialect.Oracle));
    }
  }

  @Test
  void rejectsAmbiguousCteCaseWhenTheDatabaseCollationIsUnknown() {
    String query = "WITH v AS (SELECT id FROM visits WHERE day = :biz_date) SELECT id FROM V";
    for (JdbcDialect dialect : List.of(JdbcDialect.Mssql, JdbcDialect.Mysql)) {
      assertEquals(
          "INVALID_SOURCE_QUERY",
          assertThrows(
                  IntegrationException.class,
                  () -> IntegrationSql.references(query, "clinical", dialect))
              .code());
    }
  }
}
