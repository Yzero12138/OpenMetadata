package org.openmetadata.service.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Fixed SELECT-only metadata queries sharing one monotonic inspection deadline. */
final class SqlServerCdcMetadata {
  private final Connection connection;
  private final long deadline;
  private final LongSupplier nanoTime;

  SqlServerCdcMetadata(Connection connection, long deadline) {
    this(connection, deadline, System::nanoTime);
  }

  SqlServerCdcMetadata(Connection connection, long deadline, LongSupplier nanoTime) {
    this.connection = connection;
    this.deadline = deadline;
    this.nanoTime = nanoTime;
  }

  Row server() throws SQLException {
    return one(
        "SELECT CONVERT(nvarchar(128), SERVERPROPERTY('ProductVersion')) AS product_version, CONVERT(nvarchar(128), SERVERPROPERTY('ProductMajorVersion')) AS product_major_version, CONVERT(nvarchar(128), SERVERPROPERTY('ProductLevel')) AS product_level, CONVERT(nvarchar(128), SERVERPROPERTY('Edition')) AS edition, CONVERT(int, SERVERPROPERTY('EngineEdition')) AS engine_edition, CONVERT(bigint, SERVERPROPERTY('EditionID')) AS edition_id, CONVERT(int, SERVERPROPERTY('IsClustered')) AS is_clustered, DB_NAME() AS database_name",
        "product_version product_major_version product_level edition engine_edition edition_id is_clustered database_name");
  }

  Row database() throws SQLException {
    return one(
        "SELECT TOP (1) d.database_id, d.name, d.create_date, d.collation_name, d.is_cdc_enabled, d.state, d.is_read_only, d.containment, d.source_database_id, d.is_published, d.is_merge_published, d.is_subscribed, d.is_distributor, d.replica_id, d.group_database_id, m.mirroring_guid FROM sys.databases AS d LEFT JOIN sys.database_mirroring AS m ON m.database_id = d.database_id WHERE d.database_id = DB_ID()",
        "database_id name create_date collation_name is_cdc_enabled state is_read_only containment source_database_id is_published is_merge_published is_subscribed is_distributor replica_id group_database_id mirroring_guid");
  }

  Row adr() throws SQLException {
    return one(
        "SELECT TOP (1) is_accelerated_database_recovery_on FROM sys.databases WHERE database_id = DB_ID()",
        "is_accelerated_database_recovery_on");
  }

  Row table(String schema, String table) throws SQLException {
    return one(
        "SELECT TOP (1) s.name AS schema_name, t.name AS table_name, t.object_id, t.is_tracked_by_cdc, t.is_memory_optimized, t.is_filetable, t.temporal_type, t.is_external, t.is_ms_shipped, CASE WHEN EXISTS (SELECT 1 FROM sys.indexes AS i WHERE i.object_id = t.object_id AND i.type IN (5, 6)) THEN 1 ELSE 0 END AS has_columnstore FROM sys.tables AS t JOIN sys.schemas AS s ON s.schema_id = t.schema_id WHERE s.name = ? AND t.name = ?",
        "schema_name table_name object_id is_tracked_by_cdc is_memory_optimized is_filetable temporal_type is_external is_ms_shipped has_columnstore",
        schema,
        table);
  }

  List<Row> columns(int id) throws SQLException {
    return query(
        "SELECT TOP (1025) c.column_id, c.name, bt.name AS base_type, ut.name AS declared_type, ut.is_user_defined, ut.is_assembly_type, c.max_length, c.precision, c.scale, c.collation_name, c.is_nullable, c.is_computed, c.is_identity, c.is_filestream, c.is_sparse, c.is_column_set, c.generated_always_type, c.encryption_type, c.is_hidden, HAS_PERMS_BY_NAME(QUOTENAME(OBJECT_SCHEMA_NAME(c.object_id)) + N'.' + QUOTENAME(OBJECT_NAME(c.object_id)), N'OBJECT', N'SELECT', c.name, N'COLUMN') AS can_select FROM sys.columns AS c JOIN sys.types AS ut ON ut.user_type_id = c.user_type_id JOIN sys.types AS bt ON bt.user_type_id = c.system_type_id AND bt.user_type_id = bt.system_type_id WHERE c.object_id = ? ORDER BY c.column_id",
        1025,
        "column_id name base_type declared_type is_user_defined is_assembly_type max_length precision scale collation_name is_nullable is_computed is_identity is_filestream is_sparse is_column_set generated_always_type encryption_type is_hidden can_select",
        id);
  }

