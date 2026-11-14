package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SqlServerCdcMetadataTest {
  @Test
  void expiredSharedDeadlineIssuesNoAdditionalQueries() throws Exception {
    var fixture = new SqlServerCdcPreflightTest.Fixture();
    var nanoTime = new AtomicLong(0);
    var metadata = new SqlServerCdcMetadata(fixture.connection(), 20_000_000_000L, nanoTime::get);
    metadata.server();
    nanoTime.set(20_000_000_000L);
    assertThrows(SQLException.class, metadata::database);
    assertEquals(1, fixture.queries.size());
  }

  @Test
  void deadlineDuringResultIterationDiscardsIncompleteEvidence() {
    var fixture = new SqlServerCdcPreflightTest.Fixture();
    var nanoTime = new AtomicLong(0);
    var metadata =
        new SqlServerCdcMetadata(
            fixture.connection(), 20_000_000_000L, () -> nanoTime.getAndAdd(8_000_000_000L));
    assertThrows(SQLException.class, metadata::server);
    assertThrows(SQLException.class, metadata::database);
    assertEquals(1, fixture.queries.size());
  }

  @Test
  void sentinelCapRejectsTruncationRatherThanCertifyingPartialColumns() {
    var fixture = new SqlServerCdcPreflightTest.Fixture();
    fixture.columns = Collections.nCopies(1025, fixture.columns.getFirst());
    var metadata =
        new SqlServerCdcMetadata(fixture.connection(), System.nanoTime() + 20_000_000_000L);
    assertThrows(SQLException.class, () -> metadata.columns(100));
  }
}
