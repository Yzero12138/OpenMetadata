/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
import {
  IntegrationCdcCheck,
  IntegrationCdcPreflightResult,
  IntegrationConnection,
  IntegrationMode,
  IntegrationRuntimeOptions,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';

export const RUNTIME_FIELDS = {
  fetchSize: [100, 10000],
  splitSize: [1000, 100000],
  sinkBatchSize: [100, 50000],
  checkpointIntervalSeconds: [10, 300],
  checkpointTimeoutSeconds: [60, 3600],
} as const;

export const runtimeDefaults = (
  mode: IntegrationMode
): IntegrationRuntimeOptions => ({
  fetchSize: 1000,
  ...(mode !== 'SQL_T1' ? { splitSize: 8192 } : {}),
  sinkBatchSize: mode === 'FULL' ? 10000 : mode === 'CDC' ? 1000 : 100,
  checkpointIntervalSeconds: mode === 'CDC' ? 30 : 60,
  checkpointTimeoutSeconds: mode === 'CDC' ? 300 : 900,
});

export const parseRuntimeInteger = (
  text: string
): { valid: boolean; value?: number } => {
  if (text === '') {
    return { valid: true, value: undefined };
  }
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(Number(text))) {
    return { valid: false };
  }

  return { valid: true, value: Number(text) };
};

export const validateIntegrationRuntime = (
  input: IntegrationTaskInput
): string[] => {
  const errors: string[] = [];
  if (
    Object.entries(input.runtime ?? {}).some(([key, value]) => {
      const bounds = RUNTIME_FIELDS[key as keyof typeof RUNTIME_FIELDS];

      return (
        value !== undefined &&
        value !== null &&
        (!bounds ||
          !Number.isSafeInteger(value) ||
          value < bounds[0] ||
          value > bounds[1] ||
          (input.mode === 'SQL_T1' && key === 'splitSize'))
      );
    })
  ) {
    errors.push('runtimeRangeInvalid');
  }
  const defaults = runtimeDefaults(input.mode);
  if (
    (input.runtime?.checkpointTimeoutSeconds ??
      defaults.checkpointTimeoutSeconds ??
      0) <
    2 *
      (input.runtime?.checkpointIntervalSeconds ??
        defaults.checkpointIntervalSeconds ??
        0)
  ) {
    errors.push('runtimeTimeoutInvalid');
  }

  return errors;
};

export const changeIntegrationMode = (
  input: IntegrationTaskInput,
  mode: IntegrationMode
): IntegrationTaskInput => {
  const { cdc, sourceQuery, schedule, initialization, runtime, ...common } =
    input;
  const { splitSize: _splitSize, ...sqlRuntime } = runtime ?? {};

  return {
    ...common,
    mode,
    ...(mode === 'CDC' && input.mode === 'CDC' && cdc
      ? { cdc: { ...cdc } }
      : {}),
    ...(mode === 'SQL_T1' ? { sourceQuery, schedule, initialization } : {}),
    ...(runtime
      ? { runtime: mode === 'SQL_T1' ? sqlRuntime : { ...runtime } }
      : {}),
  };
};

export const cdcPreflightReady = (result?: IntegrationCdcPreflightResult) =>
  result?.implemented === true &&
  result.ready === true &&
  result.checks.every((check) => !check.required || check.status === 'PASS');

export const prioritizeCdcChecks = (checks: IntegrationCdcCheck[]) => {
  const priority = (check: IntegrationCdcCheck) =>
    check.required && check.status !== 'PASS'
      ? 0
      : !check.required &&
        (check.status === 'FAIL' || check.status === 'UNKNOWN')
      ? 1
      : 2;

  return checks
    .map((check, index) => ({ check, index }))
    .sort((a, b) => priority(a.check) - priority(b.check) || a.index - b.index)
    .map(({ check }) => check);
};

export const integrationTaskPreflightKey = (
  input: IntegrationTaskInput,
  connections: Pick<IntegrationConnection, 'id' | 'version'>[],
  scope: string,
  generation: number
) =>
  JSON.stringify([
    scope,
    generation,
    connections
      .filter(
        (connection) =>
          connection.id === input.sourceConnectionId ||
          connection.id === input.targetConnectionId
      )
      .map(({ id, version }) => [id, version])
      .sort(),
    input.name,
    input.displayName,
    input.sourceConnectionId,
    input.sourceSchema,
    input.sourceTable,
    input.targetConnectionId,
    input.targetSchema,
    input.targetTable,
    input.mode,
    input.primaryKey,
    input.fieldMappings.map(({ source, target }) => [source, target]),
    input.sourceQuery,
    input.schedule,
    input.initialization,
    input.cdc
      ? [
          input.cdc.databaseName,
          input.cdc.serverTimeZone,
          input.cdc.pdbName,
          input.cdc.rootServiceName,
        ]
      : null,
    input.runtime
      ? [
          input.runtime.fetchSize,
          input.runtime.splitSize,
          input.runtime.sinkBatchSize,
          input.runtime.checkpointIntervalSeconds,
          input.runtime.checkpointTimeoutSeconds,
        ]
      : null,
  ]);

