package org.openmetadata.service.integration;

import java.util.List;
import java.util.Map;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;

public final class CdcAdapters {
  private static final Map<JdbcDialect, CdcSourceAdapter> ADAPTERS =
      Map.of(
          JdbcDialect.Postgres,
          new PostgresCdcAdapter(),
          JdbcDialect.Oracle,
          new OracleCdcAdapter(),
          JdbcDialect.Mssql,
          new SqlServerCdcAdapter());

  private CdcAdapters() {}

  public static boolean implemented(JdbcDialect dialect) {
    return ADAPTERS.containsKey(dialect);
  }

  public static CdcSourceAdapter require(JdbcDialect dialect) {
    CdcSourceAdapter adapter = ADAPTERS.get(dialect);
    if (adapter == null) {
      throw new IntegrationException("MODE_UNSUPPORTED", 400);
    }
    return adapter;
  }

  public static List<String> supportedModes(JdbcDialect dialect) {
    return implemented(dialect) ? List.of("FULL", "CDC") : List.of("FULL");
  }

  public static List<String> supportedModes(ConnectionInput input) {
    if (input == null) {
      return List.of("FULL");
    }
    if ("Oracle".equals(input.databaseType)) {
      return supportedModes(JdbcDialect.Oracle);
    }
    if ("Mssql".equals(input.databaseType)) {
      return supportedModes(JdbcDialect.Mssql);
    }
    if ("Postgres".equals(input.databaseType)
        && input.database != null
        && !input.database.contains(".")
        && input.databaseVersion != null
        && input.databaseVersion.matches("[0-9]{1,2}(?:[.][0-9]+)*")
        && Integer.parseInt(input.databaseVersion.split("[.]", 2)[0]) >= 10) {
      return supportedModes(JdbcDialect.Postgres);
    }
    return List.of("FULL");
  }
}
