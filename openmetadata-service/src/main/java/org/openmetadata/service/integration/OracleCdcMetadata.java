package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.openmetadata.service.integration.IntegrationModels.ColumnDefinition;
import org.openmetadata.service.integration.IntegrationModels.TableDefinition;

final class OracleCdcMetadata {
  private static final String PACKAGES = "('DBMS_LOGMNR','DBMS_LOGMNR_D')";
  private static final Set<String> REQUIRED_PACKAGES = Set.of("DBMS_LOGMNR", "DBMS_LOGMNR_D");
  private final Connection connection;
  private final long deadline;

  OracleCdcMetadata(Connection connection, long deadline) {
    this.connection = connection;
    this.deadline = deadline;
  }

  record Database(String name, String archive, String minimum, String all) {}

  record Table(TableDefinition definition, boolean validPrimaryKey) {}

  enum Coverage {
    COMPLETE,
    INCOMPLETE,
    UNKNOWN
  }

  String version() throws SQLException {
    var rows =
        query(
            "SELECT VERSION FROM PRODUCT_COMPONENT_VERSION WHERE PRODUCT LIKE ?",
            List.of("Oracle Database%"),
            1,
            "VERSION");
    String version = single(rows).get("VERSION");
    if (version == null || !version.matches("[0-9]{1,2}(?:[.][0-9]{1,10}){1,4}")) {
      throw unavailable();
    }
    return version;
  }

  Database database() throws SQLException {
    var row =
        single(
            query(
                "SELECT NAME,LOG_MODE,SUPPLEMENTAL_LOG_DATA_MIN,SUPPLEMENTAL_LOG_DATA_ALL FROM V$DATABASE",
                List.of(),
                1,
                "NAME",
                "LOG_MODE",
                "SUPPLEMENTAL_LOG_DATA_MIN",
                "SUPPLEMENTAL_LOG_DATA_ALL"));
    if (!OracleCdcAdapter.nativeName(row.get("NAME"))) {
      throw unavailable();
    }
    return new Database(
        row.get("NAME"),
        row.get("LOG_MODE"),
        row.get("SUPPLEMENTAL_LOG_DATA_MIN"),
        row.get("SUPPLEMENTAL_LOG_DATA_ALL"));
  }

  String cdb() throws SQLException {
    String cdb = single(query("SELECT CDB FROM V$DATABASE", List.of(), 1, "CDB")).get("CDB");
    if (!Set.of("YES", "NO").contains(value(cdb))) {
      throw unavailable();
    }
    return cdb;
  }

  String container() throws SQLException {
    String container =
        single(
                query(
                    "SELECT SYS_CONTEXT('USERENV','CON_NAME') AS CON_NAME FROM DUAL",
                    List.of(),
                    1,
                    "CON_NAME"))
            .get("CON_NAME");
    if (!"CDB$ROOT".equals(container) && !OracleCdcAdapter.nativeName(container)) {
      throw unavailable();
    }
    return container;
  }

