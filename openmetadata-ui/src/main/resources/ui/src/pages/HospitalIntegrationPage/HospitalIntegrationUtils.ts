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
  IntegrationConnection,
  IntegrationConnectionInput,
  IntegrationDatabaseType,
  IntegrationInitializationConfig,
  IntegrationInitializationState,
  IntegrationMode,
  IntegrationRun,
  IntegrationTable,
  IntegrationTask,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';
import { cdcErrorKey, validateIntegrationRuntime } from './IntegrationCdcUtils';

export const SQL_QUERY_MAX_BYTES = 16384;
export const DEFAULT_INTEGRATION_SCHEDULE = {
  enabled: false,
  dailyTime: '02:00',
  timeZone: 'Asia/Shanghai',
};

export const integrationModeKey = (mode: IntegrationMode) =>
  `hospitalIntegration.${
    mode === 'SQL_T1' ? 'sqlT1' : mode === 'CDC' ? 'cdc' : 'full'
  }`;

export const integrationToday = (now = new Date()) =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: DEFAULT_INTEGRATION_SCHEDULE.timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);

const integrationDate = (value: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return undefined;
  }
  const date = new Date(value + 'T00:00:00Z');

  return Number.isFinite(date.getTime()) &&
    date.toISOString().slice(0, 10) === value
    ? date
    : undefined;
};

const dateString = (date: Date) => date.toISOString().slice(0, 10);

export const getIntegrationInitializationPreview = (
  initialization: IntegrationInitializationConfig,
  cutoffDate: string
) => {
  const start = integrationDate(initialization.startDate);
  const cutoff = integrationDate(cutoffDate);
  if (!start || !cutoff || start >= cutoff) {
    return undefined;
  }
  const monthly = initialization.batchUnit === 'MONTH';
  const end = new Date(start);
  const last = new Date(cutoff);
  last.setUTCDate(last.getUTCDate() - 1);
  let totalBatches: number;
  if (monthly) {
    end.setUTCDate(1);
    end.setUTCMonth(end.getUTCMonth() + 1);
    last.setUTCDate(1);
    totalBatches =
      (last.getUTCFullYear() - start.getUTCFullYear()) * 12 +
      last.getUTCMonth() -
      start.getUTCMonth() +
      1;
  } else {
    end.setUTCDate(end.getUTCDate() + 1);
    totalBatches = (cutoff.getTime() - start.getTime()) / 86400000;
  }

  return {
    totalBatches,
    firstStartDate: initialization.startDate,
    firstEndDate: dateString(end < cutoff ? end : cutoff),
    lastStartDate: dateString(last > start ? last : start),
    lastEndDate: cutoffDate,
  };
};

export const isIntegrationInitializationLocked = (
  state?: IntegrationInitializationState
) =>
  Boolean(
    state &&
      (state.status === 'COMPLETED' ||
        state.startedAt !== undefined ||
        state.pendingStartDate ||
        state.pendingJobId ||
        state.completedBatches > 0)
  );

export const isIntegrationInitializing = (task: IntegrationTask) =>
  task.mode === 'SQL_T1' &&
  task.initialization?.enabled === true &&
  task.initializationState?.status !== 'COMPLETED';

export const EMPTY_INTEGRATION_TASK: IntegrationTaskInput = {
  name: '',
  displayName: '',
  sourceConnectionId: '',
  targetConnectionId: '',
  sourceSchema: '',
  sourceTable: '',
  targetSchema: '',
  targetTable: '',
  mode: 'FULL',
  primaryKey: '',
  fieldMappings: [],
};

const TERMINAL_STATUSES = new Set([
  'FINISHED',
  'FAILED',
  'STOPPED',
  'CANCELED',
  'CANCELLED',
  'SAVEPOINT_DONE',
]);

const ACTIVE_STATUSES = new Set([
  'CREATED',
  'PENDING',
  'INITIALIZING',
  'RUNNING',
  'FAILING',
  'CANCELLING',
  'CANCELING',
  'RESTARTING',
  'SCHEDULED',
  'STOP_FAILED',
  'STOPPING',
]);

