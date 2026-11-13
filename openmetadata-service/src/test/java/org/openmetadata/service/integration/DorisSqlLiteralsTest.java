package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DorisSqlLiteralsTest {
  @Test
  void ddlDefaultsUseTheNativeDoubleQuotedMysqlFormWithoutDoublingApostrophes() {
    String payload = "患者'a\\b\"c\\";
    String emitted = DorisSqlLiterals.defaultLiteral(payload);
    assertEquals("\"患者'a\\\\b\\\"c\\\\\"", emitted);
    assertEquals(
        payload, DorisSqlLiterals.decodeDefault(emitted.substring(1, emitted.length() - 1)));
    assertEquals(DorisSqlLiterals.defaultLiteral(payload), DorisSqlLiterals.tableComment(payload));
    assertEquals(DorisSqlLiterals.defaultLiteral(payload), DorisSqlLiterals.queryLiteral(payload));
  }

  @Test
  void nativeMetadataDefaultTokensDecodeExactlyOneMysqlEscapeLayer() {
    for (String payload : List.of("患者'", "患者\"", "a\\b", "a\\", "NULL", "", "患者'a\\b\"c\\")) {
      String emitted = DorisSqlLiterals.defaultLiteral(payload);
      assertEquals(
          payload, DorisSqlLiterals.decodeDefault(emitted.substring(1, emitted.length() - 1)));
    }
    assertEquals("a\nb\tc", DorisSqlLiterals.decodeDefault("a\\nb\\tc"));
  }

  @Test
  void fractionalTimestampExpressionIncludesDeclaredPrecision() {
    var column =
        DorisTableDesignTest.column(
            "at", "DATETIME", null, null, 3, false, false, "CURRENT_TIMESTAMP", null, null);
    var preview =
        DorisTableDesign.create(
            DorisTableDesignTest.target(),
            DorisTableDesignTest.endpoint(),
            DorisTableDesignTest.input(List.of(DorisTableDesignTest.key(), column)),
            1);
    assertTrue(preview.ddl().contains("DEFAULT CURRENT_TIMESTAMP(3)"));
  }

  @Test
  void unrepresentableColumnCommentBackslashesAreRejectedBeforeDdl() {
    var column =
        DorisTableDesignTest.column(
            "note", "VARCHAR", 80, null, null, true, false, "NONE", null, "路径a\\b");
    assertEquals(
        "INVALID_TABLE_DESIGN",
        assertThrows(
                IntegrationException.class,
                () ->
                    DorisTableDesign.create(
                        DorisTableDesignTest.target(),
                        DorisTableDesignTest.endpoint(),
                        DorisTableDesignTest.input(List.of(DorisTableDesignTest.key(), column)),
                        1))
            .code());
  }

  @Test
  void escapedDefaultTokenAlsoFitsNativeCharAndVarcharValidation() {
    for (String type : List.of("CHAR", "VARCHAR")) {
      var invalid =
          DorisTableDesignTest.column(
              "v", type, 3, null, null, true, false, "LITERAL", "\"\"", null);
      assertEquals(
          "INVALID_TABLE_DESIGN",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      DorisTableDesign.create(
                          DorisTableDesignTest.target(),
                          DorisTableDesignTest.endpoint(),
                          DorisTableDesignTest.input(List.of(DorisTableDesignTest.key(), invalid)),
                          1))
              .code());
      var valid =
          DorisTableDesignTest.column(
              "v", type, 4, null, null, true, false, "LITERAL", "\"\"", null);
      assertEquals(
          "\"\"",
          DorisTableDesign.create(
                  DorisTableDesignTest.target(),
                  DorisTableDesignTest.endpoint(),
                  DorisTableDesignTest.input(List.of(DorisTableDesignTest.key(), valid)),
                  1)
              .columns()
              .getLast()
              .defaultValue());
    }
  }
}
