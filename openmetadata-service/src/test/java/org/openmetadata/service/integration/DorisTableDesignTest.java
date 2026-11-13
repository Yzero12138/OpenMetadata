package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisAddColumnsInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisColumnInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableDesignInput;
import org.openmetadata.service.integration.DorisTableDesignModels.DorisTableStructure;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;

class DorisTableDesignTest {
  @Test
  void createsTypedUniqueTableWithConfiguredBucketsReplicasAndUnicodeComments() {
    var input =
        input(
            List.of(
                column("patient", "VARCHAR", 80, null, null, true, false, "LITERAL", "患者", "姓名"),
                key()));
    var preview = DorisTableDesign.create(target(), endpoint(), input, 3);
    assertEquals("CREATE", preview.action());
    assertEquals("id", preview.columns().getFirst().name());
    assertTrue(preview.ddl().contains("UNIQUE KEY (`id`)"));
    assertTrue(preview.ddl().contains("BUCKETS 4"));
    assertTrue(preview.ddl().contains("\"replication_num\" = \"3\""));
    assertTrue(preview.ddl().contains("COMMENT \"就诊表\""));
    assertTrue(preview.ddl().contains("`patient` VARCHAR(80) NULL DEFAULT \"患者\" COMMENT \"姓名\""));
    assertEquals(80, preview.table().columns().get(1).size());
    assertEquals(20, preview.table().columns().get(1).unicodeCapacity());
    assertTrue(preview.previewHash().matches("[a-f0-9]{64}"));
  }

