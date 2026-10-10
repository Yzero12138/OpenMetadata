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

export type IntegrationDatabaseType = 'Oracle' | 'Mssql' | 'Mysql' | 'Postgres';
export type IntegrationMode = 'FULL' | 'CDC';

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

export interface IntegrationTaskInput {
  name: string;
  displayName: string;
  sourceConnectionId: string;
  targetConnectionId: string;
  sourceSchema: string;
  sourceTable: string;
  targetSchema: string;
  targetTable: string;
  mode: 'FULL' | 'CDC';
  primaryKey: string;
  fieldMappings: { source: string; target: string }[];
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
}

export interface IntegrationTask extends IntegrationTaskInput {
  id: string;
  version: number;
  createdAt: number;
  updatedAt: number;
  updatedBy: string;
  latestRun?: IntegrationRun;
  runs: IntegrationRun[];
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

export const runIntegrationTask = async (id: string, resume = false) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/run`,
    { resume }
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

export const syncIntegrationCatalog = async (id: string) => {
  const { data } = await APIClient.post<IntegrationTask>(
    `${PREFIX}/tasks/${encodeURIComponent(id)}/catalog`
  );

  return data;
};

export const getIntegrationFailure = (error: unknown): IntegrationFailure => {
  if (isAxiosError<{ code?: string; errorCode?: string }>(error)) {
    const status = error.response?.status;
    const code =
      error.response?.data?.errorCode ??
      error.response?.data?.code ??
      (status === 409 ? 'VERSION_CONFLICT' : 'REQUEST_FAILED');
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
    ]);

    return {
      code,
      conflict: status === 409,
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