  List<Row> primaryKey(int id) throws SQLException {
    return query(
        "SELECT TOP (3) c.name, c.column_id, c.is_nullable, i.is_unique, i.is_disabled, i.is_hypothetical, ic.key_ordinal FROM sys.indexes AS i JOIN sys.index_columns AS ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id JOIN sys.columns AS c ON c.object_id = ic.object_id AND c.column_id = ic.column_id WHERE i.object_id = ? AND i.is_primary_key = 1 AND ic.key_ordinal > 0 ORDER BY ic.key_ordinal",
        3,
        "name column_id is_nullable is_unique is_disabled is_hypothetical key_ordinal",
        id);
  }

  List<Row> captures(int id) throws SQLException {
    return query(
        "SELECT TOP (3) ct.object_id, ct.source_object_id, ct.capture_instance, ct.has_drop_pending, ct.role_name, ct.index_name, ct.create_date, ct.partition_switch, ct.start_lsn FROM cdc.change_tables AS ct WHERE ct.source_object_id = ? ORDER BY ct.capture_instance",
        3,
        "object_id source_object_id capture_instance has_drop_pending role_name index_name create_date partition_switch start_lsn",
        id);
  }

  List<Row> captured(int id) throws SQLException {
    return query(
        "SELECT TOP (1025) cc.column_id, cc.column_name, cc.column_ordinal, cc.column_type, cc.is_computed FROM cdc.captured_columns AS cc WHERE cc.object_id = ? ORDER BY cc.column_ordinal",
        1025,
        "column_id column_name column_ordinal column_type is_computed",
        id);
  }

  Row permissions(String role, String capture) throws SQLException {
    return one(
        "SELECT IS_SRVROLEMEMBER(N'sysadmin') AS is_sysadmin, IS_ROLEMEMBER(N'db_owner') AS is_db_owner, IS_ROLEMEMBER(?) AS is_gating_role_member, HAS_PERMS_BY_NAME(?, N'OBJECT', N'SELECT') AS can_select_changes, HAS_PERMS_BY_NAME(DB_NAME(), N'DATABASE', N'VIEW DATABASE STATE') AS can_view_database_state, HAS_PERMS_BY_NAME(NULL, NULL, N'VIEW SERVER STATE') AS can_view_server_state",
        "is_sysadmin is_db_owner is_gating_role_member can_select_changes can_view_database_state can_view_server_state",
        role,
        "[cdc].[fn_cdc_get_all_changes_" + capture.replace("]", "]]") + "]");
  }

  Row nativeAccess() throws SQLException {
    return one(
        "SELECT HAS_PERMS_BY_NAME(N'cdc.change_tables', N'OBJECT', N'SELECT') AS change_tables, HAS_PERMS_BY_NAME(N'cdc.captured_columns', N'OBJECT', N'SELECT') AS captured_columns, HAS_PERMS_BY_NAME(N'cdc.ddl_history', N'OBJECT', N'SELECT') AS ddl_history, HAS_PERMS_BY_NAME(N'cdc.lsn_time_mapping', N'OBJECT', N'SELECT') AS lsn_time_mapping",
        "change_tables captured_columns ddl_history lsn_time_mapping");
  }

  Row lsn(String capture) throws SQLException {
    return one(
        "SELECT sys.fn_cdc_get_min_lsn(?) AS min_lsn, sys.fn_cdc_get_max_lsn() AS max_lsn",
        "min_lsn max_lsn",
        capture);
  }

  List<Row> agent() throws SQLException {
    return query(
        "SELECT TOP (2) status, startup_type, CONVERT(datetime2, last_startup_time) AS last_startup_time, GETDATE() AS server_now FROM sys.dm_server_services WHERE servicename = N'SQL Server Agent (' + COALESCE(CONVERT(nvarchar(128), SERVERPROPERTY('InstanceName')), N'MSSQLSERVER') + N')'",
        2,
        "status startup_type last_startup_time server_now");
  }