export const isIntegrationRunBusy = (run?: IntegrationRun) =>
  Boolean(run && !TERMINAL_STATUSES.has(run.status.toUpperCase()));

export const isIntegrationRunUncertain = (run?: IntegrationRun) =>
  Boolean(
    run &&
      !TERMINAL_STATUSES.has(run.status.toUpperCase()) &&
      !ACTIVE_STATUSES.has(run.status.toUpperCase())
  );

export const toIntegrationTaskInput = (
  task: IntegrationTask
): IntegrationTaskInput => ({
  name: task.name,
  displayName: task.displayName,
  sourceConnectionId: task.sourceConnectionId,
  targetConnectionId: task.targetConnectionId,
  sourceSchema: task.sourceSchema,
  sourceTable: task.sourceTable,
  targetSchema: task.targetSchema,
  targetTable: task.targetTable,
  mode: task.mode,
  primaryKey: task.primaryKey,
  fieldMappings: task.fieldMappings.map((mapping) => ({ ...mapping })),
  ...(task.sourceQuery !== undefined ? { sourceQuery: task.sourceQuery } : {}),
  ...(task.schedule ? { schedule: { ...task.schedule } } : {}),
  ...(task.initialization
    ? { initialization: { ...task.initialization } }
    : {}),
  ...(task.cdc ? { cdc: { ...task.cdc } } : {}),
  ...(task.runtime ? { runtime: { ...task.runtime } } : {}),
});

export const canResumeIntegrationTask = (
  mode: IntegrationTaskInput['mode'],
  run?: IntegrationRun
) => mode === 'CDC' && run?.canResume === true && !isIntegrationRunBusy(run);

export const integrationTableKey = (table: IntegrationTable) =>
  `${table.schema}.${table.name}`;

export const findIntegrationTable = (
  tables: IntegrationTable[],
  schema: string,
  name: string
) => tables.find((table) => table.schema === schema && table.name === name);

export const validateIntegrationInput = (
  input: IntegrationTaskInput,
  sourceTables: IntegrationTable[],
  targetTables: IntegrationTable[]
): string[] => {
  const errors: string[] = validateIntegrationRuntime(input);
  if (!/^[a-z][a-z0-9_-]{0,62}$/.test(input.name)) {
    errors.push('invalidName');
  }
  if (!input.displayName.trim()) {
    errors.push('displayNameRequired');
  }
  const source = findIntegrationTable(
    sourceTables,
    input.sourceSchema,
    input.sourceTable
  );
  const target = findIntegrationTable(
    targetTables,
    input.targetSchema,
    input.targetTable
  );
  if (!source || !target) {
    errors.push('tablesRequired');
  }
  const mappedTargets = input.fieldMappings.map((mapping) => mapping.target);
  if (
    input.fieldMappings.length === 0 ||
    new Set(mappedTargets).size !== mappedTargets.length ||
    input.fieldMappings.some(
      (mapping) =>
        !source?.columns.some((column) => column.name === mapping.source) ||
        !target?.columns.some((column) => column.name === mapping.target)
    )
  ) {
    errors.push('mappingRequired');
  }
  if (
    input.mode === 'CDC' &&
    (!source?.columns.some(
      (column) => column.name === input.primaryKey && column.primaryKey
    ) ||
      !input.fieldMappings.some(
        (mapping) => mapping.source === input.primaryKey
      ))
  ) {
    errors.push('primaryKeyRequired');
  }
  if (input.mode === 'SQL_T1') {
    if (!input.sourceQuery?.trim()) {
      errors.push('sourceQueryRequired');
    } else if (new Blob([input.sourceQuery]).size > SQL_QUERY_MAX_BYTES) {
      errors.push('queryTooLong');
    }
    if (!source?.columns.length || input.sourceTable !== 'sql_result') {
      errors.push('queryColumnsRequired');
    }
    if (
      !source?.columns.some((column) => column.name === input.primaryKey) ||
      !input.fieldMappings.some(
        (mapping) => mapping.source === input.primaryKey
      )
    ) {
      errors.push('sqlPrimaryKeyRequired');
    }
    if (
      !input.schedule ||
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(input.schedule.dailyTime) ||
      input.schedule.timeZone !== DEFAULT_INTEGRATION_SCHEDULE.timeZone
    ) {
      errors.push('scheduleInvalid');
    }
    if (input.initialization?.enabled) {
      const { startDate } = input.initialization;
      if (
        !integrationDate(startDate) ||
        startDate < '2000-01-01' ||
        startDate >= integrationToday()
      ) {
        errors.push('initializationDateInvalid');
      }
    }
  }

  return errors;
};

