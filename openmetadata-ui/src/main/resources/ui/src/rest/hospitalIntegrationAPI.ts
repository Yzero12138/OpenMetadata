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
import { isAxiosError } from 'axios';
import APIClient from '.';

export type IntegrationDatabaseType =
  | 'Oracle'
  | 'Mssql'
  | 'Mysql'
  | 'Postgres'
  | 'Doris';
export type IntegrationMode = 'FULL' | 'CDC' | 'SQL_T1';

export interface IntegrationCdcOptions {
  databaseName?: string;
  serverTimeZone?: string;
  pdbName?: string;
  rootServiceName?: string;
}

export interface IntegrationRuntimeOptions {
  fetchSize?: number | null;
  splitSize?: number | null;
  sinkBatchSize?: number | null;
  checkpointIntervalSeconds?: number | null;
  checkpointTimeoutSeconds?: number | null;
}

export interface IntegrationCdcCheck {
  id: string;
  status: 'PASS' | 'FAIL' | 'UNKNOWN' | 'NOT_APPLICABLE';
  code: string;
  required: boolean;
}

export interface IntegrationCdcPreflightResult {
  connectionId: string;
  databaseType: IntegrationDatabaseType;
  databaseVersion?: string;
  databaseName?: string;
  implemented: boolean;
  ready: boolean;
  checks: IntegrationCdcCheck[];
}

export interface IntegrationCdcPreflightInput {
  schema?: string;
  table?: string;
  columns?: string[];
  cdc?: IntegrationCdcOptions;
  targetConnectionId?: string;
  targetSchema?: string;
  targetTable?: string;
}

export interface IntegrationTargetCount {
  count: number;
  checkedAt: number;
}

export interface IntegrationSchedule {
  enabled: boolean;
  dailyTime: string;
  timeZone: string;
}

export interface IntegrationInitializationConfig {
  enabled: boolean;
  startDate: string;
  batchUnit: 'DAY' | 'MONTH';
}

export interface IntegrationInitializationState {
  id: string;
  status: 'PENDING' | 'RUNNING' | 'FAILED' | 'COMPLETED';
  startDate: string;
  cutoffDate: string;
  nextDate: string;
  batchUnit: 'DAY' | 'MONTH';
  completedBatches: number;
  totalBatches: number;
  pendingStartDate?: string;
  pendingEndDate?: string;
  pendingJobId?: string;
  startedAt?: number;
  completedAt?: number;
  lastError?: string;
}

export type IntegrationInitializationAction = 'PAUSE' | 'CONTINUE';

export interface IntegrationRunOptions {
  businessDate?: string;
  retry?: boolean;
}

export interface IntegrationQuerySchemaInput {
  connectionId: string;
  schema: string;
  query: string;
  businessDate?: string;
}

export interface IntegrationConnectionInput {
  name: string;
  displayName: string;
  role: 'SOURCE' | 'TARGET';
  databaseType: IntegrationDatabaseType;
  databaseVersion: string;
  host: string;
  port: number;
  database: string;
  username: string;
  password?: string;
  schemas: string[];
  dorisHttpPort?: number;
  oracleConnectionType?: 'SERVICE_NAME' | 'SID';
  tlsMode: 'DISABLED' | 'VERIFY';
  enabled: boolean;
}

export interface IntegrationConnection
  extends Omit<IntegrationConnectionInput, 'password'> {
  id: string;
  version: number;
  managed: boolean;
  synthetic: boolean;
  passwordSet: boolean;
  supportedModes: IntegrationMode[];
  createdAt?: number;
  updatedAt?: number;
  updatedBy?: string;
}

export interface IntegrationColumn {
  name: string;
  dataType: string;
  nullable: boolean;
  primaryKey: boolean;
}

export interface IntegrationTable {
  schema: string;
  name: string;
  columns: IntegrationColumn[];
}

export interface IntegrationTargetTableInput {
  sourceConnectionId: string;
  sourceSchema: string;
  sourceTable: string;
  targetSchema: string;
  targetTable: string;
  fieldMappings: { source: string; target: string }[];
  mode?: IntegrationMode;
  sourceQuery?: string;
  primaryKey?: string;
}

