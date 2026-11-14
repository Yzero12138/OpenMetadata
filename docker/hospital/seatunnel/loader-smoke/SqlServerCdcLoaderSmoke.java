package org.openmetadata.integration.seatunnel;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Exercises released SQL Server CDC factories and synthetic events without opening a database. */
public final class SqlServerCdcLoaderSmoke {
  private static final String API = "org.apache.seatunnel.api.";
  private static final String SQLSERVER =
      "org.apache.seatunnel.connectors.seatunnel.cdc.sqlserver.";
  private static final String CDC_ROW = "org.apache.seatunnel.connectors.cdc.debezium.row.";
  private static final String TABLE = "SmokeClinic.dbo.SmokeOrders";
  private static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

  private SqlServerCdcLoaderSmoke() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Supply the SeaTunnel runtime directory");
    }
    Path runtime = Path.of(args[0]).toRealPath();
    requireArtifact(
        runtime.resolve("connectors/connector-cdc-sqlserver-3.0.0.jar"),
        12484442,
        "85bb3d911e5f387e4c5570868914928fc674b9cee2acaee603505450a4b876de");
    requireArtifact(
        runtime.resolve("connectors/connector-cdc-oracle-3.0.0.jar"),
        31590975,
        "a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f");
    requireArtifact(
        runtime.resolve("lib/mssql-jdbc-13.6.0.jre11.jar"),
        1576575,
        "4c566e4022c4dcf1489aeb639fe49a0f6ffae3d796fdc2f56b5b8882d4c3bf5a");
    List<URL> common = new ArrayList<>();
    common.add(runtime.resolve("starter/seatunnel-starter.jar").toUri().toURL());
    addJars(common, runtime.resolve("lib"));
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader engine =
        new URLClassLoader(common.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      Thread.currentThread().setContextClassLoader(engine);
      List<URL> connectors = discoverPluginJars(engine, runtime);
      try (URLClassLoader isolated = new URLClassLoader(connectors.toArray(URL[]::new), engine)) {
        Thread.currentThread().setContextClassLoader(isolated);
        requireClass(engine, DRIVER, "mssql-jdbc-13.6.0.jre11.jar");
        requireClass(
            isolated,
            "io.debezium.connector.sqlserver.SqlServerValueConverters",
            "connector-cdc-sqlserver-3.0.0.jar");
        requireClass(
            isolated,
            "org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.sqlserver.SqlServerTypeConverter",
            "connector-cdc-sqlserver-3.0.0.jar");
        requireClass(
            isolated,
            "org.apache.seatunnel.connectors.cdc.base.source.IncrementalSource",
            "connector-cdc-base-3.0.0.jar");
        Class<?> factoryUtil = type(isolated, API + "table.factory.FactoryUtil");
        Class<?> sourceFactory = type(isolated, API + "table.factory.TableSourceFactory");
        Object factory =
            call(factoryUtil, "discoverFactory", isolated, sourceFactory, "SqlServer-CDC");
        require(
            factory.getClass().getClassLoader() == isolated,
            "SQL Server factory escaped its loader");
        Object rule = call(factoryUtil, "sourceFullOptionRule", factory);
        require(
            !((List<?>) call(rule, "getRequiredOptions")).isEmpty(), "Missing required options");
        require(
            !((List<?>) call(rule, "getOptionalOptions")).isEmpty(), "Missing optional options");
        System.out.println("PASS isolated SqlServer-CDC factory=" + factory.getClass().getName());
        Object source = verifySourceConfiguration(isolated, factory);
        verifyTypesAndEvents(isolated, source, runtime);
        verifyMicrosecondBoundary(isolated);
        System.out.println(
            "PASS offline native factory/configuration/synthetic-event/driver smoke only; "
                + "no SQL Server CDC or database acceptance; Java "
                + Runtime.version());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static List<URL> discoverPluginJars(ClassLoader engine, Path runtime) throws Exception {
    Class<?> sourceFactory = type(engine, API + "table.factory.TableSourceFactory");
    Object discovery =
        type(engine, "org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelFactoryDiscovery")
            .getConstructor(Class.class)
            .newInstance(sourceFactory);
    List<URL> sqlServerJars = new ArrayList<>();
    for (String plugin : List.of("SqlServer-CDC", "Oracle-CDC", "Postgres-CDC", "Jdbc")) {
      Object identifier =
          call(type(engine, API + "common.PluginIdentifier"), "of", "seatunnel", "source", plugin);
      List<?> jars =
          (List<?>) call(discovery, "getPluginJarAndDependencyPaths", List.of(identifier));
      require(!jars.isEmpty(), "Native plugin discovery returned no jars: " + plugin);
      List<String> names = new ArrayList<>();
      for (Object jar : jars) {
        URL url = (URL) jar;
        Path path = Path.of(url.toURI()).toRealPath();
        require(path.startsWith(runtime), "Native plugin dependency escaped runtime");
        names.add(path.getFileName().toString());
        if ("SqlServer-CDC".equals(plugin)) {
          sqlServerJars.add(url);
        }
      }
      if (plugin.endsWith("-CDC")) {
        require(names.contains("connector-cdc-base-3.0.0.jar"), "Native CDC dependency missing");
      }
      System.out.println("PASS native plugin discovery " + plugin + " -> " + names);
    }
    require(sqlServerJars.size() == 2, "Unexpected SQL Server connector dependency set");
    return sqlServerJars;
  }

  private static Object verifySourceConfiguration(ClassLoader loader, Object factory)
      throws Exception {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", "INT");
    fields.put("quantity", "SMALLINT");
    fields.put("sequence_no", "BIGINT");
    fields.put("amount", "DECIMAL(38,6)");
    fields.put("description", "STRING");
    fields.put("order_date", "DATE");
    fields.put("order_time", "TIMESTAMP");
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("plugin_name", "SqlServer-CDC");
    options.put(
        "url",
        "jdbc:sqlserver://smoke.invalid:1433;databaseName=SmokeClinic;encrypt=true;"
            + "trustServerCertificate=false;hostNameInCertificate=smoke.invalid");
    options.put("username", "smoke_user");
    options.put("password", "synthetic_unused");
    options.put("database-names", List.of("SmokeClinic"));
    options.put("table-names", List.of(TABLE));
    options.put("startup.mode", "INITIAL");
    options.put("incremental.parallelism", 1);
    options.put("exactly_once", false);
    options.put("schema-changes.enabled", false);
    options.put("server-time-zone", "Asia/Shanghai");
    options.put(
        "schema",
        Map.of(
            "table",
            TABLE,
            "schema_first",
            true,
            "fields",
            fields,
            "primaryKey",
            Map.of("name", "smoke_pk", "columnNames", List.of("id"))));
    Class<?> readonlyType = type(loader, API + "configuration.ReadonlyConfig");
    Object readonly = call(readonlyType, "fromMap", options);
    Object validator =
        call(type(loader, API + "configuration.util.ConfigValidator"), "of", readonly);
    call(validator, "validate", call(factory, "optionRule"));
    Object context =
        type(loader, API + "table.factory.TableSourceFactoryContext")
            .getConstructor(readonlyType, ClassLoader.class)
            .newInstance(readonly, loader);
    Object tableSource = call(factory, "createSource", context);
    Object source = call(tableSource, "createSource");
    require("SqlServer-CDC".equals(call(source, "getPluginName")), "Unexpected source plugin");
    Object configFactory = call(source, "createSourceConfigFactory", readonly);
    Object config = call(configFactory, "create", 0);
    require("smoke.invalid".equals(call(config, "getHostname")), "URL hostname changed");
    require(Integer.valueOf(1433).equals(call(config, "getPort")), "Explicit port changed");
    require(DRIVER.equals(call(config, "getDriverClassName")), "Unexpected JDBC driver");
    require(
        List.of("SmokeClinic").equals(call(config, "getDatabaseList")), "Database scope changed");
    require(List.of(TABLE).equals(call(config, "getTableList")), "Table scope changed");
    require(Boolean.FALSE.equals(call(config, "isExactlyOnce")), "Delivery mode changed");
    Object startup = call(config, "getStartupConfig");
    require(
        "INITIAL".equals(((Enum<?>) call(startup, "getStartupMode")).name()),
        "Startup mode changed");
    Object debezium = call(config, "getDbzConfiguration");
    for (Map.Entry<String, String> expected :
        Map.of(
                "database.encrypt", "true",
                "database.trustServerCertificate", "false",
                "database.hostNameInCertificate", "smoke.invalid",
                "database.dbname", "SmokeClinic",
                "table.include.list", "dbo.SmokeOrders")
            .entrySet()) {
      require(
          expected.getValue().equals(call(debezium, "getString", expected.getKey())),
          "URL TLS or table setting did not reach Debezium: " + expected.getKey());
    }
    System.out.println(
        "PASS native factory created source with explicit schema, singleton database/table, "
            + "INITIAL, at-least-once and URL TLS properties forwarded into Debezium");
    return source;
  }

  private static void verifyTypesAndEvents(ClassLoader loader, Object source, Path runtime)
      throws Exception {
    List<ColumnCase> columns =
        List.of(
            new ColumnCase("id", "INT", Types.INTEGER, 10, 0, "INT"),
            new ColumnCase("quantity", "TINYINT", Types.TINYINT, 3, 0, "SMALLINT"),
            new ColumnCase("sequence_no", "BIGINT", Types.BIGINT, 19, 0, "BIGINT"),
            new ColumnCase("amount", "DECIMAL", Types.DECIMAL, 38, 6, "Decimal(38, 6)"),
            new ColumnCase("description", "NVARCHAR", Types.NVARCHAR, 200, 0, "STRING"),
            new ColumnCase("order_date", "DATE", Types.DATE, 10, 0, "DATE"),
            new ColumnCase("order_time", "datetime2", Types.TIMESTAMP, 23, 3, "TIMESTAMP"));
    Class<?> schemaBuilder = type(loader, "org.apache.kafka.connect.data.SchemaBuilder");
    Class<?> schemaType = type(loader, "org.apache.kafka.connect.data.Schema");
    Object rowBuilder = call(schemaBuilder, "struct");
    call(rowBuilder, "name", "smoke.SqlServerRow");
    call(rowBuilder, "optional");
    Object sqlConverters =
        type(loader, "io.debezium.connector.sqlserver.SqlServerValueConverters")
            .getConstructor()
            .newInstance();
    List<Object> valueConverters = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      ColumnCase item = columns.get(i);
      Object column = column(loader, item);
      Object dataType =
          call(type(loader, SQLSERVER + "utils.SqlServerTypeUtils"), "convertFromColumn", column);
      require(
          item.expectedType().equals(dataType.toString()),
          "Unexpected type for " + item.sqlType() + ": " + dataType);
      Object fieldSchema = call(call(sqlConverters, "schemaBuilder", column), "build");
      if (item.jdbcType() == Types.TIMESTAMP) {
        require(
            "io.debezium.time.Timestamp".equals(call(fieldSchema, "name")),
            "datetime2(3) is not milliseconds");
      }
      call(rowBuilder, "field", item.name(), fieldSchema);
      Object field =
          type(loader, "org.apache.kafka.connect.data.Field")
              .getConstructor(String.class, int.class, schemaType)
              .newInstance(item.name(), i, fieldSchema);
      valueConverters.add(call(sqlConverters, "converter", column, field));
      System.out.println("PASS native SqlServerTypeUtils " + item.sqlType() + " -> " + dataType);
    }
    Object rowSchema = call(rowBuilder, "build");
    Object sourceBuilder = call(schemaBuilder, "struct");
    call(sourceBuilder, "name", "smoke.SqlServerSource");
    for (String field : List.of("db", "schema", "table")) {
      call(sourceBuilder, "field", field, schemaType.getField("STRING_SCHEMA").get(null));
    }
    Object sourceSchema = call(sourceBuilder, "build");
    Object eventBuilder = call(schemaBuilder, "struct");
    call(eventBuilder, "name", "smoke.SqlServerEnvelope");
    call(eventBuilder, "field", "before", rowSchema);
    call(eventBuilder, "field", "after", rowSchema);
    call(eventBuilder, "field", "source", sourceSchema);
    call(eventBuilder, "field", "op", schemaType.getField("STRING_SCHEMA").get(null));
    Object eventSchema = call(eventBuilder, "build");
    Object sourceStruct = struct(loader, sourceSchema);
    call(sourceStruct, "put", "db", "SmokeClinic");
    call(sourceStruct, "put", "schema", "dbo");
    call(sourceStruct, "put", "table", "SmokeOrders");
    Object[] early = {
      Integer.MIN_VALUE,
      (short) 0,
      Long.MIN_VALUE,
      new BigDecimal("-12345678901234567890123456789012.123456"),
      "合成医院中文🙂",
      LocalDate.of(1, 1, 1),
      LocalDateTime.of(1, 1, 1, 0, 0, 0)
    };
    Object[] late = {
      Integer.MAX_VALUE,
      (short) 255,
      Long.MAX_VALUE,
      new BigDecimal("12345678901234567890123456789012.654321"),
      "合成更新中文𠮷",
      LocalDate.of(9999, 12, 31),
      LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999000000)
    };
    Object before = convertedStruct(loader, rowSchema, columns, valueConverters, early);
    Object after = convertedStruct(loader, rowSchema, columns, valueConverters, late);
    Object deserializeBuilder =
        call(type(loader, CDC_ROW + "SeaTunnelRowDebeziumDeserializeSchema"), "builder");
    call(deserializeBuilder, "setTables", call(source, "getProducedCatalogTables"));
    call(deserializeBuilder, "setServerTimeZone", ZoneId.of("Asia/Shanghai"));
    Object deserializer = call(deserializeBuilder, "build");
    Class<?> collectorType = type(loader, API + "source.Collector");
    List<Object> rows = new ArrayList<>();
    Object collector =
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {collectorType},
            (proxy, method, args) -> {
              if ("collect".equals(method.getName())) {
                rows.add(args[0]);
              }
              return "getCheckpointLock".equals(method.getName()) ? rows : null;
            });
    for (String operation : List.of("r", "c", "u", "d")) {
      Object event = struct(loader, eventSchema);
      call(event, "put", "source", sourceStruct);
      call(event, "put", "op", operation);
      call(event, "put", "before", List.of("u", "d").contains(operation) ? before : null);
      call(
          event,
          "put",
          "after",
          "d".equals(operation) ? null : "r".equals(operation) ? before : after);
      Object record =
          type(loader, "org.apache.kafka.connect.source.SourceRecord")
              .getConstructor(Map.class, Map.class, String.class, schemaType, Object.class)
              .newInstance(
                  Map.of("server", "synthetic"),
                  Map.of("commit_lsn", "00000000:00000000:0000"),
                  TABLE,
                  eventSchema,
                  event);
      call(deserializer, "deserialize", record, collector);
    }
    require(rows.size() == 5, "Unexpected number of native change rows");
    List<String> kinds = List.of("INSERT", "INSERT", "UPDATE_BEFORE", "UPDATE_AFTER", "DELETE");
    List<Object[]> expected = List.of(early, late, early, late, early);
    for (int i = 0; i < rows.size(); i++) {
      Object row = rows.get(i);
      require(kinds.get(i).equals(call(row, "getRowKind").toString()), "Unexpected RowKind");
      require(TABLE.equals(call(row, "getTableId")), "Qualified table identity changed");
      Object[] actual = (Object[]) call(row, "getFields");
      require(
          Arrays.equals(expected.get(i), actual),
          "Native row value changed: " + Arrays.toString(actual));
    }
    verifyDorisDeleteSerialization(loader.getParent(), runtime, source, rows, expected);
    Object timestampConverter = valueConverters.get(columns.size() - 1);
    Object timestampSchema =
        call(
            call(sqlConverters, "schemaBuilder", column(loader, columns.get(columns.size() - 1))),
            "build");
    Method timestampFactory =
        type(loader, CDC_ROW + "SeaTunnelRowDebeziumDeserializationConverters")
            .getDeclaredMethod("convertToTimestamp", ZoneId.class);
    timestampFactory.setAccessible(true);
    Object timestampRowConverter = timestampFactory.invoke(null, ZoneId.of("Asia/Shanghai"));
    LocalDateTime negativeMillisecond = LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999000000);
    Object encodedNegative = call(timestampConverter, "convert", negativeMillisecond);
    require(Long.valueOf(-1).equals(encodedNegative), "Pre-epoch SQL Server milliseconds changed");
    require(
        negativeMillisecond.equals(
            call(timestampRowConverter, "convert", encodedNegative, timestampSchema)),
        "Pre-epoch datetime2(3) shifted");
    System.out.println(
        "PASS native SQL Server converters -> SourceRecord -> SeaTunnel rows: snapshot/insert, "
            + "UPDATE_BEFORE, UPDATE_AFTER, DELETE with full before image; INT/BIGINT limits, "
            + "TINYINT 0/255 -> Short, DECIMAL(38,6), Unicode, DATE 0001/9999, "
            + "datetime2(3) 0001/9999/-1ms and no zone shift");
  }

  private static void verifyDorisDeleteSerialization(
      ClassLoader engine, Path runtime, Object source, List<Object> rows, List<Object[]> expected)
      throws Exception {
    URL connector = runtime.resolve("connectors/connector-doris-3.0.0.jar").toUri().toURL();
    try (URLClassLoader doris = new URLClassLoader(new URL[] {connector}, engine)) {
      ClassLoader original = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(doris);
      try {
        List<?> tables = (List<?>) call(source, "getProducedCatalogTables");
        Object rowType = call(tables.get(0), "getSeaTunnelRowType");
        Class<?> serializerType =
            type(doris, "org.apache.seatunnel.connectors.doris.serialize.SeaTunnelRowSerializer");
        Object serializer =
            serializerType
                .getConstructor(
                    String.class,
                    type(engine, API + "table.type.SeaTunnelRowType"),
                    String.class,
                    boolean.class)
                .newInstance("json", rowType, "\t", true);
        List<String> signs = List.of("0", "0", "1", "0", "1");
        for (int i = 0; i < rows.size(); i++) {
          String json =
              new String(
                  (byte[]) call(serializer, "serialize", rows.get(i)), StandardCharsets.UTF_8);
          require(
              json.contains("\"__DORIS_DELETE_SIGN__\":\"" + signs.get(i) + "\""),
              "Doris delete sign changed");
          require(
              json.contains(((BigDecimal) expected.get(i)[3]).toPlainString()),
              "Doris decimal lost precision");
          require(json.contains(expected.get(i)[4].toString()), "Doris Unicode changed");
        }
        System.out.println(
            "PASS native Doris JSON serializer preserves synthetic decimal/Unicode and maps INSERT/UPDATE_AFTER=0, UPDATE_BEFORE/DELETE=1; no sink connection");
      } finally {
        Thread.currentThread().setContextClassLoader(original);
      }
    }
  }

  private static Object column(ClassLoader loader, ColumnCase item) throws Exception {
    Object editor = call(type(loader, "io.debezium.relational.Column"), "editor");
    call(editor, "name", item.name());
    call(editor, "type", item.sqlType());
    call(editor, "jdbcType", item.jdbcType());
    call(editor, "length", item.length());
    call(editor, "scale", item.scale());
    return call(editor, "create");
  }

  private static Object convertedStruct(
      ClassLoader loader,
      Object schema,
      List<ColumnCase> columns,
      List<Object> converters,
      Object[] values)
      throws Exception {
    Object value = struct(loader, schema);
    for (int i = 0; i < values.length; i++) {
      call(value, "put", columns.get(i).name(), call(converters.get(i), "convert", values[i]));
    }
    return value;
  }

  private static void verifyMicrosecondBoundary(ClassLoader loader) throws Exception {
    Class<?> converters = type(loader, CDC_ROW + "SeaTunnelRowDebeziumDeserializationConverters");
    Method factory = converters.getDeclaredMethod("convertToTimestamp", ZoneId.class);
    factory.setAccessible(true);
    Object converter = factory.invoke(null, ZoneId.of("Asia/Shanghai"));
    Object schema = call(type(loader, "io.debezium.time.MicroTimestamp"), "schema");
    try {
      call(converter, "convert", -1L, schema);
      throw new IllegalStateException(
          "Reassess datetime2 precision guard: microsecond boundary now succeeds");
    } catch (DateTimeException expected) {
      System.out.println(
          "PASS known native pre-epoch MicroTimestamp -1us throws DateTimeException; "
              + "platform must block datetime2 precision >3 until separately corrected and verified");
    }
  }

  private static Object struct(ClassLoader loader, Object schema) throws Exception {
    return type(loader, "org.apache.kafka.connect.data.Struct")
        .getConstructor(type(loader, "org.apache.kafka.connect.data.Schema"))
        .newInstance(schema);
  }

  private static Class<?> type(ClassLoader loader, String name) throws Exception {
    return Class.forName(name, true, loader);
  }

  private static Object call(Object target, String name, Object... args) throws Exception {
    Class<?> owner = target instanceof Class<?> ? (Class<?>) target : target.getClass();
    for (Method method : owner.getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
        continue;
      }
      Class<?>[] parameters = method.getParameterTypes();
      boolean matches = true;
      for (int i = 0; i < args.length; i++) {
        Class<?> parameter = parameters[i];
        if (parameter.isPrimitive()) {
          parameter = Array.get(Array.newInstance(parameter, 1), 0).getClass();
        }
        matches &= args[i] == null ? !parameters[i].isPrimitive() : parameter.isInstance(args[i]);
      }
      if (matches) {
        method.setAccessible(true);
        try {
          return method.invoke(target instanceof Class<?> ? null : target, args);
        } catch (InvocationTargetException error) {
          if (error.getCause() instanceof Exception exception) {
            throw exception;
          }
          throw error;
        }
      }
    }
    throw new NoSuchMethodException(owner.getName() + "." + name + "/" + args.length);
  }

  private static void addJars(List<URL> jars, Path directory) throws Exception {
    try (Stream<Path> entries = Files.walk(directory)) {
      for (Path jar : entries.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
        jars.add(jar.toUri().toURL());
      }
    }
  }

  private static void requireClass(ClassLoader loader, String name, String artifact)
      throws Exception {
    Class<?> loaded = type(loader, name);
    String actual =
        Path.of(loaded.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getFileName()
            .toString();
    require(artifact.equals(actual), "Unexpected artifact for " + name + ": " + actual);
    System.out.println("PASS class " + name + " from " + actual);
  }

  private static void requireArtifact(Path jar, long bytes, String expectedHash) throws Exception {
    require(Files.isRegularFile(jar), "Missing pinned artifact: " + jar.getFileName());
    require(Files.size(jar) == bytes, "Unexpected artifact size: " + jar.getFileName());
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(jar)) {
      byte[] chunk = new byte[65536];
      int count;
      while ((count = input.read(chunk)) != -1) {
        digest.update(chunk, 0, count);
      }
    }
    require(
        expectedHash.equals(HexFormat.of().formatHex(digest.digest())),
        "Unexpected artifact checksum: " + jar.getFileName());
    System.out.println("PASS SHA-256 " + jar.getFileName());
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record ColumnCase(
      String name, String sqlType, int jdbcType, int length, int scale, String expectedType) {}
}
