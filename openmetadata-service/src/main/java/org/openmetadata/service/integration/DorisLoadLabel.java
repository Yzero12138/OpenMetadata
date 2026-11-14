package org.openmetadata.service.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.openmetadata.service.integration.IntegrationModels.IntegrationTask;

final class DorisLoadLabel {
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  // SeaTunnel 3.0.0 appends _database_table_jobId_subtask_millis to this 17-character prefix.
  // Reserve 19 characters for each long and one for subtask 0 (parallelism is fixed at one).
  private static final int MAX_TABLE_PATH_LENGTH = 128 - 17 - 19 - 1 - 19 - 5;

  private DorisLoadLabel() {}

  static void requireTablePath(String database, String table) {
    if (database == null
        || table == null
        || !NAME.matcher(database).matches()
        || !NAME.matcher(table).matches()
        || database.length() + table.length() > MAX_TABLE_PATH_LENGTH) {
      throw new IntegrationException("DORIS_LABEL_INVALID", 400);
    }
  }

  static String prefix(IntegrationTask task) {
    requireTablePath(task.targetSchema, task.targetTable);
    String runId = task.latestRun == null ? "preview" : task.latestRun.jobId;
    if (task.id == null || runId == null) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    if (task.latestRun != null) {
      try {
        if (!runId.matches("[1-9][0-9]{0,18}") || Long.parseLong(runId) <= 0) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException e) {
        throw new IntegrationException("INVALID_CONFIGURATION", 400);
      }
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((task.id + ":" + runId).getBytes(StandardCharsets.UTF_8));
      return "h" + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