export interface IntegrationTargetTablePreview {
  targetConnectionId: string;
  previewHash: string;
  ddl: string;
  table: IntegrationTable;
  fieldMappings: IntegrationTargetTableInput['fieldMappings'];
}

export type DorisColumnType =
  | 'BOOLEAN'
  | 'TINYINT'
  | 'SMALLINT'
  | 'INT'
  | 'BIGINT'
  | 'FLOAT'
  | 'DOUBLE'
  | 'DECIMAL'
  | 'CHAR'
  | 'VARCHAR'
  | 'STRING'
  | 'DATE'
  | 'DATETIME';

export type DorisDefaultKind =
  | 'NONE'
  | 'NULL'
  | 'LITERAL'
  | 'CURRENT_DATE'
  | 'CURRENT_TIMESTAMP';

export interface DorisColumnInput {
  name: string;
  type: string;
  length?: number;
  precision?: number;
  scale?: number;
  nullable: boolean;
  primaryKey: boolean;
  defaultKind: DorisDefaultKind;
  defaultValue?: string;
  comment: string;
}

export interface DorisTableDesignInput {
  targetSchema: string;
  targetTable: string;
  comment: string;
  buckets: number;
  columns: DorisColumnInput[];
}

export interface DorisAddColumnsInput {
  columns: DorisColumnInput[];
}

export interface DorisTableStructure {
  targetConnectionId: string;
  targetSchema: string;
  targetTable: string;
  comment: string;
  model: string;
  buckets?: number;
  replicationNum?: number;
  structureHash: string;
  columns: DorisColumnInput[];
  table: IntegrationTable;
  ddl: string;
  change?: { jobId: string; state: string };
}

export interface DorisTableDesignPreview {
  targetConnectionId: string;
  action: 'CREATE' | 'ADD_COLUMNS';
  targetSchema: string;
  targetTable: string;
  previewHash: string;
  ddl: string;
  columns: DorisColumnInput[];
  table: IntegrationTable;
}

export interface DorisTableMutationResult {
  status: 'APPLIED' | 'SUBMITTED';
  structure: DorisTableStructure;
}

export interface IntegrationTaskInput {
  name: string;
  displayName: string;
  sourceConnectionId: string;
  targetConnectionId: string;
  sourceSchema: string;
  sourceTable: string;
  targetSchema: string;
  targetTable: string;
  mode: IntegrationMode;
  sourceQuery?: string;
  schedule?: IntegrationSchedule;
  initialization?: IntegrationInitializationConfig;
  primaryKey: string;
  fieldMappings: { source: string; target: string }[];
  cdc?: IntegrationCdcOptions;
  runtime?: IntegrationRuntimeOptions;
}

export interface IntegrationRun {
  jobId: string;
  status: string;
  submittedAt: number;
  finishedAt?: number;
  sourceReceivedCount?: number;
  sinkWriteCount?: number;
  errorCode?: string;
  errorMessage?: string;
  savepointRequested?: boolean;
  canResume?: boolean;
  businessDate?: string;
  windowStart?: string;
  windowEnd?: string;
  trigger?: string;
  scheduledFor?: number;
  initializationId?: string;
  batchIndex?: number;
  checkpoints?: {
    triggered: number;
    completed: number;
    failed: number;
    restored: number;
    latestCompletedAt?: number | null;
    latestSavepointAt?: number | null;
    updatedAt?: number | null;
  };
  checkpointsCheckedAt?: number;
  configurationFingerprint?: string;
}

export interface IntegrationTask extends IntegrationTaskInput {
  id: string;
  version: number;
  createdAt: number;
  updatedAt: number;
  updatedBy: string;
  latestRun?: IntegrationRun;
  runs: IntegrationRun[];
  initializationState?: IntegrationInitializationState;
  scheduleState?: {
    nextRunAt?: number;
    lastBusinessDate?: string;
    pendingBusinessDate?: string;
    lastError?: string;
  };
  catalog: {
    status: 'PENDING' | 'SYNCED' | 'FAILED';
    syncedAt?: number;
    sourceFqn?: string;
    targetFqn?: string;
    pipelineFqn?: string;
    errorCode?: string;
    errorMessage?: string;
  };
}