  Table table(String schema, String table) throws SQLException {
    var rows =
        query(
            "SELECT COLUMN_NAME,DATA_TYPE,DATA_PRECISION,DATA_SCALE,DATA_LENGTH,CHAR_LENGTH,NULLABLE FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",
            List.of(schema, table),
            128,
            "COLUMN_NAME",
            "DATA_TYPE",
            "DATA_PRECISION",
            "DATA_SCALE",
            "DATA_LENGTH",
            "CHAR_LENGTH",
            "NULLABLE");
    if (rows.isEmpty()) {
      return new Table(null, false);
    }
    var keyRows =
        query(
            "SELECT C.CONSTRAINT_NAME,C.STATUS,C.VALIDATED,CC.COLUMN_NAME,CC.POSITION FROM ALL_CONSTRAINTS C JOIN ALL_CONS_COLUMNS CC ON CC.OWNER=C.OWNER AND CC.CONSTRAINT_NAME=C.CONSTRAINT_NAME AND CC.TABLE_NAME=C.TABLE_NAME WHERE C.OWNER=? AND C.TABLE_NAME=? AND C.CONSTRAINT_TYPE='P' ORDER BY CC.POSITION",
            List.of(schema, table),
            128,
            "CONSTRAINT_NAME",
            "STATUS",
            "VALIDATED",
            "COLUMN_NAME",
            "POSITION");
    Set<String> keys = new HashSet<>();
    boolean validKey = keyRows.size() == 1;
    for (var key : keyRows) {
      if (!IntegrationValidator.safeIdentifier(key.get("COLUMN_NAME"))
          || !IntegrationValidator.safeIdentifier(key.get("CONSTRAINT_NAME"))
          || !keys.add(key.get("COLUMN_NAME"))) {
        throw unavailable();
      }
      validKey &=
          "ENABLED".equals(key.get("STATUS"))
              && "VALIDATED".equals(key.get("VALIDATED"))
              && "1".equals(key.get("POSITION"));
    }
    List<ColumnDefinition> columns = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (var row : rows) {
      String name = row.get("COLUMN_NAME");
      String type = row.get("DATA_TYPE");
      if (!IntegrationValidator.safeIdentifier(name)
          || !names.add(name)
          || type == null
          || type.isBlank()
          || type.length() > 128
          || !Set.of("Y", "N").contains(value(row.get("NULLABLE")))) {
        throw unavailable();
      }
      type = type.toUpperCase(Locale.ROOT);
      int size =
          type.equals("NUMBER")
              ? integer(row.get("DATA_PRECISION"), 0)
              : integer(row.get("CHAR_LENGTH"), 0);
      int scale = type.equals("DATE") ? 0 : integer(row.get("DATA_SCALE"), 0);
      Integer byteLength =
          row.get("DATA_LENGTH") == null ? null : integer(row.get("DATA_LENGTH"), 0);
      boolean national = Set.of("NCHAR", "NVARCHAR2").contains(type);
      columns.add(
          new ColumnDefinition(
              name,
              type,
              !"N".equals(row.get("NULLABLE")),
              keys.contains(name),
              jdbcType(type),
              size,
              scale,
              false,
              national,
              national ? size / 2 : 0,
              JdbcDialect.Oracle,
              byteLength));
    }
    if (!names.containsAll(keys)) {
      throw unavailable();
    }
    validKey &=
        columns.stream().filter(ColumnDefinition::primaryKey).noneMatch(ColumnDefinition::nullable);
    return new Table(new TableDefinition(schema, table, List.copyOf(columns)), validKey);
  }

