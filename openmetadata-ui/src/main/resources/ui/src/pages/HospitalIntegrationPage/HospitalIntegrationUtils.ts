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
  IntegrationRun,
  IntegrationTable,
  IntegrationTask,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';

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
  const errors: string[] = [];
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

  return errors;
};

export const integrationErrorKey = (code?: string) => {
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
    'SAVEPOINT_UNAVAILABLE',
    'CATALOG_SYNC_FAILED',
    'CONNECTION_NOT_FOUND',
    'CONNECTION_DISABLED',
    'CONNECTION_MANAGED',
    'CONNECTION_IN_USE',
    'CONNECTION_ROLE_MISMATCH',
    'DUPLICATE_CONNECTION',
    'CREDENTIALS_NOT_CONFIGURED',
    'CONNECTION_UNAVAILABLE',
    'MODE_UNSUPPORTED',
    'SCHEMA_NOT_ALLOWED',
    'TABLE_NOT_FOUND',
    'TABLE_LIMIT_EXCEEDED',
  ]);

  return `hospitalIntegration.errors.${
    code && knownCodes.has(code) ? code : 'REQUEST_FAILED'
  }`;
};

export const INTEGRATION_DATABASE_DEFAULTS: Record<
  IntegrationDatabaseType,
  { port: number; schemas: string[] }
> = {
  Postgres: { port: 5432, schemas: ['public'] },
  Oracle: { port: 1521, schemas: [] },
  Mssql: { port: 1433, schemas: ['dbo'] },
  Mysql: { port: 3306, schemas: [] },
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
  if (
    input.databaseType === 'Mysql' &&
    (input.schemas.length !== 1 || input.schemas[0] !== input.database)
  ) {
    errors.push('mysqlScopeHint');
  }

  return errors;
};
