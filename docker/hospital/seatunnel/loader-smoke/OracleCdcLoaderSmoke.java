package org.openmetadata.integration.seatunnel;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/** Loads pinned connector factories and selected type/driver paths without opening a database. */
public final class OracleCdcLoaderSmoke {
  private static final String API = "org.apache.seatunnel.api.table.factory.";
  private static final String ORACLE = "org.apache.seatunnel.connectors.seatunnel.cdc.oracle.";

  private OracleCdcLoaderSmoke() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Supply the SeaTunnel runtime directory");
    }
    Path runtime = Path.of(args[0]).toRealPath();
    requireArtifact(
        runtime.resolve("connectors/connector-cdc-oracle-3.0.0.jar"),
        31590975,
        "a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f");
    requireArtifact(
        runtime.resolve("lib/ojdbc8-19.32.0.0.jar"),
        -1,
        "d5ae03e2d3104f38b45666cdc79fa3c9947858f05bb4f02e227900f10c52eeb9");
    requireArtifact(
        runtime.resolve("lib/orai18n-19.32.0.0.jar"),
        -1,
        "b709d6cee59d23658b4dbd66cd1088b65380b43c68678bc107f2a1a5cd112c56");
    List<URL> common = new ArrayList<>();
    common.add(runtime.resolve("starter/seatunnel-starter.jar").toUri().toURL());
    addJars(common, runtime.resolve("lib"));
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader engine =
        new URLClassLoader(common.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      Thread.currentThread().setContextClassLoader(engine);
      Class<?> factoryUtil = Class.forName(API + "FactoryUtil", true, engine);
      Class<?> sourceFactory = Class.forName(API + "TableSourceFactory", true, engine);
      for (String plugin : List.of("Oracle-CDC", "Jdbc", "Postgres-CDC")) {
        try (URLClassLoader isolated =
            new URLClassLoader(connectorJars(runtime, plugin).toArray(URL[]::new), engine)) {
          Thread.currentThread().setContextClassLoader(isolated);
          Object factory =
              factoryUtil
                  .getMethod("discoverFactory", ClassLoader.class, Class.class, String.class)
                  .invoke(null, isolated, sourceFactory, plugin);
          if (factory.getClass().getClassLoader() != isolated) {
            throw new IllegalStateException("Connector factory escaped its isolated loader");
          }
          Object rule =
              factoryUtil.getMethod("sourceFullOptionRule", sourceFactory).invoke(null, factory);
          int required =
              ((List<?>) rule.getClass().getMethod("getRequiredOptions").invoke(rule)).size();
          int optional =
              ((List<?>) rule.getClass().getMethod("getOptionalOptions").invoke(rule)).size();
          if (required == 0 || optional == 0) {
            throw new IllegalStateException("Factory option rules are incomplete");
          }
          System.out.println(
              "PASS isolated "
                  + plugin
                  + " factory="
                  + factory.getClass().getName()
                  + " required="
                  + required
                  + " optional="
                  + optional);
          if (plugin.endsWith("-CDC")) {
            requireClass(
                isolated,
                "org.apache.seatunnel.connectors.cdc.base.source.IncrementalSource",
                "connector-cdc-base-3.0.0.jar");
          }
          if ("Oracle-CDC".equals(plugin)) {
            verifyOracleDate(isolated);
            verifyCdcValueConverters(isolated);
          } else if ("Jdbc".equals(plugin)) {
            verifyHospitalDialect(isolated);
          }
        }
      }
      Thread.currentThread().setContextClassLoader(engine);
      requireClass(engine, "oracle.jdbc.OracleDriver", "ojdbc8-19.32.0.0.jar");
      requireClass(
          engine, "oracle.i18n.text.converter.CharacterConverterGBK", "orai18n-19.32.0.0.jar");
      verifyChineseCharacterSet(engine);
      System.out.println(
          "PASS factory/synthetic-value/driver/NLS smoke only; no Oracle CDC or database acceptance; Java "
              + Runtime.version());
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static List<URL> connectorJars(Path runtime, String plugin) throws Exception {
    String artifact =
        switch (plugin) {
          case "Oracle-CDC" -> "connector-cdc-oracle";
          case "Jdbc" -> "connector-jdbc";
          case "Postgres-CDC" -> "connector-cdc-postgres";
          default -> throw new IllegalArgumentException("Unexpected smoke plugin");
        };
    List<URL> jars = new ArrayList<>();
    jars.add(runtime.resolve("connectors/" + artifact + "-3.0.0.jar").toUri().toURL());
    if (plugin.endsWith("-CDC")) {
      jars.add(runtime.resolve("connectors/connector-cdc-base-3.0.0.jar").toUri().toURL());
    }
    Path plugins = runtime.resolve("plugins");
    if (Files.isDirectory(plugins)) {
      try (Stream<Path> entries = Files.list(plugins)) {
        for (Path entry : entries.sorted().toList()) {
          if (Files.isDirectory(entry)) {
            if (!entry.getFileName().toString().startsWith("connector-")
                || entry.getFileName().toString().equalsIgnoreCase(artifact)) {
              addJars(jars, entry);
            }
          } else if (entry.toString().endsWith(".jar")) {
            jars.add(entry.toUri().toURL());
          }
        }
      }
    }
    return jars;
  }

  private static void addJars(List<URL> jars, Path directory) throws Exception {
    try (Stream<Path> entries = Files.walk(directory)) {
      for (Path jar : entries.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
        jars.add(jar.toUri().toURL());
      }
    }
  }

  private static void verifyOracleDate(ClassLoader loader) throws Exception {
    Class<?> columnType = Class.forName("io.debezium.relational.Column", true, loader);
    Class<?> editorType = Class.forName("io.debezium.relational.ColumnEditor", true, loader);
    Object editor = columnType.getMethod("editor").invoke(null);
    editorType.getMethod("name", String.class).invoke(editor, "ORDER_TIME");
    editorType.getMethod("type", String.class).invoke(editor, "DATE");
    editorType.getMethod("jdbcType", int.class).invoke(editor, Types.DATE);
    editorType.getMethod("length", int.class).invoke(editor, 7);
    Object column = editorType.getMethod("create").invoke(editor);
    Class<?> converter = Class.forName(ORACLE + "utils.OracleTypeUtils", true, loader);
    Object converted = converter.getMethod("convertFromColumn", columnType).invoke(null, column);
    Object timestamp =
        Class.forName("org.apache.seatunnel.api.table.type.LocalTimeType", true, loader)
            .getField("LOCAL_DATE_TIME_TYPE")
            .get(null);
    if (!timestamp.equals(converted)) {
      throw new IllegalStateException("Oracle DATE did not preserve its timestamp type");
    }
    requireClass(
        loader,
        "io.debezium.connector.oracle.OracleValueConverters",
        "connector-cdc-oracle-3.0.0.jar");
    System.out.println("PASS Oracle DATE -> " + converted + " through OracleTypeUtils");
  }

  private static void verifyHospitalDialect(ClassLoader loader) throws Exception {
    Class<?> dialect =
        Class.forName(
            "org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.JdbcDialectFactory",
            true,
            loader);
    Method name = dialect.getMethod("dialectFactoryName");
    for (Object factory : ServiceLoader.load(dialect, loader)) {
      if ("HospitalOracle".equals(name.invoke(factory))) {
        requireClass(loader, factory.getClass().getName(), "connector-jdbc-3.0.0.jar");
        System.out.println("PASS preserved HospitalOracle dialect service");
        return;
      }
    }
    throw new IllegalStateException("HospitalOracle dialect service is missing");
  }

  private static void verifyCdcValueConverters(ClassLoader loader) throws Exception {
    Class<?> converters =
        Class.forName(
            "org.apache.seatunnel.connectors.cdc.debezium.row.SeaTunnelRowDebeziumDeserializationConverters",
            true,
            loader);
    Class<?> converterType =
        Class.forName(
            "org.apache.seatunnel.connectors.cdc.debezium.DebeziumDeserializationConverter",
            true,
            loader);
    Class<?> schemaType = Class.forName("org.apache.kafka.connect.data.Schema", true, loader);
    Method convert = converterType.getMethod("convert", Object.class, schemaType);
    Method timestampFactory = converters.getDeclaredMethod("convertToTimestamp", ZoneId.class);
    timestampFactory.setAccessible(true);
    Object timestampConverter = timestampFactory.invoke(null, ZoneId.of("Asia/Shanghai"));
    LocalDateTime millis = LocalDateTime.of(2026, 11, 13, 12, 34, 56, 123000000);
    Object millisSchema =
        Class.forName("io.debezium.time.Timestamp", true, loader).getMethod("schema").invoke(null);
    Object convertedMillis =
        convert.invoke(
            timestampConverter, millis.toInstant(ZoneOffset.UTC).toEpochMilli(), millisSchema);
    LocalDateTime micros = millis.withNano(123456000);
    long encodedMicros =
        Math.addExact(
            Math.multiplyExact(micros.toEpochSecond(ZoneOffset.UTC), 1000000L),
            micros.getNano() / 1000);
    Object microsSchema =
        Class.forName("io.debezium.time.MicroTimestamp", true, loader)
            .getMethod("schema")
            .invoke(null);
    Object convertedMicros = convert.invoke(timestampConverter, encodedMicros, microsSchema);
    if (!millis.equals(convertedMillis) || !micros.equals(convertedMicros)) {
      throw new IllegalStateException(
          "CDC logical timestamp shifted local date/time or lost precision");
    }
    long integral = 123456789012345678L;
    Object int64Schema = schemaType.getField("INT64_SCHEMA").get(null);
    Method longFactory = converters.getDeclaredMethod("convertToLong");
    longFactory.setAccessible(true);
    Object convertedLong = convert.invoke(longFactory.invoke(null), integral, int64Schema);
    Method decimalFactory = converters.getDeclaredMethod("createDecimalConverter");
    decimalFactory.setAccessible(true);
    Object convertedDecimal = convert.invoke(decimalFactory.invoke(null), integral, int64Schema);
    if (!Long.valueOf(integral).equals(convertedLong)
        || !new BigDecimal("123456789012345678").equals(convertedDecimal)) {
      throw new IllegalStateException("CDC integral/DECIMAL conversion lost 18-digit precision");
    }
    System.out.println(
        "PASS released CDC Timestamp="
            + convertedMillis
            + " MicroTimestamp="
            + convertedMicros
            + " with Asia/Shanghai and no local-time shift");
    System.out.println("PASS released CDC integral/DECIMAL exact value=" + convertedDecimal);
  }

  private static void verifyChineseCharacterSet(ClassLoader loader) throws Exception {
    Class<?> charsetType = Class.forName("oracle.sql.CharacterSet", true, loader);
    Object charset = charsetType.getMethod("make", int.class).invoke(null, 852);
    String expected = "医院中文";
    byte[] encoded =
        (byte[]) charsetType.getMethod("convert", String.class).invoke(charset, expected);
    Object decoded =
        charsetType
            .getMethod("toString", byte[].class, int.class, int.class)
            .invoke(charset, encoded, 0, encoded.length);
    if (!expected.equals(decoded)) {
      throw new IllegalStateException("Oracle ZHS16GBK round trip failed");
    }
    System.out.println("PASS Oracle ZHS16GBK Chinese round trip without a database");
  }

  private static void requireClass(ClassLoader loader, String name, String artifact)
      throws Exception {
    Class<?> type = Class.forName(name, true, loader);
    String actual =
        Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getFileName()
            .toString();
    if (!artifact.equals(actual)) {
      throw new IllegalStateException("Unexpected artifact for " + name + ": " + actual);
    }
    System.out.println("PASS class " + name + " from " + actual);
  }

  private static void requireArtifact(Path jar, long bytes, String expectedHash) throws Exception {
    if (bytes >= 0 && Files.size(jar) != bytes) {
      throw new IllegalStateException("Unexpected artifact size: " + jar.getFileName());
    }
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(jar)) {
      byte[] chunk = new byte[65536];
      int count;
      while ((count = input.read(chunk)) != -1) {
        digest.update(chunk, 0, count);
      }
    }
    if (!expectedHash.equals(HexFormat.of().formatHex(digest.digest()))) {
      throw new IllegalStateException("Unexpected artifact checksum: " + jar.getFileName());
    }
    System.out.println("PASS SHA-256 " + jar.getFileName());
  }
}