export interface IntegrationEngineStatus {
  enabled: boolean;
  reachable: boolean;
  engineVersion?: string;
  errorCode?: string;
}

export interface IntegrationValidation {
  valid: boolean;
  errors: { field: string; code: string; message: string }[];
}

export interface IntegrationFailure {
  code: string;
  conflict: boolean;
  uncertain: boolean;
}

const PREFIX = '/hospital/integration';

export const preflightIntegrationConnection = async (
  id: string,
  input: IntegrationCdcPreflightInput,
  signal?: AbortSignal
): Promise<IntegrationCdcPreflightResult> => {
  const { data } = await APIClient.post<IntegrationCdcPreflightResult>(
    `${PREFIX}/connections/${encodeURIComponent(id)}/cdc-preflight`,
    input,
    { signal }
  );

  return data;
};

export const preflightIntegrationTask = async (
  input: IntegrationTaskInput,
  signal?: AbortSignal
): Promise<IntegrationCdcPreflightResult> => {
  const { data } = await APIClient.post<IntegrationCdcPreflightResult>(
    `${PREFIX}/tasks/cdc-preflight`,
    input,
    { signal }
  );

  return data;
};

export const getIntegrationTargetCount = async (
  id: string,
  signal?: AbortSignal
): Promise<IntegrationTargetCount> => {
  const { data } = await APIClient.get<IntegrationTargetCount>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/target-count`,
    { signal }
  );

  return data;
};

export const getIntegrationStatus = async (signal?: AbortSignal) => {
  const { data } = await APIClient.get<IntegrationEngineStatus>(
    `${PREFIX}/status`,
    { signal }
  );

  return data;
};

export const getIntegrationConnections = async (signal?: AbortSignal) => {
  const { data } = await APIClient.get<{ data: IntegrationConnection[] }>(
    `${PREFIX}/connections`,
    { signal }
  );

  return data.data;
};

export const getIntegrationConnection = async (
  id: string,
  signal?: AbortSignal
) => {
  const { data } = await APIClient.get<IntegrationConnection>(
    `${PREFIX}/connections/${encodeURIComponent(id)}`,
    { signal }
  );

  return data;
};

export const createIntegrationConnection = async (
  input: IntegrationConnectionInput
) => {
  const { data } = await APIClient.post<IntegrationConnection>(
    `${PREFIX}/connections`,
    input
  );

  return data;
};

export const updateIntegrationConnection = async (
  id: string,
  input: IntegrationConnectionInput,
  version: number
) => {
  const { password, ...definition } = input;
  const { data } = await APIClient.put<IntegrationConnection>(
    `${PREFIX}/connections/${encodeURIComponent(id)}`,
    { ...definition, ...(password ? { password } : {}), version }
  );

  return data;
};

export const deleteIntegrationConnection = async (
  id: string,
  version: number
) => {
  await APIClient.delete(`${PREFIX}/connections/${encodeURIComponent(id)}`, {
    params: { version },
  });
};

export const getIntegrationTableOptions = async (
  id: string,
  signal?: AbortSignal
) => {
  const { data } = await APIClient.get<{ data: IntegrationTable[] }>(
    `${PREFIX}/connections/${encodeURIComponent(id)}/table-options`,
    { signal }
  );

  return data.data;
};

export const getIntegrationTable = async (
  id: string,
  schema: string,
  table: string,
  signal?: AbortSignal
) => {
  const { data } = await APIClient.get<IntegrationTable>(
    `${PREFIX}/connections/${encodeURIComponent(
      id
    )}/tables/${encodeURIComponent(schema)}/${encodeURIComponent(table)}`,
    { signal }
  );

  return data;
};

export const getIntegrationQuerySchema = async (
  input: IntegrationQuerySchemaInput,
  signal?: AbortSignal
) => {
  const { data } = await APIClient.post<IntegrationTable>(
    `${PREFIX}/query/schema`,
    input,
    { signal }
  );

  return data;
};

export const previewIntegrationTargetTable = async (
  id: string,
  input: IntegrationTargetTableInput,
  signal?: AbortSignal
): Promise<IntegrationTargetTablePreview> => {
  const { data } = await APIClient.post<IntegrationTargetTablePreview>(
    PREFIX + '/connections/' + encodeURIComponent(id) + '/tables/preview',
    input,
    { signal }
  );

  return data;
};

export const createIntegrationTargetTable = async (
  id: string,
  input: IntegrationTargetTableInput & { previewHash: string }
): Promise<IntegrationTable> => {
  const { data } = await APIClient.post<IntegrationTable>(
    PREFIX + '/connections/' + encodeURIComponent(id) + '/tables',
    input
  );

  return data;
};

const dorisStructurePath = (id: string, schema: string, table: string) =>
  `${PREFIX}/connections/${encodeURIComponent(id)}/tables/${encodeURIComponent(
    schema
  )}/${encodeURIComponent(table)}`;

export const getDorisTableStructure = async (
  id: string,
  schema: string,
  table: string,
  signal?: AbortSignal
): Promise<DorisTableStructure> => {
  const { data } = await APIClient.get<DorisTableStructure>(
    dorisStructurePath(id, schema, table) + '/structure',
    { signal }
  );

  return data;
};

export const previewDesignedDorisTable = async (
  id: string,
  input: DorisTableDesignInput,
  signal?: AbortSignal
): Promise<DorisTableDesignPreview> => {
  const { data } = await APIClient.post<DorisTableDesignPreview>(
    `${PREFIX}/connections/${encodeURIComponent(id)}/table-design/preview`,
    input,
    { signal }
  );

  return data;
};

export const createDesignedDorisTable = async (
  id: string,
  input: DorisTableDesignInput & { previewHash: string }
): Promise<DorisTableMutationResult> => {
  const { data } = await APIClient.post<DorisTableMutationResult>(
    `${PREFIX}/connections/${encodeURIComponent(id)}/table-design`,
    input
  );

  return data;
};

export const previewDorisTableColumns = async (
  id: string,
  schema: string,
  table: string,
  input: DorisAddColumnsInput,
  signal?: AbortSignal
): Promise<DorisTableDesignPreview> => {
  const { data } = await APIClient.post<DorisTableDesignPreview>(
    dorisStructurePath(id, schema, table) + '/columns/preview',
    input,
    { signal }
  );

  return data;
};

export const addDorisTableColumns = async (
  id: string,
  schema: string,
  table: string,
  input: DorisAddColumnsInput & { previewHash: string }
): Promise<DorisTableMutationResult> => {
  const { data } = await APIClient.post<DorisTableMutationResult>(
    dorisStructurePath(id, schema, table) + '/columns',
    input
  );

  return data;
};

export const testIntegrationConnection = async (id: string) => {
  const { data } = await APIClient.post<{
    connected: boolean;
    errorCode?: string;
  }>(`${PREFIX}/connections/${encodeURIComponent(id)}/test`);

  return data;
};

export const getIntegrationTables = async (
  id: string,
  signal?: AbortSignal
) => {
  const { data } = await APIClient.get<{ data: IntegrationTable[] }>(
    `${PREFIX}/connections/${encodeURIComponent(id)}/tables`,
    { signal }
  );

  return data.data;
};

export const getIntegrationTasks = async (signal?: AbortSignal) => {
  const { data } = await APIClient.get<{ data: IntegrationTask[] }>(
    `${PREFIX}/tasks`,
    { signal }
  );

  return data.data;
};

export const getIntegrationTask = async (id: string, signal?: AbortSignal) => {
  const { data } = await APIClient.get<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}`,
    { signal }
  );

  return data;
};

