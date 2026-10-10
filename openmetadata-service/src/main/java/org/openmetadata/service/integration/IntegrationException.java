package org.openmetadata.service.integration;

import java.util.Map;

public final class IntegrationException extends RuntimeException {
  private static final Map<String, String> MESSAGES =
      Map.ofEntries(
          Map.entry("INTEGRATION_DISABLED", "Hospital integration is disabled."),
          Map.entry("ENGINE_UNAVAILABLE", "The integration engine is unavailable."),
          Map.entry("ENGINE_JOB_FAILED", "The engine reported that the integration job failed."),
          Map.entry(
              "SUBMISSION_UNKNOWN",
              "Submission has not been confirmed. Reconcile this job before retrying."),
          Map.entry("JOB_NOT_FOUND", "The engine has no record of this job."),
          Map.entry("INVALID_CONFIGURATION", "The integration configuration is invalid."),
          Map.entry("VERSION_CONFLICT", "The task changed. Reload it before editing."),
          Map.entry("TASK_ACTIVE", "The task has an active, uncertain, or resumable run."),
          Map.entry("SAVEPOINT_UNAVAILABLE", "A completed savepoint has not been confirmed."),
          Map.entry(
              "CATALOG_SYNC_FAILED",
              "Catalog synchronization failed. It can be retried separately."),
          Map.entry(
              "CONNECTION_UNAVAILABLE",
              "The database connection is unavailable. Check connectivity and credentials."),
          Map.entry("CONNECTION_NOT_FOUND", "The data source does not exist."),
          Map.entry("CONNECTION_DISABLED", "The data source is disabled."),
          Map.entry("CONNECTION_MANAGED", "This data source is managed by server configuration."),
          Map.entry("CONNECTION_IN_USE", "This data source is referenced by an integration task."),
          Map.entry(
              "CONNECTION_ROLE_MISMATCH",
              "Select a data source with the required source or target role."),
          Map.entry(
              "DUPLICATE_CONNECTION", "A data source with this technical name already exists."),
          Map.entry(
              "CREDENTIALS_NOT_CONFIGURED",
              "Credential encryption is unavailable. Contact the administrator."),
          Map.entry(
              "MODE_UNSUPPORTED",
              "This data source does not support the selected collection mode."),
          Map.entry("SCHEMA_NOT_ALLOWED", "Select a schema in the configured collection scope."),
          Map.entry("TABLE_NOT_FOUND", "The selected table does not exist or is not accessible."),
          Map.entry(
              "TABLE_LIMIT_EXCEEDED",
              "There are too many tables. Narrow the configured schema scope."),
          Map.entry("TASK_NOT_FOUND", "The integration task does not exist."),
          Map.entry("DUPLICATE_TASK", "A task with this name already exists."),
          Map.entry(
              "DUPLICATE_ROUTE", "Another task already owns this source-to-target catalog route."),
          Map.entry("CAPACITY_EXCEEDED", "The integration task limit has been reached."),
          Map.entry("STORAGE_UNAVAILABLE", "Integration task storage is unavailable."));

  private final String code;
  private final int status;

  public IntegrationException(String code, int status) {
    super(MESSAGES.getOrDefault(code, "The integration operation could not be completed."));
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }
}
