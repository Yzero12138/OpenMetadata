package org.openmetadata.service.integration;

import java.util.List;
import java.util.Properties;
import org.openmetadata.service.integration.IntegrationModels.ConnectionInput;

public enum JdbcDialect {
  Postgres("org.postgresql.Driver"),
  Mysql("com.mysql.cj.jdbc.Driver"),
  Oracle("oracle.jdbc.OracleDriver"),
  Mssql("com.microsoft.sqlserver.jdbc.SQLServerDriver");

  private final String driver;

  JdbcDialect(String driver) {
    this.driver = driver;
  }

  public String driver() {
    return driver;
  }

  public List<String> supportedModes() {
    return this == Postgres ? List.of("FULL", "CDC") : List.of("FULL");
  }

  public String quote(String identifier) {
    if (!IntegrationValidator.safeIdentifier(identifier)) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    return switch (this) {
      case Mysql -> "`" + identifier + "`";
      case Mssql -> "[" + identifier + "]";
      default -> "\"" + identifier + "\"";
    };
  }

  public String jdbcUrl(ConnectionInput input) {
    boolean verify = "VERIFY".equals(input.tlsMode);
    String hostPort = input.host + ":" + input.port;
    return switch (this) {
      case Postgres -> "jdbc:postgresql://"
          + hostPort
          + "/"
          + input.database
          + "?connectTimeout=5&socketTimeout=15&sslmode="
          + (verify ? "verify-full" : "disable");
      case Mysql -> "jdbc:mysql://"
          + hostPort
          + "/"
          + input.database
          + "?connectTimeout=5000&socketTimeout=15000&sslMode="
          + (verify ? "VERIFY_IDENTITY" : "DISABLED")
          + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&allowPublicKeyRetrieval=false";
      case Mssql -> "jdbc:sqlserver://"
          + hostPort
          + ";databaseName="
          + input.database
          + ";encrypt="
          + verify
          + ";trustServerCertificate=false;loginTimeout=5;socketTimeout=15000;sendTimeAsDatetime=false";
      case Oracle -> "jdbc:oracle:thin:@(DESCRIPTION=(TRANSPORT_CONNECT_TIMEOUT=5)(CONNECT_TIMEOUT=5)"
          + "(ADDRESS=(PROTOCOL="
          + (verify ? "TCPS" : "TCP")
          + ")(HOST="
          + input.host
          + ")(PORT="
          + input.port
          + "))"
          + "(CONNECT_DATA=("
          + ("SID".equals(input.oracleConnectionType) ? "SID" : "SERVICE_NAME")
          + "="
          + input.database
          + "))"
          + (verify ? "(SECURITY=(SSL_SERVER_DN_MATCH=yes))" : "")
          + ")";
    };
  }

  public Properties properties(String username, String password) {
    Properties properties = new Properties();
    properties.setProperty("user", username);
    properties.setProperty("password", password);
    switch (this) {
      case Postgres -> {
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        properties.setProperty("ApplicationName", "hospital-integration-inspector");
      }
      case Mysql -> {
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
      }
      case Oracle -> {
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "15000");
        properties.setProperty("oracle.jdbc.timezoneAsRegion", "false");
      }
      case Mssql -> {
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "15000");
      }
    }
    return properties;
  }
}