const PREFLIGHT_MESSAGES: Record<string, string> = {
  ENGINE_SOURCE_NETWORK_UNVERIFIED: 'network',
  CDC_NOT_IMPLEMENTED: 'notImplemented',
  CDC_ENGINE_PLUGIN_AVAILABLE: 'plugin',
  CDC_ENGINE_PLUGIN_MISSING: 'plugin',
  CDC_ENGINE_PLUGIN_UNVERIFIED: 'plugin',
  ORACLE_CDC_RUNTIME_UNVERIFIED: 'runtimeWarning',
  ORACLE_CDC_VERSION_UNCERTIFIED: 'versionWarning',
  ORACLE_DATABASE_VERSION_UNVERIFIED: 'versionWarning',
  ORACLE_DB_NAME_PROVEN: 'databaseNameHint',
  ORACLE_DB_NAME_MISMATCH: 'databaseNameHint',
  ORACLE_DB_NAME_UNVERIFIED: 'databaseNameHint',
  ORACLE_TIME_ZONE_CONFIGURED: 'timeZoneHint',
  ORACLE_TIME_ZONE_REQUIRED: 'timeZoneHint',
  ORACLE_ARCHIVELOG_ENABLED: 'archive',
  ORACLE_ARCHIVELOG_REQUIRED: 'archive',
  ORACLE_ARCHIVELOG_UNVERIFIED: 'archive',
  ORACLE_MIN_SUPPLEMENTAL_ENABLED: 'minimumLog',
  ORACLE_MIN_SUPPLEMENTAL_REQUIRED: 'minimumLog',
  ORACLE_MIN_SUPPLEMENTAL_UNVERIFIED: 'minimumLog',
  TABLE_SUPPLEMENTAL_LOGGING_PROVEN: 'tableLog',
  TABLE_SUPPLEMENTAL_LOGGING_INCOMPLETE: 'tableLog',
  TABLE_SUPPLEMENTAL_LOGGING_UNVERIFIED: 'tableLog',
  EXECUTE_GRANT_PROVEN: 'packageExecute',
  EXECUTE_GRANT_UNVERIFIED: 'packageExecute',
  ORACLE_MINING_VIEW_ACCESS_PROVEN: 'miningViews',
  ORACLE_MINING_VIEW_ACCESS_UNVERIFIED: 'miningViews',
  ORACLE_MINING_PRIVILEGES_PROVEN: 'miningPrivileges',
  ORACLE_MINING_PRIVILEGES_UNVERIFIED: 'miningPrivileges',
  ORACLE_NON_CDB_PROVEN: 'container',
  ORACLE_PDB_SOURCE_PROVEN: 'container',
  ORACLE_PDB_SOURCE_MISMATCH: 'container',
  ORACLE_CDC_ROOT_ROUTE_UNVERIFIED: 'container',
  ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED: 'container',
  ORACLE_CDC_CONTAINER_UNVERIFIED: 'container',
  CDC_TABLE_REQUIRED: 'table',
  CDC_TABLE_NOT_FOUND: 'table',
  CDC_TABLE_METADATA_PROVEN: 'table',
  CDC_TABLE_METADATA_UNVERIFIED: 'table',
  CDC_PRIMARY_KEY_PROVEN: 'primaryKey',
  INVALID_PRIMARY_KEY: 'primaryKey',
  CDC_COLUMNS_PROVEN: 'columns',
  CDC_COLUMNS_INVALID: 'columns',
  ORACLE_CDC_COLUMN_TYPES_PROVEN: 'types',
  ORACLE_CDC_TYPE_UNSUPPORTED: 'types',
  ORACLE_CDC_LOB_UNVERIFIED: 'lob',
  ORACLE_CDC_STRING_BYTES_UNVERIFIED: 'stringBytes',
  ORACLE_CDC_EXTENDED_STRING_UNVERIFIED: 'extendedString',
  ORACLE_CDC_IDENTIFIERS_PROVEN: 'identifiers',
  ORACLE_CDC_IDENTIFIER_UNSUPPORTED: 'identifiers',
  CDC_TARGET_METADATA_UNVERIFIED: 'targetMapping',
  CDC_TARGET_MAPPING_PROVEN: 'targetMapping',
  CDC_TARGET_MAPPING_INCOMPATIBLE: 'targetMapping',
  ORACLE_CDC_TARGET_UNSUPPORTED: 'targetMapping',
  ORACLE_CDC_TARGET_PROJECTION_INCOMPLETE: 'targetProjection',
  CDC_SOURCE_METADATA_UNVERIFIED: 'table',
  CDC_DATABASE_NAME_UNSUPPORTED: 'optionsInvalid',
};

export const cdcCheckMessageKey = (code: string) =>
  `hospitalCdc.${PREFLIGHT_MESSAGES[code] ?? 'unknownCheck'}`;

const CDC_ERROR_MESSAGES: Record<string, string> = {
  CDC_PREFLIGHT_FAILED: 'requiredBlocked',
  CDC_CONFIGURATION_CHANGED: 'configurationChanged',
  CDC_LEGACY_RESUME_UNSAFE: 'legacyResume',
  TARGET_WRITER_ACTIVE: 'writerActive',
  TARGET_COUNT_UNAVAILABLE: 'countUnavailable',
  CDC_TRUSTED_INSPECTION_REQUIRED: 'requiredBlocked',
  ORACLE_CDC_OPTIONS_INVALID: 'optionsInvalid',
};

export const cdcErrorKey = (code?: string) =>
  code && (CDC_ERROR_MESSAGES[code] || PREFLIGHT_MESSAGES[code])
    ? `hospitalCdc.${CDC_ERROR_MESSAGES[code] ?? PREFLIGHT_MESSAGES[code]}`
    : undefined;