  Coverage coverage(Database database, TableDefinition table, List<ColumnDefinition> selected)
      throws SQLException {
    if (database == null) {
      return Coverage.UNKNOWN;
    }
    if ("YES".equals(database.all())) {
      return Coverage.COMPLETE;
    }
    if (!"NO".equals(database.all())) {
      return Coverage.UNKNOWN;
    }
    var groups =
        query(
            "SELECT OWNER,LOG_GROUP_NAME,TABLE_NAME,LOG_GROUP_TYPE,ALWAYS FROM ALL_LOG_GROUPS WHERE OWNER=? AND TABLE_NAME=? ORDER BY LOG_GROUP_NAME",
            List.of(table.schema(), table.name()),
            512,
            "OWNER",
            "LOG_GROUP_NAME",
            "TABLE_NAME",
            "LOG_GROUP_TYPE",
            "ALWAYS");
    Map<String, Boolean> unconditionalUserGroups = new HashMap<>();
    boolean unknown = false;
    for (var group : groups) {
      if (!sameTable(group, table)
          || !IntegrationValidator.safeIdentifier(group.get("LOG_GROUP_NAME"))) {
        throw unavailable();
      }
      String always = group.get("ALWAYS");
      if (!Set.of("ALWAYS", "Y", "CONDITIONAL", "N").contains(value(always))) {
        unknown = true;
        continue;
      }
      boolean unconditional = Set.of("ALWAYS", "Y").contains(always);
      if ("ALL COLUMN LOGGING".equals(group.get("LOG_GROUP_TYPE")) && unconditional) {
        return Coverage.COMPLETE;
      }
      if ("USER LOG GROUP".equals(group.get("LOG_GROUP_TYPE"))) {
        if (unconditionalUserGroups.putIfAbsent(group.get("LOG_GROUP_NAME"), unconditional)
            != null) {
          throw unavailable();
        }
      } else if (!Set.of(
              "PRIMARY KEY LOGGING",
              "UNIQUE KEY LOGGING",
              "FOREIGN KEY LOGGING",
              "ALL COLUMN LOGGING")
          .contains(value(group.get("LOG_GROUP_TYPE")))) {
        unknown = true;
      }
    }
    var members =
        query(
            "SELECT OWNER,LOG_GROUP_NAME,TABLE_NAME,COLUMN_NAME,LOGGING_PROPERTY FROM ALL_LOG_GROUP_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY LOG_GROUP_NAME,POSITION",
            List.of(table.schema(), table.name()),
            512,
            "OWNER",
            "LOG_GROUP_NAME",
            "TABLE_NAME",
            "COLUMN_NAME",
            "LOGGING_PROPERTY");
    Set<String> columnNames = new HashSet<>();
    table.columns().forEach(column -> columnNames.add(column.name()));
    Set<String> covered = new HashSet<>();
    for (var member : members) {
      if (!sameTable(member, table)
          || !IntegrationValidator.safeIdentifier(member.get("LOG_GROUP_NAME"))
          || !columnNames.contains(member.get("COLUMN_NAME"))) {
        throw unavailable();
      }
      String property = member.get("LOGGING_PROPERTY");
      if (!Set.of("LOG", "NO LOG", "NO_LOG").contains(value(property))) {
        unknown = true;
      } else if ("LOG".equals(property)
          && Boolean.TRUE.equals(unconditionalUserGroups.get(member.get("LOG_GROUP_NAME")))) {
        covered.add(member.get("COLUMN_NAME"));
      }
    }
    Set<String> required = new HashSet<>();
    selected.stream()
        .filter(column -> !Set.of(Types.CLOB, Types.NCLOB, Types.BLOB).contains(column.jdbcType()))
        .forEach(column -> required.add(column.name()));
    table.columns().stream()
        .filter(ColumnDefinition::primaryKey)
        .forEach(column -> required.add(column.name()));
    if (covered.containsAll(required)) {
      return Coverage.COMPLETE;
    }
    return unknown ? Coverage.UNKNOWN : Coverage.INCOMPLETE;
  }

  boolean packages() {
    Set<String> packages = new HashSet<>();
    try {
      var rows =
          query(
              "SELECT P.TABLE_NAME,CASE WHEN P.GRANTEE=USER THEN 'DIRECT' WHEN P.GRANTEE='PUBLIC' THEN 'PUBLIC' ELSE 'ENABLED_ROLE' END AS GRANT_PATH,COUNT(*) AS GRANT_COUNT FROM ALL_TAB_PRIVS P WHERE P.TABLE_SCHEMA='SYS' AND P.TABLE_NAME IN "
                  + PACKAGES
                  + " AND P.PRIVILEGE='EXECUTE' AND (P.GRANTEE=USER OR P.GRANTEE='PUBLIC' OR P.GRANTEE IN (SELECT ROLE FROM SESSION_ROLES)) GROUP BY P.TABLE_NAME,CASE WHEN P.GRANTEE=USER THEN 'DIRECT' WHEN P.GRANTEE='PUBLIC' THEN 'PUBLIC' ELSE 'ENABLED_ROLE' END ORDER BY P.TABLE_NAME,GRANT_PATH",
              List.of(),
              512,
              "TABLE_NAME",
              "GRANT_PATH",
              "GRANT_COUNT");
      Set<String> proven = new HashSet<>();
      for (var row : rows) {
        if (!REQUIRED_PACKAGES.contains(value(row.get("TABLE_NAME")))
            || !Set.of("DIRECT", "PUBLIC", "ENABLED_ROLE").contains(value(row.get("GRANT_PATH")))
            || integer(row.get("GRANT_COUNT"), 0) <= 0) {
          throw unavailable();
        }
        proven.add(row.get("TABLE_NAME"));
      }
      packages.addAll(proven);
    } catch (SQLException ignored) {
      // A separate enabled-role dictionary can independently prove an unavailable grant view.
    }
    if (packages.containsAll(REQUIRED_PACKAGES)) {
      return true;
    }
    try {
      var rows =
          query(
              "SELECT P.TABLE_NAME,COUNT(DISTINCT P.ROLE) AS ENABLED_ROLE_GRANT_COUNT FROM ROLE_TAB_PRIVS P JOIN SESSION_ROLES S ON S.ROLE=P.ROLE WHERE P.OWNER='SYS' AND P.TABLE_NAME IN "
                  + PACKAGES
                  + " AND P.PRIVILEGE='EXECUTE' GROUP BY P.TABLE_NAME ORDER BY P.TABLE_NAME",
              List.of(),
              512,
              "TABLE_NAME",
              "ENABLED_ROLE_GRANT_COUNT");
      Set<String> proven = new HashSet<>();
      for (var row : rows) {
        if (!REQUIRED_PACKAGES.contains(value(row.get("TABLE_NAME")))
            || integer(row.get("ENABLED_ROLE_GRANT_COUNT"), 0) <= 0) {
          throw unavailable();
        }
        proven.add(row.get("TABLE_NAME"));
      }
      packages.addAll(proven);
    } catch (SQLException ignored) {
      return false;
    }
    return packages.containsAll(REQUIRED_PACKAGES);
  }

