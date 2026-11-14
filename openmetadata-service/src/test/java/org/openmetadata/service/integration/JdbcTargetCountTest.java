package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class JdbcTargetCountTest {
  @Test
  void targetCountExecutesOnlyBoundedReadOnlyTargetSelectAndAcceptsZero() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var result = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT COUNT(*) FROM `ods_validation`.`patient_ods`"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true, false);
    when(result.getBigDecimal(1)).thenReturn(BigDecimal.ZERO);
    var endpoint = IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation");
    var inspector = new JdbcInspector(id -> endpoint, (selected, properties) -> connection);
    assertEquals(0, inspector.targetCount("doris-ods-validation", "ods_validation", "patient_ods"));
    verify(connection).setReadOnly(true);
    verify(statement).setQueryTimeout(5);
    verify(statement).setMaxRows(1);
    verify(connection).close();
  }

  @Test
  void targetCountRejectsUnsafeOrUnregisteredSchemaBeforeOpeningJdbc() {
    var connection = mock(Connection.class);
    var endpoint = IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation");
    var inspector = new JdbcInspector(id -> endpoint, (selected, properties) -> connection);
    assertEquals(
        "SCHEMA_NOT_ALLOWED",
        assertThrows(
                IntegrationException.class,
                () -> inspector.targetCount("doris-ods-validation", "other", "patient_ods"))
            .code());
    assertEquals(
        "INVALID_CONFIGURATION",
        assertThrows(
                IntegrationException.class,
                () ->
                    inspector.targetCount(
                        "doris-ods-validation", "ods_validation", "patient_ods;DROP"))
            .code());
    verifyNoInteractions(connection);
  }

  @Test
  void targetCountRedactsQueryFailuresAndRejectsInvalidCounts() throws Exception {
    for (BigDecimal invalid :
        new BigDecimal[] {
          null, new BigDecimal("-1"), new BigDecimal("0.5"), new BigDecimal("9223372036854775808")
        }) {
      var connection = mock(Connection.class);
      var statement = mock(PreparedStatement.class);
      var result = mock(ResultSet.class);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(result);
      when(result.next()).thenReturn(true, false);
      when(result.getBigDecimal(1)).thenReturn(invalid);
      var endpoint = IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation");
      var inspector = new JdbcInspector(id -> endpoint, (selected, properties) -> connection);
      assertEquals(
          "TARGET_COUNT_UNAVAILABLE",
          assertThrows(
                  IntegrationException.class,
                  () ->
                      inspector.targetCount(
                          "doris-ods-validation", "ods_validation", "patient_ods"))
              .code());
    }
    var endpoint = IntegrationCdcExecutionTest.configuration().endpoint("doris-ods-validation");
    var inspector =
        new JdbcInspector(
            id -> endpoint,
            (selected, properties) -> {
              throw new SQLException("password=private-secret jdbc:internal");
            });
    var failure =
        assertThrows(
            IntegrationException.class,
            () -> inspector.targetCount("doris-ods-validation", "ods_validation", "patient_ods"));
    assertEquals("TARGET_COUNT_UNAVAILABLE", failure.code());
    assertFalse(failure.getMessage().contains("private-secret"));
  }
}