export const validateIntegrationTask = async (input: IntegrationTaskInput) => {
  const { data } = await APIClient.post<IntegrationValidation>(
    `${PREFIX}/tasks/validate`,
    input
  );

  return data;
};

export const createIntegrationTask = async (input: IntegrationTaskInput) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks`,
    input
  );

  return data;
};

export const updateIntegrationTask = async (
  id: string,
  input: IntegrationTaskInput,
  version: number
) => {
  const { data } = await APIClient.put<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}`,
    { ...input, version }
  );

  return data;
};

export const runIntegrationTask = async (
  id: string,
  resume = false,
  options?: IntegrationRunOptions
) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/run`,
    { resume, ...options }
  );

  return data;
};

export const stopIntegrationTask = async (id: string, savepoint: boolean) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/stop`,
    { savepoint }
  );

  return data;
};

export const updateIntegrationInitialization = async (
  id: string,
  action: IntegrationInitializationAction,
  version: number
) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/initialization`,
    { version, action }
  );

  return data;
};

export const syncIntegrationCatalog = async (id: string) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/catalog`
  );

  return data;
};

export const MSSQL_CDC_PREREQUISITE_REJECTION_CODES = [
  'MSSQL_CDC_OPTIONS_INVALID',
  'MSSQL_CDC_ROUTE_UNSUPPORTED',
  'MSSQL_DB_NAME_MISMATCH',
  'MSSQL_CDC_IDENTIFIER_UNSUPPORTED',
  'MSSQL_CDC_TYPE_UNSUPPORTED',
  'MSSQL_CDC_TARGET_PROJECTION_INCOMPLETE',
] as const;