export const integrationErrorKey = (code?: string) => {
  const cdcKey = cdcErrorKey(code);
  if (cdcKey) {
    return cdcKey;
  }
  const knownCodes = new Set([
    'INTEGRATION_DISABLED',
    'ENGINE_UNAVAILABLE',
    'SUBMISSION_UNKNOWN',
    'JOB_NOT_FOUND',
    'INVALID_CONFIGURATION',
    'DUPLICATE_ROUTE',
    'DUPLICATE_TASK',
    'VERSION_CONFLICT',
    'TASK_ACTIVE',
    'TASK_NOT_FOUND',
    'SAVEPOINT_UNAVAILABLE',
    'CATALOG_SYNC_FAILED',
    'CONNECTION_NOT_FOUND',
    'CONNECTION_DISABLED',
    'CONNECTION_MANAGED',
    'CONNECTION_IN_USE',
    'CONNECTION_ROLE_MISMATCH',
    'DUPLICATE_CONNECTION',
    'DORIS_LABEL_INVALID',
    'CREDENTIALS_NOT_CONFIGURED',
    'CONNECTION_UNAVAILABLE',
    'MODE_UNSUPPORTED',
    'SCHEMA_NOT_ALLOWED',
    'TABLE_NOT_FOUND',
    'TABLE_LIMIT_EXCEEDED',
    'TABLE_ALREADY_EXISTS',
    'TABLE_PREVIEW_STALE',
    'UNSUPPORTED_TARGET_TYPE',
    'TARGET_TABLE_CREATE_FAILED',
    'INVALID_SOURCE_QUERY',
    'SQL_DATE_PARAMETER_REQUIRED',
    'SQL_PARAMETER_UNSUPPORTED',
    'QUERY_METADATA_UNAVAILABLE',
    'INVALID_QUERY_COLUMNS',
    'INVALID_SCHEDULE',
    'BUSINESS_DATE_REQUIRED',
    'BUSINESS_DATE_INVALID',
    'SQL_INITIALIZATION_PARAMETERS_REQUIRED',
    'INITIALIZATION_STATE_INVALID',
    'INITIALIZATION_IN_PROGRESS',
    'INITIALIZATION_CONFIGURATION_LOCKED',
    'INITIALIZATION_UNAVAILABLE',
  ]);

  return `hospitalIntegration.errors.${
    code && knownCodes.has(code) ? code : 'REQUEST_FAILED'
  }`;
};

export const INTEGRATION_DATABASE_DEFAULTS: Record<
  IntegrationDatabaseType,
  { port: number; schemas: string[]; dorisHttpPort?: number }
> = {
  Postgres: { port: 5432, schemas: ['public'] },
  Oracle: { port: 1521, schemas: [] },
  Mssql: { port: 1433, schemas: ['dbo'] },
  Mysql: { port: 3306, schemas: [] },
  Doris: { port: 9030, dorisHttpPort: 8030, schemas: [] },
};

export const changeIntegrationDatabaseType = (
  value: IntegrationConnectionInput,
  databaseType: IntegrationDatabaseType
): IntegrationConnectionInput => {
  const {
    oracleConnectionType: _oracle,
    dorisHttpPort: _doris,
    ...current
  } = value;
  const defaults = INTEGRATION_DATABASE_DEFAULTS[databaseType];

  return {
    ...current,
    databaseType,
    databaseVersion: '',
    port: defaults.port,
    schemas:
      databaseType === 'Mysql' || databaseType === 'Doris'
        ? value.database
          ? [value.database]
          : []
        : [...defaults.schemas],
    ...(databaseType === 'Oracle'
      ? { oracleConnectionType: 'SERVICE_NAME' }
      : {}),
    ...(databaseType === 'Doris'
      ? { dorisHttpPort: defaults.dorisHttpPort, tlsMode: 'DISABLED' }
      : {}),
  };
};