  @Test
  void normalizesEverySupportedScalarTypeAndFixedDateExpressions() {
    var fields = new ArrayList<DorisColumnInput>();
    fields.add(key());
    for (String type :
        List.of("BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "FLOAT", "DOUBLE", "STRING")) {
      fields.add(
          column(
              "c_" + type,
              type,
              null,
              null,
              null,
              true,
              false,
              "LITERAL",
              type.equals("BOOLEAN") ? "true" : "1",
              null));
    }
    fields.add(column("amount", "DECIMAL", null, 18, 3, false, false, "LITERAL", "12.340", null));
    fields.add(column("code", "CHAR", 12, null, null, true, false, "NULL", null, null));
    fields.add(column("day", "DATE", null, null, null, true, false, "CURRENT_DATE", null, null));
    fields.add(
        column("at", "DATETIME", null, null, 6, true, false, "CURRENT_TIMESTAMP", null, null));
    var preview = DorisTableDesign.create(target(), endpoint(), input(fields), 1);
    assertTrue(preview.ddl().contains("DECIMAL(18,3) NOT NULL DEFAULT \"12.340\""));
    assertTrue(preview.ddl().contains("DATEV2 NULL DEFAULT CURRENT_DATE"));
    assertTrue(preview.ddl().contains("DATETIMEV2(6) NULL DEFAULT CURRENT_TIMESTAMP"));
    assertEquals("DATE", preview.columns().get(11).type());
    assertEquals("DATETIME", preview.columns().getLast().type());
  }

  @Test
  void scalarValidationRejectsOverflowMalformedValuesAndIrrelevantParameters() {
    var invalid =
        List.of(
            column("v", "TINYINT", null, null, null, true, false, "LITERAL", "128", null),
            column(
                "v",
                "BIGINT",
                null,
                null,
                null,
                true,
                false,
                "LITERAL",
                "9223372036854775808",
                null),
            column("v", "INT", null, null, null, true, false, "LITERAL", "1; DROP TABLE t", null),
            column("v", "DECIMAL", null, 4, 2, true, false, "LITERAL", "100.00", null),
            column("v", "DECIMAL", null, 4, 2, true, false, "LITERAL", "1.001", null),
            column("v", "FLOAT", null, null, null, true, false, "LITERAL", "3.5e38", null),
            column("v", "DOUBLE", null, null, null, true, false, "LITERAL", "NaN", null),
            column("v", "DATE", null, null, null, true, false, "LITERAL", "2025-02-29", null),
            column(
                "v",
                "DATETIME",
                null,
                null,
                3,
                true,
                false,
                "LITERAL",
                "2026-01-01 25:00:00",
                null),
            column(
                "v",
                "DATETIME",
                null,
                null,
                3,
                true,
                false,
                "LITERAL",
                "2026-01-01 00:00:00.0001",
                null),
            column("v", "VARCHAR", 5, null, null, true, false, "LITERAL", "患者", null),
            column("v", "VARCHAR", 65534, null, null, true, false, "NONE", null, null),
            column("v", "CHAR", 256, null, null, true, false, "NONE", null, null),
            column("v", "DECIMAL", null, 39, 2, true, false, "NONE", null, null),
            column("v", "DECIMAL", null, 4, 5, true, false, "NONE", null, null),
            column("v", "INT", 20, null, null, true, false, "NONE", null, null),
            column("v", "DATE", null, null, 0, true, false, "NONE", null, null),
            column("v", "DATE", null, null, null, true, false, "CURRENT_TIMESTAMP", null, null),
            column("v", "DATETIME", null, null, 7, true, false, "NONE", null, null),
            column("v", "JSON", null, null, null, true, false, "NONE", null, null),
            column("v", "INT", null, null, null, false, false, "NULL", null, null),
            column("v", "INT", null, null, null, true, false, "NONE", "0", null),
            column("v", "VARCHAR", 32, null, null, true, false, "LITERAL", "a\u0000b", null));
    for (var value : invalid) {
      assertEquals(
          "INVALID_TABLE_DESIGN",
          failure(
              () -> DorisTableDesign.create(target(), endpoint(), input(List.of(key(), value)), 1)),
          value.toString());
    }
  }

  @Test
  void rejectsDuplicateUnsafeNamesAndMissingOrIncompatibleKeys() {
    for (var fields :
        List.of(
            List.of(key(), column("ID", "INT", null, null, null, true, false, null, null, null)),
            List.of(column("id", "FLOAT", null, null, null, false, true, null, null, null)),
            List.of(column("id", "DOUBLE", null, null, null, false, true, null, null, null)),
            List.of(column("id", "STRING", null, null, null, false, true, null, null, null)),
            List.of(column("id", "INT", null, null, null, true, true, null, null, null)),
            List.of(column("id", "INT", null, null, null, false, false, null, null, null)),
            List.of(
                key(), column("x`);DROP", "INT", null, null, null, true, false, null, null, null)),
            List.of(key(), column("患者", "INT", null, null, null, true, false, null, null, null)))) {
      assertEquals(
          "INVALID_TABLE_DESIGN",
          failure(() -> DorisTableDesign.create(target(), endpoint(), input(fields), 1)));
    }
  }

  @Test
  void literalsAndCommentsUseNativeDdlEmitters() {
    String payload = "患者'\\'; DROP TABLE secret; --";
    String comment = "患者'\"; statement text";
    var preview =
        DorisTableDesign.create(
            target(),
            endpoint(),
            input(
                List.of(
                    key(),
                    column(
                        "note", "VARCHAR", 200, null, null, true, false, "LITERAL", payload,
                        comment))),
            1);
    String encoded = DorisTableDesign.literal(payload);
    assertEquals("\"患者'\\\\'; DROP TABLE secret; --\"", encoded);
    assertEquals(
        payload, DorisSqlLiterals.decodeDefault(encoded.substring(1, encoded.length() - 1)));
    assertTrue(preview.ddl().contains("DEFAULT " + encoded));
    assertTrue(preview.ddl().contains("COMMENT \"患者'\\\"; statement text\""));
  }

  @Test
  void hashBindsActualEndpointConnectionVersionAndEveryCanonicalInput() {
    var input = input(List.of(key()));
    String hash = DorisTableDesign.create(target(), endpoint(), input, 1).previewHash();
    var changed = target();
    changed.version++;
    assertNotEquals(hash, DorisTableDesign.create(changed, endpoint(), input, 1).previewHash());
    changed = target();
    changed.host = "another-fe";
    assertNotEquals(
        hash,
        DorisTableDesign.create(target(), new JdbcEndpoint(changed, "unused"), input, 1)
            .previewHash());
    assertNotEquals(
        hash,
        DorisTableDesign.create(
                target(),
                endpoint(),
                new DorisTableDesignInput(
                    "warehouse", "visits", "changed", 4, List.of(key()), null),
                1)
            .previewHash());
    assertEquals(
        hash,
        DorisTableDesign.create(target(), new JdbcEndpoint(target(), "another-secret"), input, 1)
            .previewHash());
  }

  @Test
  void appendsOnlyNewValueColumnsAndBindsFullExistingStructure() {
    var existing = structure();
    var added = column("extra", "VARCHAR", 40, null, null, true, false, "NONE", null, "新增");
    var preview =
        DorisTableDesign.append(
            target(), endpoint(), existing, new DorisAddColumnsInput(List.of(added), null));
    assertEquals("ADD_COLUMNS", preview.action());
    assertTrue(preview.ddl().startsWith("ALTER TABLE `warehouse`.`visits` ADD COLUMN ("));
    assertFalse(preview.ddl().contains("`id` BIGINT"));
    assertEquals(2, preview.table().columns().size());
    var different =
        new DorisTableStructure(
            existing.targetConnectionId(),
            existing.targetSchema(),
            existing.targetTable(),
            "changed",
            existing.model(),
            existing.buckets(),
            existing.replicationNum(),
            "different-structure",
            existing.columns(),
            existing.table(),
            existing.ddl(),
            null);
    assertNotEquals(
        preview.previewHash(),
        DorisTableDesign.append(
                target(), endpoint(), different, new DorisAddColumnsInput(List.of(added), null))
            .previewHash());
    assertEquals(
        "COLUMN_ALREADY_EXISTS",
        failure(
            () ->
                DorisTableDesign.append(
                    target(),
                    endpoint(),
                    existing,
                    new DorisAddColumnsInput(List.of(key()), null))));
    assertEquals(
        "INVALID_TABLE_DESIGN",
        failure(
            () ->
                DorisTableDesign.append(
                    target(),
                    endpoint(),
                    existing,
                    new DorisAddColumnsInput(
                        List.of(
                            column("v", "INT", null, null, null, false, false, "NONE", null, null)),
                        null))));
    assertEquals(
        "INVALID_TABLE_DESIGN",
        failure(
            () ->
                DorisTableDesign.append(
                    target(),
                    endpoint(),
                    existing,
                    new DorisAddColumnsInput(
                        List.of(
                            column(
                                "v", "INT", null, null, null, false, true, "LITERAL", "0", null)),
                        null))));
    DorisTableDesign.append(
        target(),
        endpoint(),
        existing,
        new DorisAddColumnsInput(
            List.of(column("v", "INT", null, null, null, false, false, "LITERAL", "0", null)),
            null));
  }

  @Test
  void commentsAndTotalColumnsAreBoundedInUtf8Bytes() {
    var tooLong =
        column("note", "STRING", null, null, null, true, false, "NONE", null, "患".repeat(342));
    assertTrue(tooLong.comment().getBytes(StandardCharsets.UTF_8).length > 1024);
    assertEquals(
        "INVALID_TABLE_DESIGN",
        failure(
            () ->
                DorisTableDesign.create(target(), endpoint(), input(List.of(key(), tooLong)), 1)));
    var fields = new ArrayList<DorisColumnInput>();
    fields.add(key());
    for (int i = 0; i < 128; i++)
      fields.add(column("c" + i, "INT", null, null, null, true, false, null, null, null));
    assertEquals(
        "TABLE_COLUMN_LIMIT_EXCEEDED",
        failure(() -> DorisTableDesign.create(target(), endpoint(), input(fields), 1)));
  }

  @Test
  void equivalentDatetimeLiteralsHaveTheSameCanonicalPrecisionAndHash() {
    var shortValue =
        input(
            List.of(
                key(),
                column(
                    "at",
                    "DATETIME",
                    null,
                    null,
                    6,
                    true,
                    false,
                    "LITERAL",
                    "2026-11-01 00:00:00.1",
                    null)));
    var fullValue =
        input(
            List.of(
                key(),
                column(
                    "at",
                    "DATETIME",
                    null,
                    null,
                    6,
                    true,
                    false,
                    "LITERAL",
                    "2026-11-01 00:00:00.100000",
                    null)));
    var preview = DorisTableDesign.create(target(), endpoint(), shortValue, 1);
    assertEquals("2026-11-01 00:00:00.100000", preview.columns().getLast().defaultValue());
    assertEquals(
        preview.previewHash(),
        DorisTableDesign.create(target(), endpoint(), fullValue, 1).previewHash());
  }

  static DorisTableStructure structure() {
    var preview = DorisTableDesign.create(target(), endpoint(), input(List.of(key())), 1);
    return new DorisTableStructure(
        "target",
        "warehouse",
        "visits",
        "就诊表",
        "UNIQUE",
        4,
        1,
        "existing-structure",
        preview.columns(),
        preview.table(),
        preview.ddl(),
        null);
  }

  static DorisTableDesignInput input(List<DorisColumnInput> columns) {
    return new DorisTableDesignInput("warehouse", "visits", "就诊表", 4, columns, null);
  }

  static DorisColumnInput key() {
    return column("id", "BIGINT", null, null, null, false, true, "NONE", null, "主键");
  }

  static DorisColumnInput column(
      String name,
      String type,
      Integer length,
      Integer precision,
      Integer scale,
      Boolean nullable,
      Boolean key,
      String kind,
      String value,
      String comment) {
    return new DorisColumnInput(
        name, type, length, precision, scale, nullable, key, kind, value, comment);
  }

  static ConnectionDefinition target() {
    var result = new ConnectionDefinition();
    result.id = "target";
    result.role = "TARGET";
    result.databaseType = "Doris";
    result.host = "doris-fe";
    result.port = 9030;
    result.dorisHttpPort = 8030;
    result.database = "warehouse";
    result.schemas = List.of("warehouse");
    result.username = "writer";
    result.tlsMode = "DISABLED";
    result.enabled = true;
    return result;
  }

  static JdbcEndpoint endpoint() {
    return new JdbcEndpoint(target(), "synthetic-secret");
  }

  private String failure(org.junit.jupiter.api.function.Executable action) {
    return assertThrows(IntegrationException.class, action).code();
  }
}