export const getIntegrationFailure = (error: unknown): IntegrationFailure => {
  if (isAxiosError<{ code?: string; errorCode?: string }>(error)) {
    const status = error.response?.status;
    const code =
      error.response?.data?.errorCode ??
      error.response?.data?.code ??
      'REQUEST_FAILED';
    const rejectedBeforeSubmission = new Set([
      'INTEGRATION_DISABLED',
      'ENGINE_UNAVAILABLE',
      'JOB_NOT_FOUND',
      'INVALID_CONFIGURATION',
      'VERSION_CONFLICT',
      'TASK_ACTIVE',
      'SAVEPOINT_UNAVAILABLE',
      'CREDENTIALS_NOT_CONFIGURED',
      'CONNECTION_UNAVAILABLE',
      'CONNECTION_DISABLED',
      'MODE_UNSUPPORTED',
      'DORIS_LABEL_INVALID',
      'INVALID_SOURCE_QUERY',
      'SQL_DATE_PARAMETER_REQUIRED',
      'SQL_PARAMETER_UNSUPPORTED',
      'SCHEMA_NOT_ALLOWED',
      'INVALID_QUERY_COLUMNS',
      'INVALID_SCHEDULE',
      'BUSINESS_DATE_REQUIRED',
      'BUSINESS_DATE_INVALID',
      'SQL_INITIALIZATION_PARAMETERS_REQUIRED',
      'INITIALIZATION_STATE_INVALID',
      'INITIALIZATION_IN_PROGRESS',
      'INITIALIZATION_CONFIGURATION_LOCKED',
      'INITIALIZATION_UNAVAILABLE',
      'CDC_PREFLIGHT_FAILED',
      'CDC_CONFIGURATION_CHANGED',
      'CDC_LEGACY_RESUME_UNSAFE',
      'TARGET_WRITER_ACTIVE',
      'TARGET_COUNT_UNAVAILABLE',
      'CDC_TRUSTED_INSPECTION_REQUIRED',
      'ORACLE_CDC_OPTIONS_INVALID',
      'ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED',
      'ORACLE_CDC_IDENTIFIER_UNSUPPORTED',
      'ORACLE_CDC_TYPE_UNSUPPORTED',
      'ORACLE_CDC_LOB_UNVERIFIED',
      'ORACLE_CDC_STRING_BYTES_UNVERIFIED',
      'ORACLE_CDC_EXTENDED_STRING_UNVERIFIED',
      ...MSSQL_CDC_PREREQUISITE_REJECTION_CODES,
    ]);

    return {
      code,
      conflict: status === 409 && code === 'VERSION_CONFLICT',
      uncertain:
        code === 'SUBMISSION_UNKNOWN' ||
        !error.response ||
        (status !== undefined &&
          status >= 500 &&
          !rejectedBeforeSubmission.has(code)),
    };
  }

  return { code: 'REQUEST_FAILED', conflict: false, uncertain: true };
};
