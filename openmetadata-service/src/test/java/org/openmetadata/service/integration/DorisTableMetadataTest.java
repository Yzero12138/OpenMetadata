package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DorisTableMetadataTest {
  @Test
  void identifiesLogicalUniqueKeysWithoutTreatingDuplicateOrAggregateKeysAsPrimary() {
    assertEquals(
        Set.of("id"),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nUNIQUE KEY(`id`)\nDISTRIBUTED BY HASH(`id`)"));
    assertEquals(
        Set.of(),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nDUPLICATE KEY(`id`)"));
    assertEquals(
        Set.of(),
        DorisTableMetadata.uniqueKeys(
            "CREATE TABLE `t` (\n`id` bigint\n) ENGINE=OLAP\nAGGREGATE KEY(`id`)"));
  }

  @Test
  void keepsDorisByteLengthsSeparateFromUnicodeCharacterCapacity() {
    var column = DorisTableMetadata.column("患者姓名", "varchar(400)", false, false, false);
    assertEquals(Types.VARCHAR, column.jdbcType());
    assertEquals(400, column.size());
    assertTrue(column.unicodeSafeTarget());
    assertEquals(100, column.unicodeCapacity());
    var key = DorisTableMetadata.column("id", "bigint", false, true, false);
    assertTrue(key.primaryKey());
    assertFalse(key.nullable());
    assertEquals(Types.BIGINT, key.jdbcType());
    var timestamp = DorisTableMetadata.column("updated_at", "datetimev2(6)", true, false, false);
    assertEquals(Types.TIMESTAMP, timestamp.jdbcType());
    assertEquals(6, timestamp.scale());
    var decimal = DorisTableMetadata.column("amount", "decimal(20, 4)", true, false, false);
    assertEquals(20, decimal.size());
    assertEquals(4, decimal.scale());
  }
}