  boolean privileges(int major) throws SQLException {
    var rows =
        query(
            "SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('SELECT ANY TRANSACTION','LOGMINING') ORDER BY PRIVILEGE",
            List.of(),
            16,
            "PRIVILEGE");
    Set<String> privileges = new HashSet<>();
    for (var row : rows) {
      if (!Set.of("SELECT ANY TRANSACTION", "LOGMINING").contains(value(row.get("PRIVILEGE")))) {
        throw unavailable();
      }
      privileges.add(row.get("PRIVILEGE"));
    }
    return major > 0
        && privileges.contains("SELECT ANY TRANSACTION")
        && (major <= 11 || privileges.contains("LOGMINING"));
  }

  boolean views() {
    boolean all = true;
    for (String view :
        List.of("V$LOG", "V$LOGFILE", "V$ARCHIVED_LOG", "V$LOGMNR_LOGS", "V$LOGMNR_CONTENTS")) {
      try {
        all &= query("SELECT 1 FROM " + view + " WHERE 1=0", List.of(), 1, "1").isEmpty();
      } catch (SQLException e) {
        all = false;
      }
    }
    return all;
  }

  private List<Map<String, String>> query(
      String sql, List<String> parameters, int limit, String... columns) throws SQLException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw unavailable();
    }
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(
          (int) Math.min(5, Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L)));
      statement.setMaxRows(limit + 1);
      for (int i = 0; i < parameters.size(); i++) {
        statement.setString(i + 1, parameters.get(i));
      }
      List<Map<String, String>> values = new ArrayList<>();
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (values.size() >= limit || System.nanoTime() >= deadline) {
            throw unavailable();
          }
          Map<String, String> value = new LinkedHashMap<>();
          for (String column : columns) {
            String entry = rows.getString(column);
            if (entry != null && entry.length() > 256) {
              throw unavailable();
            }
            value.put(column, entry);
          }
          values.add(value);
        }
      }
      return values;
    }
  }

  private static boolean sameTable(Map<String, String> row, TableDefinition table) {
    return table.schema().equals(row.get("OWNER")) && table.name().equals(row.get("TABLE_NAME"));
  }

  private static Map<String, String> single(List<Map<String, String>> rows) throws SQLException {
    if (rows.size() != 1) {
      throw unavailable();
    }
    return rows.getFirst();
  }

  private static int integer(String value, int fallback) throws SQLException {
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw unavailable();
    }
  }

  private static int jdbcType(String type) {
    return switch (type) {
      case "NUMBER" -> Types.NUMERIC;
      case "DATE" -> Types.TIMESTAMP;
      case "CHAR" -> Types.CHAR;
      case "VARCHAR2" -> Types.VARCHAR;
      case "NCHAR" -> Types.NCHAR;
      case "NVARCHAR2" -> Types.NVARCHAR;
      case "CLOB" -> Types.CLOB;
      case "NCLOB" -> Types.NCLOB;
      case "BLOB" -> Types.BLOB;
      case "BINARY_FLOAT" -> Types.REAL;
      case "BINARY_DOUBLE" -> Types.DOUBLE;
      default -> type.matches("TIMESTAMP(?:\\([0-9]\\))?") ? Types.TIMESTAMP : Types.OTHER;
    };
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static SQLException unavailable() {
    return new SQLException("ORACLE_CDC_METADATA_UNVERIFIED");
  }
}