  List<Row> jobs() throws SQLException {
    return query(
        "SELECT TOP (3) cj.job_type, cj.continuous, cj.retention, cj.pollinginterval, j.enabled, a.start_execution_date, a.stop_execution_date, a.next_scheduled_run_date, CASE WHEN EXISTS (SELECT 1 FROM msdb.dbo.sysjobschedules AS js JOIN msdb.dbo.sysschedules AS sch ON sch.schedule_id = js.schedule_id WHERE js.job_id = cj.job_id AND sch.enabled = 1 AND sch.active_start_date <= CONVERT(int, CONVERT(char(8), GETDATE(), 112)) AND sch.active_end_date >= CONVERT(int, CONVERT(char(8), GETDATE(), 112))) THEN 1 ELSE 0 END AS has_enabled_schedule FROM msdb.dbo.cdc_jobs AS cj LEFT JOIN msdb.dbo.sysjobs AS j ON j.job_id = cj.job_id LEFT JOIN msdb.dbo.sysjobactivity AS a ON a.job_id = cj.job_id AND a.session_id = (SELECT MAX(session_id) FROM msdb.dbo.sysjobactivity) WHERE cj.database_id = DB_ID() ORDER BY cj.job_type",
        3,
        "job_type continuous retention pollinginterval enabled start_execution_date stop_execution_date next_scheduled_run_date has_enabled_schedule");
  }

  Row scan() throws SQLException {
    return one(
        "SELECT TOP (1) session_id, start_time, end_time, scan_phase, error_count FROM sys.dm_cdc_log_scan_sessions WHERE session_id > 0 ORDER BY session_id DESC",
        "session_id start_time end_time scan_phase error_count");
  }

  List<Row> errors() throws SQLException {
    return query(
        "SELECT TOP (32) session_id, entry_time, error_number FROM sys.dm_cdc_errors ORDER BY entry_time DESC",
        32,
        "session_id entry_time error_number");
  }

  private Row one(String sql, String fields, Object... params) throws SQLException {
    var rows = query(sql, 2, fields, params);
    return rows.size() == 1 ? rows.getFirst() : Row.EMPTY;
  }

  private List<Row> query(String sql, int limit, String fields, Object... params)
      throws SQLException {
    remaining();
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout((int) Math.min(5, Math.max(1, remaining() / 1_000_000_000L)));
      statement.setMaxRows(limit);
      for (int index = 0; index < params.length; index++) {
        statement.setObject(index + 1, params[index]);
      }
      try (var results = statement.executeQuery()) {
        List<Row> rows = new ArrayList<>();
        while (results.next()) {
          remaining();
          Map<String, Object> values = new LinkedHashMap<>();
          for (String field : fields.split(" ")) {
            values.put(field, results.getObject(field));
          }
          rows.add(new Row(values));
          if (rows.size() >= limit) {
            throw new SQLException("MSSQL_CDC_METADATA_LIMIT");
          }
        }
        remaining();
        return List.copyOf(rows);
      }
    }
  }

  private long remaining() throws SQLException {
    long value = deadline - nanoTime.getAsLong();
    if (value <= 0) {
      throw new SQLException("MSSQL_CDC_METADATA_DEADLINE");
    }
    return value;
  }

  record Row(Map<String, Object> values) {
    static final Row EMPTY = new Row(Map.of());

    Row {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    String text(String key) {
      Object value = values.get(key);
      return value == null ? null : value.toString();
    }

    Integer number(String key) {
      Object value = values.get(key);
      if (value instanceof Number number) return number.intValue();
      if (value instanceof Boolean bool) return bool ? 1 : 0;
      try {
        return value == null ? null : Integer.valueOf(value.toString());
      } catch (NumberFormatException e) {
        return null;
      }
    }

    boolean is(String key, int expected) {
      return Integer.valueOf(expected).equals(number(key));
    }

    boolean known(String key) {
      return values.get(key) != null;
    }

    LocalDateTime time(String key) {
      Object value = values.get(key);
      return value instanceof Timestamp timestamp
          ? timestamp.toLocalDateTime()
          : value instanceof LocalDateTime time ? time : null;
    }

    byte[] bytes(String key) {
      Object value = values.get(key);
      return value instanceof byte[] bytes ? bytes.clone() : null;
    }
  }
}