export const suggestIntegrationIdentifier = (name: string): string => {
  const normalized = name
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '');

  return (
    (/^[a-z]/.test(normalized) ? normalized : 'target_' + normalized).slice(
      0,
      64
    ) || 'target_table'
  );
};

export const emptyIntegrationConnection = (): IntegrationConnectionInput => ({
  name: '',
  displayName: '',
  role: 'SOURCE',
  databaseType: 'Postgres',
  databaseVersion: '',
  host: '',
  port: 5432,
  database: '',
  username: '',
  password: '',
  schemas: ['public'],
  tlsMode: 'VERIFY',
  enabled: true,
});

export const toIntegrationConnectionInput = (
  connection: IntegrationConnection
): IntegrationConnectionInput => ({
  name: connection.name,
  displayName: connection.displayName,
  role: connection.role,
  databaseType: connection.databaseType,
  databaseVersion: connection.databaseVersion,
  host: connection.host,
  port: connection.port,
  database: connection.database,
  username: connection.username,
  schemas: [...connection.schemas],
  ...(connection.databaseType === 'Oracle'
    ? {
        oracleConnectionType: connection.oracleConnectionType ?? 'SERVICE_NAME',
      }
    : {}),
  ...(connection.databaseType === 'Doris'
    ? { dorisHttpPort: connection.dorisHttpPort }
    : {}),
  tlsMode: connection.tlsMode,
  enabled: connection.enabled,
});

export const validateIntegrationConnectionInput = (
  input: IntegrationConnectionInput,
  editing: boolean
): string[] => {
  const errors: string[] = [];
  if (!/^[a-z][a-z0-9_-]{0,62}$/.test(input.name)) {
    errors.push('invalidName');
  }
  if (!input.displayName.trim() || input.displayName.length > 120) {
    errors.push('displayNameRequired');
  }
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._ -]{0,29}$/.test(input.databaseVersion)) {
    errors.push('databaseVersionRequired');
  }
  if (
    !/^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?$/.test(
      input.host
    ) ||
    input.host.includes('..')
  ) {
    errors.push('hostInvalid');
  }
  if (!Number.isInteger(input.port) || input.port < 1 || input.port > 65535) {
    errors.push('portInvalid');
  }
  if (
    !/^[a-zA-Z_][a-zA-Z0-9_.-]{0,126}$/.test(input.database) ||
    !input.username.trim() ||
    input.username.length > 128
  ) {
    errors.push('connectionFieldsRequired');
  }
  if (!editing && !input.password) {
    errors.push('passwordRequired');
  }
  if (
    input.schemas.length < 1 ||
    input.schemas.length > 8 ||
    new Set(input.schemas).size !== input.schemas.length ||
    input.schemas.some(
      (schema) => !/^[\p{L}_][\p{L}\p{N}_$#]{0,127}$/u.test(schema)
    )
  ) {
    errors.push('schemasInvalid');
  }
  if (input.databaseType === 'Doris') {
    if (input.role !== 'TARGET') {
      errors.push('dorisTargetOnly');
    }
    if (
      !Number.isInteger(input.dorisHttpPort) ||
      !input.dorisHttpPort ||
      input.dorisHttpPort < 1 ||
      input.dorisHttpPort > 65535
    ) {
      errors.push('dorisHttpPortInvalid');
    }
    if (input.tlsMode !== 'DISABLED') {
      errors.push('dorisTlsHint');
    }
    if (input.schemas.length !== 1 || input.schemas[0] !== input.database) {
      errors.push('dorisScopeHint');
    }
  }
  if (
    input.databaseType === 'Mysql' &&
    (input.schemas.length !== 1 || input.schemas[0] !== input.database)
  ) {
    errors.push('mysqlScopeHint');
  }

  return errors;
};
