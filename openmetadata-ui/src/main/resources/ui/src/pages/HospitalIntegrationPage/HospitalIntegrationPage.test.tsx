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
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReactNode } from 'react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { ROUTES } from '../../constants/constants';
import * as api from '../../rest/hospitalIntegrationAPI';
import { HospitalDataSourcesWorkspace } from './HospitalDataSourcesPage';
import { HospitalIntegrationWorkspace } from './HospitalIntegrationPage';

jest.mock('../../components/PageLayoutV1/PageLayoutV1', () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));
jest.mock('../../hooks/useApplicationStore', () => ({
  useApplicationStore: jest.fn(),
}));
jest.mock('../../utils/ToastUtils', () => ({
  showSuccessToast: jest.fn(),
  showErrorToast: jest.fn(),
}));
jest.mock('../../rest/hospitalIntegrationAPI', () => ({
  ...jest.requireActual('../../rest/hospitalIntegrationAPI'),
  getIntegrationStatus: jest.fn(),
  getIntegrationConnections: jest.fn(),
  getIntegrationTables: jest.fn(),
  getIntegrationConnection: jest.fn(),
  createIntegrationConnection: jest.fn(),
  updateIntegrationConnection: jest.fn(),
  deleteIntegrationConnection: jest.fn(),
  getIntegrationTableOptions: jest.fn(),
  getIntegrationTable: jest.fn(),
  getIntegrationQuerySchema: jest.fn(),
  getIntegrationTasks: jest.fn(),
  getIntegrationTask: jest.fn(),
  validateIntegrationTask: jest.fn(),
  createIntegrationTask: jest.fn(),
  updateIntegrationTask: jest.fn(),
  updateIntegrationInitialization: jest.fn(),
  runIntegrationTask: jest.fn(),
  stopIntegrationTask: jest.fn(),
  syncIntegrationCatalog: jest.fn(),
  testIntegrationConnection: jest.fn(),
  previewIntegrationTargetTable: jest.fn(),
  createIntegrationTargetTable: jest.fn(),
  preflightIntegrationTask: jest.fn(),
  preflightIntegrationConnection: jest.fn(),
  getIntegrationTargetCount: jest.fn(),
}));

const mockApi = api as jest.Mocked<typeof api>;
const scrollIntoViewMock = jest.fn();
const expectRevealedError = (alert: HTMLElement) => {
  expect(alert).toHaveAttribute('tabindex', '-1');
  expect(alert).toHaveFocus();
  expect(scrollIntoViewMock).toHaveBeenLastCalledWith(
    expect.objectContaining({ block: 'nearest' })
  );
  expect(scrollIntoViewMock.mock.instances.slice(-1)[0]).toBe(alert);
};
const table: api.IntegrationTable = {
  schema: 'public',
  name: 'synthetic_encounter',
  columns: [
    { name: 'id', dataType: 'bigint', nullable: false, primaryKey: true },
    {
      name: 'department',
      dataType: 'varchar',
      nullable: true,
      primaryKey: false,
    },
  ],
};
const sourceConnection: api.IntegrationConnection = {
  id: 'synthetic-source',
  version: 0,
  name: 'synthetic_source',
  displayName: 'Synthetic PostgreSQL source',
  role: 'SOURCE',
  databaseType: 'Postgres',
  databaseVersion: '17',
  host: 'source.example.invalid',
  port: 5432,
  database: 'synthetic_source',
  username: 'synthetic_reader',
  schemas: ['public'],
  tlsMode: 'VERIFY',
  enabled: true,
  managed: true,
  synthetic: true,
  passwordSet: true,
  supportedModes: ['FULL', 'CDC'],
};
const targetConnection: api.IntegrationConnection = {
  ...sourceConnection,
  id: 'synthetic-ods',
  name: 'synthetic_ods',
  displayName: 'Synthetic PostgreSQL ODS',
  role: 'TARGET',
  schemas: ['ods'],
};
const businessConnection: api.IntegrationConnection = {
  ...sourceConnection,
  id: 'business-source',
  name: 'business_source',
  displayName: 'Synthetic business source',
  version: 3,
  managed: false,
  synthetic: false,
};
const mssqlConnection: api.IntegrationConnection = {
  ...businessConnection,
  id: 'mssql-source',
  name: 'mssql_source',
  displayName: 'Synthetic SQL Server source',
  databaseType: 'Mssql',
  databaseVersion: '2019',
  database: 'MiXeD_Source',
  port: 1433,
};

const task: api.IntegrationTask = {
  id: 'fixture-task',
  version: 7,
  name: 'synthetic_encounter_cdc',
  displayName: 'Synthetic encounter transfer',
  sourceConnectionId: 'synthetic-source',
  targetConnectionId: 'synthetic-ods',
  sourceSchema: 'public',
  sourceTable: table.name,
  targetSchema: 'ods',
  targetTable: table.name,
  mode: 'CDC',
  primaryKey: 'id',
  fieldMappings: [
    { source: 'id', target: 'id' },
    { source: 'department', target: 'department' },
  ],
  createdAt: 1791500000000,
  updatedAt: 1791500000000,
  updatedBy: 'synthetic-admin',
  runs: [],
  catalog: { status: 'PENDING' },
};
const finished: api.IntegrationRun = {
  jobId: '9223372036854775807',
  status: 'FINISHED',
  submittedAt: 1791500000000,
  finishedAt: 1791500010000,
};
const withRun = (run: api.IntegrationRun): api.IntegrationTask => ({
  ...task,
  latestRun: run,
});

const LocationProbe = () => {
  const location = useLocation();
  const navigate = useNavigate();

  return (
    <>
      <span hidden data-testid="integration-location">
        {location.pathname}
        {location.search}
      </span>
      <button
        data-testid="integration-history-back"
        onClick={() => navigate(-1)}>
        Back fixture
      </button>
      <button
        data-testid="integration-history-forward"
        onClick={() => navigate(1)}>
        Forward fixture
      </button>
    </>
  );
};
const renderWorkspace = async (
  isAdmin = true,
  path = ROUTES.HOSPITAL_INTEGRATION_TASKS
) => {
  let result!: ReturnType<typeof render>;
  await act(async () => {
    result = render(
      <MemoryRouter initialEntries={[path]}>
        <HospitalIntegrationWorkspace isAdmin={isAdmin} />
        <LocationProbe />
      </MemoryRouter>
    );
  });

  return result;
};
const renderSources = async (isAdmin = true, search = '') => {
  await act(async () => {
    render(
      <MemoryRouter
        initialEntries={[ROUTES.HOSPITAL_INTEGRATION_SOURCES + search]}>
        <HospitalDataSourcesWorkspace isAdmin={isAdmin} />
        <LocationProbe />
      </MemoryRouter>
    );
  });
};
const click = async (element: HTMLElement) => {
  await act(async () => {
    fireEvent.click(element);
  });
};
const choose = async (id: string, label: string) => {
  const trigger = document.getElementById(id);

  expect(trigger).toBeDefined();

  await click(trigger!);
  await click(await screen.findByRole('option', { name: label }));
};
const openTask = async () => {
  await click(await screen.findByRole('button', { name: task.displayName }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'label.refresh' })).toBeEnabled()
  );
};

describe('Hospital integration workbench behavior', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoViewMock,
    });
    mockApi.getIntegrationStatus.mockResolvedValue({
      enabled: true,
      reachable: true,
      engineVersion: '3.0.0',
    });
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      targetConnection,
      businessConnection,
    ]);
    mockApi.getIntegrationConnection.mockResolvedValue(businessConnection);
    mockApi.getIntegrationTableOptions.mockImplementation(async (id) => [
      {
        ...table,
        schema: id === targetConnection.id ? 'ods' : 'public',
        columns: [],
      },
    ]);
    mockApi.getIntegrationTable.mockImplementation(
      async (_id, schema, name) => ({ ...table, schema, name })
    );
    mockApi.testIntegrationConnection.mockResolvedValue({ connected: true });
    mockApi.getIntegrationTasks.mockResolvedValue([task]);
    mockApi.getIntegrationTask.mockResolvedValue(task);
    mockApi.validateIntegrationTask.mockResolvedValue({
      valid: true,
      errors: [],
    });
    mockApi.runIntegrationTask.mockResolvedValue(
      withRun({ ...finished, status: 'RUNNING', finishedAt: undefined })
    );
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: sourceConnection.id,
      databaseType: 'Postgres',
      implemented: true,
      ready: true,
      checks: [],
    });
    mockApi.preflightIntegrationConnection.mockResolvedValue({
      connectionId: sourceConnection.id,
      databaseType: 'Postgres',
      implemented: true,
      ready: true,
      checks: [],
    });
    mockApi.getIntegrationTargetCount.mockResolvedValue({
      count: 0,
      checkedAt: 1791500020000,
    });
  });

  it('requires explicit task evidence and rechecks actual saved renamed mappings immediately before run', async () => {
    const renamed = {
      ...task,
      fieldMappings: [{ source: 'id', target: 'encounter_id' }],
    };
    mockApi.getIntegrationTask.mockResolvedValue(renamed);
    await renderWorkspace();
    await openTask();

    expect(mockApi.preflightIntegrationTask).not.toHaveBeenCalled();
    expect(mockApi.getIntegrationTargetCount).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled();

    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );

    expect(mockApi.preflightIntegrationTask).toHaveBeenLastCalledWith(
      expect.objectContaining({
        primaryKey: 'id',
        fieldMappings: renamed.fieldMappings,
      }),
      expect.any(AbortSignal)
    );

    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(mockApi.preflightIntegrationTask).toHaveBeenCalledTimes(2);
    expect(mockApi.runIntegrationTask).toHaveBeenCalledWith(task.id, false);
  });

  it('never latches submission uncertainty when the fresh preflight times out before run', async () => {
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    mockApi.preflightIntegrationTask.mockRejectedValueOnce({
      isAxiosError: true,
    });
    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
    expect(
      screen.queryByText('hospitalIntegration.errors.SUBMISSION_UNKNOWN')
    ).not.toBeInTheDocument();
    expect(
      screen.getByTestId('integration-task-preflight-drawer')
    ).toBeInTheDocument();

    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));

    expect(screen.getByRole('button', { name: 'label.run' })).toBeEnabled();
  });

  it('invalidates previous readiness on a CDC rejection without displaying version reload', async () => {
    mockApi.runIntegrationTask.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { code: 'CDC_PREFLIGHT_FAILED' } },
    });
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled();
    expect(
      screen.queryByTestId('integration-task-reload')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('hospitalIntegration.errors.SUBMISSION_UNKNOWN')
    ).not.toBeInTheDocument();
  });

  it('saves a structurally valid CDC draft when execution preflight is not ready', async () => {
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: sourceConnection.id,
      databaseType: 'Oracle',
      implemented: true,
      ready: false,
      checks: [
        {
          id: 'lob',
          status: 'FAIL',
          code: 'ORACLE_CDC_LOB_UNVERIFIED',
          required: true,
        },
      ],
    });
    mockApi.updateIntegrationTask.mockResolvedValue(task);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(await screen.findByText('hospitalCdc.lob')).toBeInTheDocument();

    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalled();
  });

  it('queries target rows only on explicit action and preserves a measured zero and timestamp', async () => {
    await renderWorkspace();
    await openTask();

    expect(mockApi.getIntegrationTargetCount).not.toHaveBeenCalled();

    await click(
      screen.getByRole('button', { name: 'hospitalCdc.countTarget' })
    );

    expect(mockApi.getIntegrationTargetCount).toHaveBeenCalledWith(
      task.id,
      expect.any(AbortSignal)
    );
    expect(screen.getByTestId('integration-target-count')).toHaveTextContent(
      '0'
    );

    await click(screen.getByRole('button', { name: 'label.refresh' }));

    expect(mockApi.getIntegrationTargetCount).toHaveBeenCalledTimes(1);
  });

  it('rechecks a legacy PostgreSQL resume without inventing runtime options or requiring a fingerprint', async () => {
    mockApi.getIntegrationTask.mockResolvedValue(
      withRun({ ...finished, status: 'SAVEPOINT_DONE', canResume: true })
    );
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(screen.getByRole('button', { name: 'label.resume' }));

    expect(mockApi.preflightIntegrationTask).toHaveBeenCalledTimes(2);
    expect(
      mockApi.preflightIntegrationTask.mock.calls[1][0]
    ).not.toHaveProperty('runtime');
    expect(
      mockApi.preflightIntegrationTask.mock.calls[1][0]
    ).not.toHaveProperty('cdc');
    expect(mockApi.runIntegrationTask).toHaveBeenCalledWith(task.id, true);
  });

  it('checks basic form validity before task preflight without inventing placeholder names', async () => {
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );
    fireEvent.change(document.getElementById('integration-task-name')!, {
      target: { value: '' },
    });
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(mockApi.preflightIntegrationTask).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.invalidName'
    );
  });

  it.each(['close', 'history'])(
    'guards a clean saved task with only an invalid raw runtime draft on %s',
    async (action) => {
      await renderWorkspace();
      await openTask();
      await click(screen.getByRole('button', { name: 'label.edit' }));
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
      );
      fireEvent.click(screen.getByText('hospitalCdc.runtimeSettings'));
      fireEvent.change(
        document.getElementById('integration-runtime-fetchSize')!,
        { target: { value: '1e3' } }
      );
      const requestLeave = () =>
        click(
          action === 'close'
            ? screen.getByRole('button', { name: 'label.close' })
            : screen.getByTestId('integration-history-back')
        );
      await requestLeave();

      expect(screen.getByText('message.unsaved-form-data')).toBeInTheDocument();
      expect(screen.getByTestId('integration-location')).toHaveTextContent(
        '/tasks/' + task.id
      );

      await click(
        screen.getByRole('button', { name: 'label.continue-editing' })
      );

      expect(
        document.getElementById('integration-runtime-fetchSize')
      ).toHaveValue('1e3');
      expect(mockApi.updateIntegrationTask).not.toHaveBeenCalled();

      await requestLeave();
      await click(screen.getByRole('button', { name: 'label.discard' }));
      await waitFor(() =>
        expect(
          screen.queryByTestId('integration-task-drawer')
        ).not.toBeInTheDocument()
      );
      if (action === 'history') {
        await openTask();
      }
      await click(screen.getByRole('button', { name: 'label.edit' }));
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
      );

      expect(
        document.getElementById('integration-runtime-fetchSize')
      ).toHaveValue('');

      await click(screen.getByRole('button', { name: 'label.close' }));

      expect(
        screen.queryByText('message.unsaved-form-data')
      ).not.toBeInTheDocument();
    }
  );

  it('clears invalid runtime dirty state after returning the value to its unchanged inherited blank', async () => {
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
    );
    fireEvent.click(screen.getByText('hospitalCdc.runtimeSettings'));
    const fetch = document.getElementById('integration-runtime-fetchSize')!;
    fireEvent.change(fetch, { target: { value: '1e3' } });
    fireEvent.change(fetch, { target: { value: '' } });
    await click(screen.getByRole('button', { name: 'label.close' }));

    expect(
      screen.queryByText('message.unsaved-form-data')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId('integration-task-drawer')
    ).not.toBeInTheDocument();
  });

  it('renders inherited null runtime fields blank and preserves them through edit, task preflight and save', async () => {
    const nullable = {
      ...task,
      runtime: {
        fetchSize: null,
        splitSize: null,
        sinkBatchSize: null,
        checkpointIntervalSeconds: null,
        checkpointTimeoutSeconds: null,
      },
    };
    mockApi.getIntegrationTask.mockResolvedValue(nullable);
    mockApi.updateIntegrationTask.mockImplementation(async (_id, input) => ({
      ...nullable,
      ...input,
    }));
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );

    expect(
      document.getElementById('integration-runtime-fetchSize')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-runtime-fetchSize')
    ).toHaveAttribute('placeholder', '1000');

    fireEvent.change(document.getElementById('integration-task-displayName')!, {
      target: { value: task.displayName + ' reviewed' },
    });
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(mockApi.preflightIntegrationTask).toHaveBeenLastCalledWith(
      expect.objectContaining({ runtime: nullable.runtime }),
      expect.any(AbortSignal)
    );

    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.objectContaining({ runtime: nullable.runtime }),
      task.version
    );

    await click(screen.getByRole('button', { name: 'label.edit' }));

    expect(
      document.getElementById('integration-runtime-fetchSize')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-runtime-checkpointIntervalSeconds')
    ).toHaveValue('');
  });

  it('saves and reopens explicit Oracle options while displaying discovered DB_NAME without backfilling it', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      {
        ...sourceConnection,
        databaseType: 'Oracle',
        database: 'SERVICE_ONLY',
        databaseVersion: '11.2.0.1.0',
      },
      targetConnection,
    ]);
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: sourceConnection.id,
      databaseType: 'Oracle',
      databaseName: 'ORCL',
      databaseVersion: '11.2.0.1.0',
      implemented: true,
      ready: false,
      checks: [
        {
          id: 'column-types',
          code: 'ORACLE_CDC_LOB_UNVERIFIED',
          status: 'FAIL',
          required: true,
        },
      ],
    });
    mockApi.updateIntegrationTask.mockImplementation(async (_id, input) => ({
      ...task,
      ...input,
    }));
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );

    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');

    fireEvent.change(
      document.getElementById('integration-cdc-server-time-zone')!,
      { target: { value: 'Asia/Shanghai' } }
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(screen.getByText('ORCL')).toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');

    fireEvent.click(screen.getByText('hospitalCdc.runtimeSettings'));
    fireEvent.change(
      document.getElementById('integration-runtime-fetchSize')!,
      { target: { value: '2000' } }
    );
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.objectContaining({
        cdc: { serverTimeZone: 'Asia/Shanghai' },
        runtime: { fetchSize: 2000 },
      }),
      task.version
    );

    await click(screen.getByRole('button', { name: 'label.edit' }));

    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toHaveValue('Asia/Shanghai');
    expect(
      document.getElementById('integration-runtime-fetchSize')
    ).toHaveValue('2000');
  });

  it('gives integration tasks their own page heading', async () => {
    await renderWorkspace();

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'hospitalIntegration.tasks',
      })
    ).toBeInTheDocument();
  });

  it('aborts fresh execution preflight on administrator role loss before any run request', async () => {
    const view = await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    let resolve!: (value: api.IntegrationCdcPreflightResult) => void;
    mockApi.preflightIntegrationTask.mockImplementationOnce(
      () =>
        new Promise((success) => {
          resolve = success;
        })
    );
    await click(screen.getByRole('button', { name: 'label.run' }));
    const signal = mockApi.preflightIntegrationTask.mock.calls[1][1];
    view.rerender(
      <MemoryRouter>
        <HospitalIntegrationWorkspace isAdmin={false} />
      </MemoryRouter>
    );

    expect(signal?.aborted).toBe(true);

    await act(async () =>
      resolve({
        connectionId: sourceConnection.id,
        databaseType: 'Postgres',
        implemented: true,
        ready: true,
        checks: [],
      })
    );

    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('keeps checkpoint zero distinct from unknown and preserves the last successful sample through task refresh', async () => {
    const sampled = withRun({
      ...finished,
      checkpoints: { triggered: 0, completed: 0, failed: 0, restored: 0 },
      checkpointsCheckedAt: 1791500000000,
    });
    mockApi.getIntegrationTask.mockResolvedValue(sampled);
    await renderWorkspace();
    await openTask();

    expect(
      screen.getByTestId('integration-checkpoint-completed')
    ).toHaveTextContent('0');
    expect(
      screen.getByTestId('integration-checkpoint-sample')
    ).toHaveTextContent('hospitalCdc.sampleAge');

    await click(screen.getByRole('button', { name: 'label.refresh' }));

    expect(
      screen.getByTestId('integration-checkpoint-sample')
    ).toHaveTextContent('hospitalCdc.sampleAge');

    mockApi.getIntegrationTask.mockResolvedValue(withRun(finished));
    await click(screen.getByRole('button', { name: 'label.refresh' }));

    expect(
      screen.getByTestId('integration-checkpoint-completed')
    ).toHaveTextContent('hospitalIntegration.unavailable');
    expect(
      screen.getByTestId('integration-checkpoint-sample')
    ).toHaveTextContent('hospitalCdc.sampleUnknown');
  });

  it('offers server-advertised source preflight separately and cancels its request when closed', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      targetConnection,
      {
        ...businessConnection,
        id: 'mssql',
        databaseType: 'Mssql',
        supportedModes: ['FULL'],
      },
    ]);
    await renderSources();

    expect(
      screen.getAllByRole('button', { name: 'hospitalCdc.sourcePreflight' })
    ).toHaveLength(1);
    expect(mockApi.preflightIntegrationConnection).not.toHaveBeenCalled();

    await click(
      screen.getByRole('button', { name: 'hospitalCdc.sourcePreflight' })
    );
    const select = screen.getByRole('button', {
      name: /hospitalIntegration.sourceTable/,
    });
    await click(select);
    await click(await screen.findByRole('option', { name: table.name }));
    mockApi.preflightIntegrationConnection.mockImplementationOnce(
      () => new Promise(() => undefined)
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));
    const signal = mockApi.preflightIntegrationConnection.mock.calls[0][2];

    expect(screen.getByText('hospitalCdc.sourceOnly')).toBeInTheDocument();

    await click(screen.getByRole('button', { name: 'label.close' }));

    expect(signal?.aborted).toBe(true);
    expect(mockApi.preflightIntegrationTask).not.toHaveBeenCalled();
    expect(mockApi.getIntegrationTargetCount).not.toHaveBeenCalled();
  });

  it('offers SQL Server CDC only for an advertised, enabled source and keeps other connectors gated', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      mssqlConnection,
      {
        ...mssqlConnection,
        id: 'full-only',
        displayName: 'Full only SQL Server',
        supportedModes: ['FULL'],
      },
      {
        ...mssqlConnection,
        id: 'disabled',
        displayName: 'Disabled SQL Server',
        enabled: false,
      },
      {
        ...mssqlConnection,
        id: 'target',
        displayName: 'Target SQL Server',
        role: 'TARGET',
      },
      {
        ...mssqlConnection,
        id: 'mysql',
        displayName: 'MySQL source',
        databaseType: 'Mysql',
      },
    ]);
    await renderSources();
    const action = (id: string) =>
      within(
        screen.getByTestId('integration-connection-row-' + id)
      ).queryByRole('button', { name: 'hospitalCdc.sourcePreflight' });

    expect(action(mssqlConnection.id)).toBeEnabled();
    expect(action('disabled')).toBeDisabled();
    expect(action('full-only')).not.toBeInTheDocument();
    expect(action('target')).not.toBeInTheDocument();
    expect(action('mysql')).not.toBeInTheDocument();
  });

  it('discovers the exact SQL Server database without filling the optional hint or exposing Oracle fields', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([mssqlConnection]);
    mockApi.preflightIntegrationConnection.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      databaseName: 'MiXeD_Source',
      implemented: true,
      ready: false,
      checks: [
        {
          id: 'server-time-zone',
          code: 'MSSQL_TIME_ZONE_REQUIRED',
          status: 'FAIL',
          required: true,
        },
      ],
    });
    await renderSources();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.sourcePreflight' })
    );
    await choose('integration-preflight-source-table', table.name);

    expect(screen.getByText('hospitalCdc.mssqlSettings')).toBeInTheDocument();
    expect(
      screen.getByText('hospitalCdc.mssqlDatabaseHint')
    ).toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toBeRequired();
    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-cdc-pdb')
    ).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-root')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('hospitalCdc.oracleSettings')
    ).not.toBeInTheDocument();

    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(mockApi.preflightIntegrationConnection).toHaveBeenLastCalledWith(
      mssqlConnection.id,
      { schema: 'public', table: table.name },
      expect.any(AbortSignal)
    );
    expect(
      within(
        screen.getByTestId('integration-source-preflight-drawer')
      ).getByText('MiXeD_Source')
    ).toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');
    expect(screen.getByText('hospitalCdc.sourceOnly')).toBeInTheDocument();
    expect(screen.getByText('hospitalCdc.requiredBlocked')).toBeInTheDocument();

    fireEvent.change(
      document.getElementById('integration-cdc-server-time-zone')!,
      { target: { value: 'Asia/Shanghai' } }
    );
    mockApi.preflightIntegrationConnection.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      implemented: true,
      ready: true,
      checks: [],
    });
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(mockApi.preflightIntegrationConnection).toHaveBeenLastCalledWith(
      mssqlConnection.id,
      {
        schema: 'public',
        table: table.name,
        cdc: { serverTimeZone: 'Asia/Shanghai' },
      },
      expect.any(AbortSignal)
    );
    expect(mockApi.preflightIntegrationTask).not.toHaveBeenCalled();
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('saves native SQL Server options with exact database case while required execution checks remain blocked', async () => {
    const mssqlTask = { ...task, sourceConnectionId: mssqlConnection.id };
    mockApi.getIntegrationConnections.mockResolvedValue([
      mssqlConnection,
      targetConnection,
    ]);
    mockApi.getIntegrationTask.mockResolvedValue(mssqlTask);
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      databaseName: 'MiXeD_Source',
      implemented: true,
      ready: false,
      checks: [
        {
          id: 'column-types',
          code: 'MSSQL_CDC_TYPE_UNSUPPORTED',
          status: 'FAIL',
          required: true,
        },
      ],
    });
    mockApi.updateIntegrationTask.mockImplementation(async (_id, input) => ({
      ...mssqlTask,
      ...input,
    }));
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );

    expect(document.getElementById('integration-mode')).toHaveTextContent(
      'hospitalIntegration.cdc'
    );
    expect(screen.getByText('hospitalCdc.mssqlSettings')).toBeInTheDocument();
    expect(
      screen.queryByText('hospitalCdc.oracleSettings')
    ).not.toBeInTheDocument();

    fireEvent.change(
      document.getElementById('integration-cdc-server-time-zone')!,
      { target: { value: 'Asia/Shanghai' } }
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(screen.getByText('hospitalCdc.mssqlTypes')).toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');
    expect(mockApi.preflightIntegrationTask).toHaveBeenLastCalledWith(
      expect.objectContaining({ cdc: { serverTimeZone: 'Asia/Shanghai' } }),
      expect.any(AbortSignal)
    );

    fireEvent.change(
      document.getElementById('integration-cdc-database-name')!,
      { target: { value: 'MiXeD_Source' } }
    );
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.objectContaining({
        mode: 'CDC',
        cdc: { databaseName: 'MiXeD_Source', serverTimeZone: 'Asia/Shanghai' },
      }),
      task.version
    );
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();

    await click(screen.getByRole('button', { name: 'label.edit' }));

    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('MiXeD_Source');
    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toHaveValue('Asia/Shanghai');
  });

  it('preserves CDC when switching to an advertised SQL Server source and clears all previous Oracle options', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      { ...sourceConnection, databaseType: 'Oracle' },
      mssqlConnection,
      targetConnection,
    ]);
    mockApi.getIntegrationTask.mockResolvedValue({
      ...task,
      cdc: {
        databaseName: 'ORCL',
        serverTimeZone: 'UTC',
        pdbName: 'PDB1',
        rootServiceName: 'ROOT',
      },
    });
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await choose('integration-source-connection', mssqlConnection.displayName);

    expect(document.getElementById('integration-mode')).toHaveTextContent(
      'hospitalIntegration.cdc'
    );
    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-cdc-pdb')
    ).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-root')
    ).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-source-table')
    ).not.toHaveTextContent(table.name);
    expect(
      document.getElementById('integration-target-table')
    ).toHaveTextContent(table.name);

    await choose('integration-source-connection', sourceConnection.displayName);

    expect(
      document.getElementById('integration-cdc-database-name')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toHaveValue('');
    expect(
      document.getElementById('integration-cdc-pdb')
    ).not.toBeInTheDocument();
  });

  it('allows a valid SQL Server CDC draft to omit the execution time zone', async () => {
    const mssqlTask = { ...task, sourceConnectionId: mssqlConnection.id };
    mockApi.getIntegrationConnections.mockResolvedValue([
      mssqlConnection,
      targetConnection,
    ]);
    mockApi.getIntegrationTask.mockResolvedValue(mssqlTask);
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      implemented: true,
      ready: false,
      checks: [
        {
          id: 'server-time-zone',
          code: 'MSSQL_TIME_ZONE_REQUIRED',
          status: 'FAIL',
          required: true,
        },
      ],
    });
    mockApi.updateIntegrationTask.mockResolvedValue(mssqlTask);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    expect(screen.getByText('hospitalCdc.requiredBlocked')).toBeInTheDocument();
    expect(
      document.getElementById('integration-cdc-server-time-zone')
    ).toHaveValue('');

    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.objectContaining({ mode: 'CDC' }),
      task.version
    );
    expect(mockApi.updateIntegrationTask.mock.calls[0][1].cdc).toBeUndefined();
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('does not resurrect SQL Server proof after changing the database hint away and back', async () => {
    const mssqlTask = {
      ...task,
      sourceConnectionId: mssqlConnection.id,
      cdc: { databaseName: 'MiXeD_Source', serverTimeZone: 'Asia/Shanghai' },
    };
    mockApi.getIntegrationConnections.mockResolvedValue([
      mssqlConnection,
      targetConnection,
    ]);
    mockApi.getIntegrationTask.mockResolvedValue(mssqlTask);
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      implemented: true,
      ready: true,
      checks: [],
    });
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalCdc.check' })
      ).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'hospitalCdc.check' }));

    const drawer = within(screen.getByTestId('integration-task-drawer'));

    expect(drawer.getByText('hospitalCdc.ready')).toBeInTheDocument();

    fireEvent.change(
      document.getElementById('integration-cdc-database-name')!,
      { target: { value: 'Other_Source' } }
    );
    fireEvent.change(
      document.getElementById('integration-cdc-database-name')!,
      { target: { value: 'MiXeD_Source' } }
    );

    expect(drawer.queryByText('hospitalCdc.ready')).not.toBeInTheDocument();
    expect(drawer.getByText('hospitalCdc.checkRequired')).toBeInTheDocument();
    expect(mockApi.preflightIntegrationTask).toHaveBeenCalledTimes(1);
  });

  it('blocks a SQL Server run when the fresh server preflight cannot prove metadata visibility', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      mssqlConnection,
      targetConnection,
    ]);
    mockApi.getIntegrationTask.mockResolvedValue({
      ...task,
      sourceConnectionId: mssqlConnection.id,
    });
    mockApi.preflightIntegrationTask.mockResolvedValue({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      implemented: true,
      ready: true,
      checks: [],
    });
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    mockApi.preflightIntegrationTask.mockResolvedValueOnce({
      connectionId: mssqlConnection.id,
      databaseType: 'Mssql',
      implemented: true,
      ready: true,
      checks: [
        {
          id: 'metadata-permissions',
          code: 'MSSQL_CDC_STATE_UNVERIFIED',
          status: 'UNKNOWN',
          required: true,
        },
      ],
    });
    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(mockApi.preflightIntegrationTask).toHaveBeenCalledTimes(2);
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
    expect(
      screen.getByText('hospitalCdc.mssqlStatePermissions')
    ).toBeInTheDocument();
    expect(screen.getByText('hospitalCdc.requiredBlocked')).toBeInTheDocument();
  });

  it.each([
    ['CDC_CONFIGURATION_CHANGED', 'configurationChanged'],
    ['CDC_LEGACY_RESUME_UNSAFE', 'legacyResume'],
  ])(
    'explains a SQL Server resume rejection %s without exposing SQL diagnostics or inventing a version conflict',
    async (code, key) => {
      mockApi.getIntegrationConnections.mockResolvedValue([
        mssqlConnection,
        targetConnection,
      ]);
      mockApi.getIntegrationTask.mockResolvedValue({
        ...withRun({ ...finished, status: 'SAVEPOINT_DONE', canResume: true }),
        sourceConnectionId: mssqlConnection.id,
      });
      mockApi.preflightIntegrationTask.mockResolvedValue({
        connectionId: mssqlConnection.id,
        databaseType: 'Mssql',
        implemented: true,
        ready: true,
        checks: [],
      });
      mockApi.runIntegrationTask.mockRejectedValueOnce({
        isAxiosError: true,
        response: {
          status: 409,
          data: { code, message: 'Private SQL Server SELECT diagnostic' },
        },
      });
      await renderWorkspace();
      await openTask();
      await click(
        screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
      );
      await click(screen.getByRole('button', { name: 'label.close' }));
      await click(screen.getByRole('button', { name: 'label.resume' }));

      expect(mockApi.preflightIntegrationTask).toHaveBeenCalledTimes(2);
      expect(mockApi.runIntegrationTask).toHaveBeenCalledWith(task.id, true);
      expect(screen.getByText('hospitalCdc.' + key)).toBeInTheDocument();
      expect(screen.queryByText(code)).not.toBeInTheDocument();
      expect(
        screen.queryByText('Private SQL Server SELECT diagnostic')
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText('hospitalIntegration.errors.SUBMISSION_UNKNOWN')
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId('integration-task-reload')
      ).not.toBeInTheDocument();
    }
  );

  it('opens a new task with the actual Doris table chosen from table management', async () => {
    const target: api.IntegrationConnection = {
      ...targetConnection,
      id: 'designer-doris',
      displayName: 'Designer Doris',
      databaseType: 'Doris',
      database: 'ods_validation',
      schemas: ['ods_validation'],
      supportedModes: ['FULL', 'SQL_T1'],
      dorisHttpPort: 8030,
    };
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      target,
    ]);
    mockApi.getIntegrationTableOptions.mockResolvedValue([
      {
        ...table,
        schema: 'ods_validation',
        name: 'designed_visits',
        columns: [],
      },
    ]);
    await act(async () => {
      render(
        <MemoryRouter
          initialEntries={[
            {
              pathname: ROUTES.HOSPITAL_INTEGRATION_TASKS,
              state: {
                dorisTarget: {
                  connectionId: target.id,
                  schema: 'ods_validation',
                  table: 'designed_visits',
                },
              },
            },
          ]}>
          <HospitalIntegrationWorkspace isAdmin />
        </MemoryRouter>
      );
    });

    const drawer = screen.getByRole('dialog');

    expect(
      within(drawer).getByRole('button', {
        name: /hospitalIntegration.targetConnection/,
      })
    ).toHaveTextContent('Designer Doris');
    expect(
      within(drawer).getByRole('button', {
        name: /hospitalIntegration.targetTable/,
      })
    ).toHaveTextContent('designed_visits');
    expect(mockApi.createIntegrationTask).not.toHaveBeenCalled();
  });

  it('ignores a target-table intent outside its registered database scope', async () => {
    await act(async () => {
      render(
        <MemoryRouter
          initialEntries={[
            {
              pathname: ROUTES.HOSPITAL_INTEGRATION_TASKS,
              state: {
                dorisTarget: {
                  connectionId: targetConnection.id,
                  schema: 'other_database',
                  table: 'visits',
                },
              },
            },
          ]}>
          <HospitalIntegrationWorkspace isAdmin />
        </MemoryRouter>
      );
    });

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not use tabs to switch between tasks and data sources', async () => {
    await renderWorkspace();

    expect(
      screen.queryByRole('tab', { name: 'hospitalIntegration.dataSources' })
    ).not.toBeInTheDocument();
  });

  it('explains ordinary employee access without requesting protected data', async () => {
    await renderWorkspace(false);

    expect(screen.getByRole('status')).toHaveTextContent(
      'hospitalIntegration.adminOnly'
    );
    expect(mockApi.getIntegrationTasks).not.toHaveBeenCalled();
    expect(mockApi.getIntegrationConnections).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('button', { name: 'hospitalIntegration.createTask' })
    ).not.toBeInTheDocument();
  });

  it('blocks invalid task submission before making API requests', async () => {
    await renderWorkspace();
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.invalidName'
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.tablesRequired'
    );
    expect(mockApi.validateIntegrationTask).not.toHaveBeenCalled();
    expect(mockApi.createIntegrationTask).not.toHaveBeenCalled();

    expectRevealedError(screen.getByRole('alert'));

    const revealCount = scrollIntoViewMock.mock.calls.length;
    await click(screen.getByRole('button', { name: 'label.save' }));

    expectRevealedError(screen.getByRole('alert'));

    expect(scrollIntoViewMock).toHaveBeenCalledTimes(revealCount + 1);
  });

  it('preserves edited fields and the original version after a 409 conflict', async () => {
    mockApi.updateIntegrationTask.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { errorCode: 'VERSION_CONFLICT' } },
    });
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    const displayName = screen.getByRole('textbox', {
      name: /label.display-name/,
    });
    await userEvent.clear(displayName);
    await userEvent.type(displayName, 'Keep my draft');
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.conflictHint'
    );

    expectRevealedError(screen.getByRole('alert'));

    expect(displayName).toHaveValue('Keep my draft');
    expect(screen.getByRole('textbox', { name: /label.name/ })).toHaveValue(
      task.name
    );
    expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.objectContaining({ displayName: 'Keep my draft' }),
      7
    );
    expect(mockApi.updateIntegrationTask.mock.calls[0][1]).not.toHaveProperty(
      'latestRun'
    );
    expect(mockApi.updateIntegrationTask.mock.calls[0][1]).not.toHaveProperty(
      'updatedBy'
    );
  });

  it('shows missing counters as unavailable and preserves an exact 64-bit job ID', async () => {
    mockApi.getIntegrationTask.mockResolvedValue(withRun(finished));
    await renderWorkspace();
    await openTask();

    expect(screen.getByTestId('integration-job-id')).toHaveTextContent(
      '9223372036854775807'
    );
    expect(screen.getByTestId('integration-source-count')).toHaveTextContent(
      'hospitalIntegration.unavailable'
    );
    expect(screen.getByTestId('integration-sink-count')).toHaveTextContent(
      'hospitalIntegration.unavailable'
    );
    expect(
      screen.getByText('hospitalIntegration.counterHint')
    ).toBeInTheDocument();
  });

  it.each(['DUPLICATE_TASK', 'DUPLICATE_ROUTE'])(
    'explains %s without suggesting a version merge and keeps the draft',
    async (errorCode) => {
      mockApi.updateIntegrationTask.mockRejectedValue({
        isAxiosError: true,
        response: { status: 409, data: { errorCode } },
      });
      await renderWorkspace();
      await openTask();
      await click(screen.getByRole('button', { name: 'label.edit' }));
      const name = screen.getByRole('textbox', { name: /label.name/ });
      await userEvent.clear(name);
      await userEvent.type(name, 'already_exists');
      await click(screen.getByRole('button', { name: 'label.save' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        `hospitalIntegration.errors.${errorCode}`
      );
      expect(
        screen.queryByText('hospitalIntegration.conflictHint')
      ).not.toBeInTheDocument();
      expect(name).toHaveValue('already_exists');
    }
  );

  it('keeps a real zero distinct from an unavailable count', async () => {
    mockApi.getIntegrationTask.mockResolvedValue(
      withRun({ ...finished, sourceReceivedCount: 0 })
    );
    await renderWorkspace();
    await openTask();

    expect(screen.getByTestId('integration-source-count')).toHaveTextContent(
      /^0$/
    );
    expect(screen.getByTestId('integration-sink-count')).toHaveTextContent(
      'hospitalIntegration.unavailable'
    );
  });

  it('blocks duplicate active runs and sends the CDC savepoint stop request', async () => {
    const running = withRun({
      ...finished,
      status: 'RUNNING',
      finishedAt: undefined,
    });
    mockApi.getIntegrationTask.mockResolvedValue(running);
    mockApi.stopIntegrationTask.mockResolvedValue({
      ...running,
      latestRun: {
        ...running.latestRun!,
        status: 'SAVEPOINT_DONE',
        canResume: true,
      },
    });
    await renderWorkspace();
    await openTask();

    expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.resume' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.edit' })).toBeDisabled();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.stopWithSavepoint',
      })
    );
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.resume' })).toBeEnabled()
    );

    expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.edit' })).toBeDisabled();
    expect(
      screen.getByText('hospitalIntegration.pausedHint')
    ).toBeInTheDocument();

    expect(mockApi.stopIntegrationTask).toHaveBeenCalledWith(task.id, true);
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('keeps a timed-out submission blocked instead of submitting another run', async () => {
    mockApi.runIntegrationTask.mockRejectedValue({
      isAxiosError: true,
      response: undefined,
    });
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(screen.getByRole('button', { name: 'label.run' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled()
    );

    expect(screen.getByRole('button', { name: 'label.resume' })).toBeDisabled();
    expect(
      screen.getByRole('button', {
        name: 'hospitalIntegration.stopWithSavepoint',
      })
    ).toBeDisabled();

    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(mockApi.runIntegrationTask).toHaveBeenCalledTimes(1);
    expect(
      screen
        .getAllByRole('alert')
        .some((alert) => alert.textContent?.includes('SUBMISSION_UNKNOWN'))
    ).toBe(true);
  });

  it('allows a failed stop to be retried while blocking edits and duplicate runs', async () => {
    const stopFailed = withRun({
      ...finished,
      status: 'STOP_FAILED',
      finishedAt: undefined,
      errorCode: 'ENGINE_UNAVAILABLE',
    });
    mockApi.getIntegrationTask.mockResolvedValue(stopFailed);
    mockApi.stopIntegrationTask.mockResolvedValue(stopFailed);
    await renderWorkspace();
    await openTask();

    expect(
      within(screen.getByTestId('integration-task-detail')).getByText(
        'hospitalIntegration.runStatus.STOP_FAILED'
      )
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'label.run' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.edit' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.resume' })).toBeDisabled();
    expect(
      screen.queryByText('hospitalIntegration.errors.SUBMISSION_UNKNOWN')
    ).not.toBeInTheDocument();

    const stop = screen.getByRole('button', {
      name: 'hospitalIntegration.stopWithSavepoint',
    });

    expect(stop).toBeEnabled();

    await click(stop);
    await click(screen.getByRole('button', { name: 'label.run' }));

    expect(mockApi.stopIntegrationTask).toHaveBeenCalledWith(task.id, true);
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('reconciles a submitted list task without opening detail and stops polling when finished', async () => {
    jest.useFakeTimers();
    mockApi.getIntegrationTasks
      .mockResolvedValueOnce([
        withRun({ ...finished, status: 'SUBMITTED', finishedAt: undefined }),
      ])
      .mockResolvedValue([withRun(finished)]);
    await renderWorkspace();

    expect(
      within(screen.getByRole('table')).getByText(
        'hospitalIntegration.runStatus.SUBMITTED'
      )
    ).toBeInTheDocument();

    await act(async () => {
      jest.advanceTimersByTime(5000);
    });

    expect(
      within(screen.getByRole('table')).getByText(
        'hospitalIntegration.runStatus.FINISHED'
      )
    ).toBeInTheDocument();
    expect(mockApi.getIntegrationTasks).toHaveBeenCalledTimes(2);
    expect(mockApi.getIntegrationTask).not.toHaveBeenCalled();

    await act(async () => {
      jest.advanceTimersByTime(10000);
    });

    expect(mockApi.getIntegrationTasks).toHaveBeenCalledTimes(2);
    expect(mockApi.getIntegrationTask).not.toHaveBeenCalled();
  });

  it('requires backend confirmation of recoverability even after a CDC stop', async () => {
    mockApi.getIntegrationTask.mockResolvedValue(
      withRun({
        ...finished,
        status: 'SAVEPOINT_DONE',
        savepointRequested: true,
        canResume: false,
      })
    );
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalCdc.taskPreflight' })
    );
    await click(screen.getByRole('button', { name: 'label.close' }));

    expect(screen.getByRole('button', { name: 'label.resume' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.run' })).toBeEnabled();
  });

  it('retries catalog registration without changing FINISHED transfer status', async () => {
    const catalogFailure: api.IntegrationTask = {
      ...withRun(finished),
      catalog: {
        status: 'FAILED',
        errorCode: 'CATALOG_SYNC_FAILED',
        errorMessage: 'credential=must-not-render',
      },
    };
    mockApi.getIntegrationTask.mockResolvedValue(catalogFailure);
    mockApi.syncIntegrationCatalog.mockResolvedValue({
      ...catalogFailure,
      catalog: {
        status: 'SYNCED',
        targetFqn: 'synthetic.ods.public.encounter',
      },
    });
    await renderWorkspace();
    await openTask();

    expect(
      within(screen.getByTestId('integration-task-detail')).getByText(
        'hospitalIntegration.runStatus.FINISHED'
      )
    ).toBeInTheDocument();
    expect(
      screen.queryByText('credential=must-not-render')
    ).not.toBeInTheDocument();

    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.retryCatalog' })
    );

    expect(
      await screen.findByRole('link', {
        name: 'hospitalIntegration.targetAsset',
      })
    ).toHaveAttribute('href', '/table/synthetic.ods.public.encounter');
    expect(
      within(screen.getByTestId('integration-task-detail')).getByText(
        'hospitalIntegration.runStatus.FINISHED'
      )
    ).toBeInTheDocument();
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();
  });

  it('ignores an old task response after the operator switches tasks', async () => {
    const otherTask: api.IntegrationTask = {
      ...task,
      id: 'other',
      name: 'other',
      displayName: 'Other transfer',
    };
    let resolveOld: (value: api.IntegrationTask) => void = () => undefined;
    const oldRequest = new Promise<api.IntegrationTask>((resolve) => {
      resolveOld = resolve;
    });
    mockApi.getIntegrationTasks.mockResolvedValue([task, otherTask]);
    mockApi.getIntegrationTask.mockImplementation((id) =>
      id === task.id ? oldRequest : Promise.resolve(otherTask)
    );
    await renderWorkspace();
    await click(screen.getByRole('button', { name: task.displayName }));
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.backToTasks' })
    );
    await click(screen.getByRole('button', { name: otherTask.displayName }));
    await act(async () => {
      resolveOld(withRun(finished));
    });

    expect(
      screen.getByRole('heading', { name: otherTask.displayName })
    ).toBeInTheDocument();
    expect(screen.queryByTestId('integration-job-id')).not.toBeInTheDocument();
  });

  const editBusinessConnection = async () => {
    await click(
      within(
        screen.getByTestId('integration-connection-row-business-source')
      ).getByRole('button', { name: 'label.edit' })
    );
  };

  it('keeps the task list mounted behind a native right drawer', async () => {
    await renderWorkspace();
    const list = screen.getByTestId('integration-tasks-list');
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );

    expect(document.body).toContainElement(list);
    expect(screen.getByTestId('integration-task-drawer')).toBeInTheDocument();
    expect(screen.getByTestId('form-drawer-footer')).toContainElement(
      screen.getByRole('button', { name: 'label.save' })
    );
  });

  it('has one create action on the sources page and keeps environment seeds immutable', async () => {
    await renderSources();

    expect(
      screen.queryByRole('button', { name: 'hospitalIntegration.createTask' })
    ).not.toBeInTheDocument();
    expect(
      screen.getAllByRole('button', {
        name: 'hospitalIntegration.createConnection',
      })
    ).toHaveLength(1);

    const seed = within(
      screen.getByTestId('integration-connection-row-synthetic-source')
    );

    expect(
      seed.queryByRole('button', { name: 'label.edit' })
    ).not.toBeInTheDocument();
    expect(
      seed.queryByRole('button', { name: 'label.delete' })
    ).not.toBeInTheDocument();
    expect(
      seed.getByText('hospitalIntegration.environmentManaged')
    ).toBeInTheDocument();

    const maintained = within(
      screen.getByTestId('integration-connection-row-business-source')
    );

    expect(
      maintained.getByRole('button', { name: 'label.edit' })
    ).toBeEnabled();
    expect(
      maintained.getByText('hospitalIntegration.connectionUntested')
    ).toBeInTheDocument();
  });

  it('creates a safe data-source draft and does not imply that saving tests connectivity', async () => {
    const created = {
      ...businessConnection,
      id: 'new-source',
      name: 'new_source',
      displayName: 'New synthetic source',
    };
    mockApi.createIntegrationConnection.mockResolvedValue(created);
    await renderSources();
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.createConnection',
      })
    );
    const drawer = screen.getByTestId('integration-connection-drawer');

    expect(drawer).toBeInTheDocument();
    expect(document.getElementById('connection-databaseVersion')).toHaveValue(
      ''
    );
    expect(document.getElementById('connection-port')).toHaveValue(5432);
    expect(document.getElementById('connection-tlsMode')).toHaveTextContent(
      'hospitalIntegration.tlsVerify'
    );

    for (const [id, value] of Object.entries({
      name: 'new_source',
      displayName: created.displayName,
      databaseVersion: '17.2',
      host: 'example.invalid',
      database: 'synthetic_db',
      username: 'reader',
      password: 'synthetic-test-password',
    })) {
      await userEvent.type(document.getElementById('connection-' + id)!, value);
    }
    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-connection-drawer')
      ).not.toBeInTheDocument()
    );

    expect(mockApi.createIntegrationConnection).toHaveBeenCalledWith(
      expect.objectContaining({
        tlsMode: 'VERIFY',
        databaseVersion: '17.2',
        schemas: ['public'],
      })
    );
    expect(mockApi.testIntegrationConnection).not.toHaveBeenCalled();
    expect(
      within(
        screen.getByTestId('integration-connection-row-new-source')
      ).getByText('hospitalIntegration.connectionUntested')
    ).toBeInTheDocument();
  });

  it('keeps failed data-source edits and omits an unchanged password', async () => {
    mockApi.updateIntegrationConnection.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { errorCode: 'INVALID_CONFIGURATION' } },
    });
    await renderSources();
    await editBusinessConnection();
    const name = document.getElementById('connection-displayName')!;

    expect(document.getElementById('connection-password')).toHaveValue('');

    await userEvent.clear(name);
    await userEvent.type(name, 'Keep this source draft');
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.INVALID_CONFIGURATION'
    );

    expectRevealedError(screen.getByRole('alert'));

    expect(name).toHaveValue('Keep this source draft');
    expect(mockApi.updateIntegrationConnection).toHaveBeenCalledWith(
      businessConnection.id,
      expect.not.objectContaining({ password: expect.anything() }),
      3
    );

    const revealCount = scrollIntoViewMock.mock.calls.length;
    await click(name);
    await userEvent.type(name, ' after error');

    expect(name).toHaveFocus();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(revealCount);
  });

  it('keeps a version-conflict draft until an explicit current-version reload', async () => {
    mockApi.updateIntegrationConnection.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { errorCode: 'VERSION_CONFLICT' } },
    });
    mockApi.getIntegrationConnection.mockResolvedValue({
      ...businessConnection,
      version: 4,
      displayName: 'Current stored version',
    });
    await renderSources();
    await editBusinessConnection();
    const name = document.getElementById('connection-displayName')!;
    await userEvent.clear(name);
    await userEvent.type(name, 'Keep conflict draft');
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.connectionConflictHint'
    );
    expect(screen.getByRole('alert')).not.toHaveTextContent(
      'hospitalIntegration.errors.VERSION_CONFLICT'
    );

    expectRevealedError(screen.getByRole('alert'));

    expect(name).toHaveValue('Keep conflict draft');

    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(
      mockApi.updateIntegrationConnection.mock.calls.map((call) => call[2])
    ).toEqual([3, 3]);

    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.reloadCurrent' })
    );
    await waitFor(() => expect(name).toHaveValue('Current stored version'));

    expect(document.getElementById('connection-password')).toHaveValue('');
  });

  it('does not offer version reload for a duplicate-name conflict', async () => {
    mockApi.updateIntegrationConnection.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { errorCode: 'DUPLICATE_CONNECTION' } },
    });
    await renderSources();
    await editBusinessConnection();
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.DUPLICATE_CONNECTION'
    );
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.reloadCurrent',
      })
    ).not.toBeInTheDocument();
  });

  it('blocks closing and duplicate data-source saves while the request is pending', async () => {
    let resolveSave: (value: api.IntegrationConnection) => void = () =>
      undefined;
    mockApi.updateIntegrationConnection.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSave = resolve;
        })
    );
    await renderSources();
    await editBusinessConnection();
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(screen.getByRole('button', { name: 'label.cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.close' })).toBeDisabled();

    await userEvent.keyboard('{Escape}');
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationConnection).toHaveBeenCalledTimes(1);
    expect(
      screen.getByTestId('integration-connection-drawer')
    ).toBeInTheDocument();

    await act(async () => {
      resolveSave({ ...businessConnection, version: 4 });
    });
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-connection-drawer')
      ).not.toBeInTheDocument()
    );
  });

  it('clears a typed password on cancel and reopening', async () => {
    await renderSources();
    await editBusinessConnection();
    await userEvent.type(
      document.getElementById('connection-password')!,
      'synthetic-transient-password'
    );
    await click(screen.getByRole('button', { name: 'label.cancel' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      'message.unsaved-form-data'
    );

    expectRevealedError(screen.getByRole('alert'));
    // This removes the focused button. Let user-event commit its click before
    // the native FocusScope frame restores focus inside the drawer.
    await userEvent.click(
      screen.getByRole('button', { name: 'label.continue-editing' })
    );

    expect(document.getElementById('connection-password')).toHaveValue(
      'synthetic-transient-password'
    );

    await waitFor(() =>
      expect(
        screen.getByTestId('integration-connection-drawer')
      ).toContainElement(document.activeElement as HTMLElement)
    );
    await act(async () => {
      await userEvent.keyboard('{Escape}');
    });
    await click(await screen.findByRole('button', { name: 'label.discard' }));
    await click(
      within(
        screen.getByTestId('integration-connection-row-business-source')
      ).getByRole('button', { name: 'label.edit' })
    );

    expect(document.getElementById('connection-password')).toHaveValue('');
    expect(
      screen.queryByText('synthetic-transient-password')
    ).not.toBeInTheDocument();
  });

  it('requires inline deletion confirmation and retains an in-use source after rejection', async () => {
    mockApi.deleteIntegrationConnection
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: { status: 409, data: { errorCode: 'CONNECTION_IN_USE' } },
      })
      .mockResolvedValueOnce(undefined);
    await renderSources();
    const row = within(
      screen.getByTestId('integration-connection-row-business-source')
    );
    await click(row.getByRole('button', { name: 'label.delete' }));

    expect(mockApi.deleteIntegrationConnection).not.toHaveBeenCalled();

    await click(
      row.getByRole('button', {
        name: 'hospitalIntegration.confirmDeleteConnection',
      })
    );

    expect(await row.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.CONNECTION_IN_USE'
    );
    expect(
      screen.getByTestId('integration-connection-row-business-source')
    ).toBeInTheDocument();

    await click(
      row.getByRole('button', {
        name: 'hospitalIntegration.confirmDeleteConnection',
      })
    );
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-connection-row-business-source')
      ).not.toBeInTheDocument()
    );

    expect(mockApi.deleteIntegrationConnection).toHaveBeenLastCalledWith(
      businessConnection.id,
      3
    );
  });

  it('shows explicit pending and failed connection tests without rendering server secrets', async () => {
    let resolveTest: (value: {
      connected: boolean;
      errorCode?: string;
    }) => void = () => undefined;
    mockApi.testIntegrationConnection.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveTest = resolve;
        })
    );
    await renderSources();
    const row = within(
      screen.getByTestId('integration-connection-row-business-source')
    );
    await click(
      row.getByRole('button', { name: 'hospitalIntegration.testConnection' })
    );

    expect(row.getByRole('status')).toHaveTextContent(
      'hospitalIntegration.connectionTesting'
    );

    await act(async () => {
      resolveTest({ connected: false, errorCode: 'CONNECTION_UNAVAILABLE' });
    });

    expect(row.getByRole('status')).toHaveTextContent(
      'hospitalIntegration.connectionFailed'
    );
    expect(row.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.CONNECTION_UNAVAILABLE'
    );
  });

  it('filters task connections by role and enabled state and uses selected identifiers for discovery', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      targetConnection,
      businessConnection,
      {
        ...businessConnection,
        id: 'disabled',
        displayName: 'Disabled source',
        enabled: false,
      },
    ]);
    await renderWorkspace();
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );
    const trigger = document.getElementById('integration-source-connection')!;
    await click(trigger);

    expect(
      screen.queryByRole('option', { name: targetConnection.displayName })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('option', { name: 'Disabled source' })
    ).not.toBeInTheDocument();

    await click(
      screen.getByRole('option', { name: businessConnection.displayName })
    );
    await waitFor(() =>
      expect(mockApi.getIntegrationTableOptions).toHaveBeenCalledWith(
        businessConnection.id,
        expect.any(AbortSignal)
      )
    );
    await choose('integration-source-table', 'public.' + table.name);
    await waitFor(() =>
      expect(mockApi.getIntegrationTable).toHaveBeenCalledWith(
        businessConnection.id,
        'public',
        table.name,
        expect.any(AbortSignal)
      )
    );

    expect(mockApi.getIntegrationTables).not.toHaveBeenCalled();
  });

  it('clears source selections, preserves the target and removes unsupported CDC on source change', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      targetConnection,
      {
        ...businessConnection,
        databaseType: 'Oracle',
        supportedModes: ['FULL'],
      },
    ]);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await choose(
      'integration-source-connection',
      businessConnection.displayName
    );

    expect(
      document.getElementById('integration-source-table')
    ).not.toHaveTextContent(table.name);
    expect(
      document.getElementById('integration-target-table')
    ).toHaveTextContent(table.name);
    expect(document.getElementById('integration-mode')).toHaveTextContent(
      'hospitalIntegration.full'
    );

    await click(document.getElementById('integration-mode')!);

    expect(
      screen.queryByRole('option', { name: 'hospitalIntegration.cdc' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('id · label.primary-key')
    ).not.toBeInTheDocument();
  });

  it('ignores late names and column responses from a previously selected source', async () => {
    let resolveNames: (value: api.IntegrationTable[]) => void = () => undefined;
    let resolveColumns: (value: api.IntegrationTable) => void = () => undefined;
    mockApi.getIntegrationTableOptions.mockImplementation((id) =>
      id === sourceConnection.id
        ? new Promise((resolve) => {
            resolveNames = resolve;
          })
        : Promise.resolve([{ ...table, name: 'current_table', columns: [] }])
    );
    mockApi.getIntegrationTable.mockImplementation((id, schema, name) =>
      id === sourceConnection.id
        ? new Promise((resolve) => {
            resolveColumns = resolve;
          })
        : Promise.resolve({ ...table, schema, name })
    );
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await choose(
      'integration-source-connection',
      businessConnection.displayName
    );
    await act(async () => {
      resolveNames([{ ...table, name: 'stale_table', columns: [] }]);
      resolveColumns({
        ...table,
        columns: [{ ...table.columns[0], name: 'stale_column' }],
      });
    });
    await choose('integration-source-table', 'public.current_table');

    expect(screen.queryByText('stale_table')).not.toBeInTheDocument();
    expect(screen.queryByText('stale_column')).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-source-table')
    ).toHaveTextContent('current_table');
  });

  it('uses family defaults, requires an actual version and binds MySQL scope to the database', async () => {
    await renderSources();
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.createConnection',
      })
    );
    await userEvent.type(
      document.getElementById('connection-databaseVersion')!,
      '17.2'
    );
    await choose(
      'connection-databaseType',
      'hospitalIntegration.databaseTypes.Oracle'
    );

    expect(document.getElementById('connection-port')).toHaveValue(1521);
    expect(document.getElementById('connection-databaseVersion')).toHaveValue(
      ''
    );
    expect(
      document.getElementById('connection-oracleConnectionType')
    ).toHaveTextContent('hospitalIntegration.oracleServiceName');

    await choose(
      'connection-databaseType',
      'hospitalIntegration.databaseTypes.Mysql'
    );

    expect(document.getElementById('connection-port')).toHaveValue(3306);
    expect(
      document.getElementById('connection-oracleConnectionType')
    ).not.toBeInTheDocument();

    await userEvent.type(
      document.getElementById('connection-database')!,
      'synthetic_business'
    );

    expect(document.getElementById('connection-schemas')).toHaveValue(
      'synthetic_business'
    );
    expect(document.getElementById('connection-schemas')).toBeDisabled();
  });

  it('loads data sources independently of the engine and task polling', async () => {
    await renderSources();

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'hospitalNavigation.sources',
      })
    ).toBeInTheDocument();
    expect(mockApi.getIntegrationConnections).toHaveBeenCalledTimes(1);
    expect(mockApi.getIntegrationStatus).not.toHaveBeenCalled();
    expect(mockApi.getIntegrationTasks).not.toHaveBeenCalled();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });

  it('keeps source access restricted without requesting protected data', async () => {
    await renderSources(false);

    expect(screen.getByRole('status')).toHaveTextContent(
      'hospitalIntegration.adminOnly'
    );
    expect(mockApi.getIntegrationConnections).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.createConnection',
      })
    ).not.toBeInTheDocument();
  });

  it('filters sources by URL and preserves the complete collection after editing', async () => {
    mockApi.updateIntegrationConnection.mockResolvedValue({
      ...businessConnection,
      displayName: 'Changed business source',
    });
    await renderSources(true, '?q=business&page=1');

    expect(
      screen.queryByTestId('integration-connection-row-synthetic-source')
    ).not.toBeInTheDocument();

    await editBusinessConnection();
    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-connection-drawer')
      ).not.toBeInTheDocument()
    );
    await click(screen.getByRole('button', { name: 'label.reset' }));

    expect(
      screen.getByTestId('integration-connection-row-synthetic-source')
    ).toBeInTheDocument();
    expect(
      screen.getByTestId('integration-connection-row-business-source')
    ).toHaveTextContent('Changed business source');
    expect(screen.getByTestId('integration-location')).toHaveTextContent(
      ROUTES.HOSPITAL_INTEGRATION_SOURCES
    );
  });

  it('paginates the loaded source collection without changing server records', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue(
      Array.from({ length: 26 }, (_, index) => ({
        ...businessConnection,
        id: 'page-source-' + index,
        name: 'source_' + index,
        displayName: 'Source ' + index,
      }))
    );
    await renderSources();

    expect(screen.getAllByTestId(/^integration-connection-row-/)).toHaveLength(
      25
    );

    await click(screen.getByRole('button', { name: 'label.next' }));

    expect(screen.getAllByTestId(/^integration-connection-row-/)).toHaveLength(
      1
    );
    expect(
      screen.getByTestId('integration-connection-row-page-source-25')
    ).toBeInTheDocument();
    expect(screen.getByTestId('integration-location')).toHaveTextContent(
      '?page=2'
    );
    expect(mockApi.getIntegrationConnections).toHaveBeenCalledTimes(1);
  });

  it('opens task detail directly and restores task filters when returning', async () => {
    await renderWorkspace(
      true,
      ROUTES.HOSPITAL_INTEGRATION_TASKS +
        '/' +
        task.id +
        '?q=encounter&status=NOT_RUN'
    );

    expect(
      screen.getByRole('heading', { level: 1, name: task.displayName })
    ).toBeInTheDocument();
    expect(mockApi.getIntegrationTask).toHaveBeenCalledWith(
      task.id,
      expect.any(AbortSignal)
    );

    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.backToTasks' })
    );

    expect(screen.getByTestId('integration-location')).toHaveTextContent(
      ROUTES.HOSPITAL_INTEGRATION_TASKS + '?q=encounter&status=NOT_RUN'
    );
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'hospitalIntegration.tasks',
      })
    ).toBeInTheDocument();
  });

  it('shows a retryable error when a directly opened task is missing', async () => {
    mockApi.getIntegrationTask.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404, data: { code: 'TASK_NOT_FOUND' } },
    });
    await renderWorkspace(true, ROUTES.HOSPITAL_INTEGRATION_TASKS + '/missing');

    expect(screen.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.TASK_NOT_FOUND'
    );
    expect(
      screen.queryByTestId('integration-task-detail')
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'label.refresh' })).toBeEnabled();
  });

  it('reveals data-source client validation and keeps the unsaved draft', async () => {
    await renderSources();
    await editBusinessConnection();
    const version = document.getElementById('connection-databaseVersion')!;
    await userEvent.clear(version);
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      'hospitalIntegration.databaseVersionRequired'
    );

    expectRevealedError(screen.getByRole('alert'));

    expect(version).toHaveValue('');
    expect(mockApi.updateIntegrationConnection).not.toHaveBeenCalled();
  });

  it('reveals rejected server validation without saving the task draft', async () => {
    mockApi.validateIntegrationTask.mockResolvedValue({
      valid: false,
      errors: [
        {
          field: 'fieldMappings',
          code: 'INVALID_CONFIGURATION',
          message: 'synthetic validation failure',
        },
      ],
    });
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'label.validate' })
      ).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'label.validate' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.serverValidationFailed'
    );

    expectRevealedError(screen.getByRole('alert'));

    expect(document.getElementById('integration-task-name')).toHaveValue(
      task.name
    );
    expect(mockApi.updateIntegrationTask).not.toHaveBeenCalled();
  });

  it('keeps task detail mounted and blocks duplicate task saves and dismissal while pending', async () => {
    let resolveSave: (value: api.IntegrationTask) => void = () => undefined;
    mockApi.updateIntegrationTask.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSave = resolve;
        })
    );
    await renderWorkspace();
    await openTask();
    const detail = screen.getByTestId('integration-task-detail');
    await click(screen.getByRole('button', { name: 'label.edit' }));

    expect(document.body).toContainElement(detail);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(screen.getByRole('button', { name: 'label.cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'label.close' })).toBeDisabled();

    await userEvent.keyboard('{Escape}');
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationTask).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveSave({ ...task, version: 8 });
    });
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-task-drawer')
      ).not.toBeInTheDocument()
    );

    expect(screen.getByTestId('integration-task-detail')).toBe(detail);
  });

  it('confirms unsaved task edits within the right drawer and preserves edits when continuing', async () => {
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    const name = document.getElementById('integration-task-displayName')!;
    await userEvent.type(name, ' changed');
    await click(screen.getByRole('button', { name: 'label.cancel' }));
    const drawer = screen.getByTestId('integration-task-drawer');

    expect(within(drawer).getByRole('alert')).toHaveTextContent(
      'message.unsaved-form-data'
    );

    expectRevealedError(within(drawer).getByRole('alert'));
    await click(
      within(drawer).getByRole('button', { name: 'label.continue-editing' })
    );

    expect(name).toHaveValue(task.displayName + ' changed');

    await click(screen.getByRole('button', { name: 'label.close' }));
    await click(within(drawer).getByRole('button', { name: 'label.discard' }));
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-task-drawer')
      ).not.toBeInTheDocument()
    );

    expect(screen.getByTestId('integration-task-detail')).toBeInTheDocument();
    expect(mockApi.updateIntegrationTask).not.toHaveBeenCalled();

    await click(screen.getByRole('button', { name: 'label.edit' }));

    expect(document.getElementById('integration-task-displayName')).toHaveValue(
      task.displayName
    );

    await click(screen.getByRole('button', { name: 'label.cancel' }));
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-task-drawer')
      ).not.toBeInTheDocument()
    );
  });

  it('shows the latest run timestamp independently of configuration updates', async () => {
    mockApi.getIntegrationTasks.mockResolvedValue([
      { ...withRun(finished), updatedAt: finished.submittedAt + 3600000 },
      { ...task, id: 'never-run', name: 'never_run', displayName: 'Never run' },
    ]);
    await renderWorkspace();
    const table = screen.getByRole('table');

    expect(
      within(table).getByRole('columnheader', {
        name: 'hospitalIntegration.latestRun',
      })
    ).toBeInTheDocument();

    const row = within(table)
      .getByRole('button', { name: task.displayName })
      .closest('tr')!;

    expect(row).toHaveTextContent(
      new Date(finished.submittedAt).toLocaleString('en-US')
    );

    const neverRun = within(table)
      .getByRole('button', { name: 'Never run' })
      .closest('tr')!;

    expect(
      within(neverRun).getAllByText('hospitalIntegration.notRun')
    ).toHaveLength(2);
  });

  it('preserves task page contents after opening and returning from a later page', async () => {
    const records = Array.from({ length: 50 }, (_, index) => ({
      ...task,
      id: 'task-' + (index + 1),
      name: 'task_' + (index + 1),
      displayName: 'Task ' + (index + 1),
    }));
    mockApi.getIntegrationTasks.mockResolvedValue(records);
    mockApi.getIntegrationTask.mockImplementation(
      async (id) => records.find((item) => item.id === id)!
    );
    await renderWorkspace(true, ROUTES.HOSPITAL_INTEGRATION_TASKS + '?page=2');
    await click(screen.getByRole('button', { name: 'Task 30' }));
    await screen.findByRole('heading', { level: 1, name: 'Task 30' });
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.backToTasks' })
    );

    expect(screen.getByTestId('integration-location')).toHaveTextContent(
      '?page=2'
    );

    const table = screen.getByRole('table');

    expect(
      within(table).getByRole('button', { name: 'Task 30' })
    ).toBeInTheDocument();
    expect(
      within(table).queryByRole('button', { name: 'Task 25' })
    ).not.toBeInTheDocument();
  });

  it('blocks history navigation until unsaved task changes are explicitly discarded', async () => {
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await userEvent.type(
      document.getElementById('integration-task-displayName')!,
      ' dirty'
    );
    await click(screen.getByTestId('integration-history-back'));
    const drawer = screen.getByTestId('integration-task-drawer');

    expect(within(drawer).getByRole('alert')).toHaveTextContent(
      'message.unsaved-form-data'
    );
    expect(screen.getByTestId('integration-location')).toHaveTextContent(
      '/tasks/' + task.id
    );

    await click(
      within(drawer).getByRole('button', { name: 'label.continue-editing' })
    );

    expect(document.getElementById('integration-task-displayName')).toHaveValue(
      task.displayName + ' dirty'
    );

    await click(screen.getByTestId('integration-history-back'));
    await click(within(drawer).getByRole('button', { name: 'label.discard' }));
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-task-drawer')
      ).not.toBeInTheDocument()
    );

    expect(screen.getByTestId('integration-location').textContent).toBe(
      ROUTES.HOSPITAL_INTEGRATION_TASKS
    );

    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );
    await userEvent.type(
      document.getElementById('integration-task-displayName')!,
      'Unsaved new task'
    );
    await click(screen.getByTestId('integration-history-forward'));

    expect(
      within(screen.getByTestId('integration-task-drawer')).getByRole('alert')
    ).toHaveTextContent('message.unsaved-form-data');
    expect(screen.getByTestId('integration-location').textContent).toBe(
      ROUTES.HOSPITAL_INTEGRATION_TASKS
    );
  });

  it('allows successful task creation to navigate to its saved detail while the editor is guarded', async () => {
    const saved = {
      ...task,
      id: 'new-task',
      name: 'created_task',
      displayName: 'Created transfer',
      mode: 'FULL' as const,
    };
    mockApi.createIntegrationTask.mockResolvedValue(saved);
    mockApi.getIntegrationTask.mockResolvedValue(saved);
    await renderWorkspace();
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );
    await userEvent.type(
      document.getElementById('integration-task-name')!,
      'created_task'
    );
    await userEvent.type(
      document.getElementById('integration-task-displayName')!,
      'Created transfer'
    );
    await choose('integration-source-table', 'public.' + table.name);
    await choose('integration-target-table', 'ods.' + table.name);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'label.save' })).toBeEnabled()
    );
    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(mockApi.createIntegrationTask).toHaveBeenCalledTimes(1)
    );
    await waitFor(() =>
      expect(screen.getByTestId('integration-location')).toHaveTextContent(
        '/tasks/new-task'
      )
    );

    expect(
      screen.queryByTestId('integration-task-drawer')
    ).not.toBeInTheDocument();
  });

  it('disables source saving while history discard confirmation is open', async () => {
    await renderSources();
    await editBusinessConnection();
    await userEvent.type(
      document.getElementById('connection-password')!,
      'synthetic-new-password'
    );
    await click(screen.getByTestId('integration-history-back'));

    expect(screen.getByRole('alert')).toHaveTextContent(
      'message.unsaved-form-data'
    );
    expect(screen.getByRole('button', { name: 'label.save' })).toBeDisabled();

    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(mockApi.updateIntegrationConnection).not.toHaveBeenCalled();
  });

  it('waits for the original browser history entry before confirming rapid traversal', async () => {
    const originalURL = window.location.href;
    const originalState: unknown = window.history.state;
    window.history.replaceState({ idx: 3 }, '', originalURL);
    const go = jest
      .spyOn(window.history, 'go')
      .mockImplementation(() => undefined);
    try {
      await renderWorkspace();
      await openTask();
      await click(screen.getByRole('button', { name: 'label.edit' }));
      await userEvent.type(
        document.getElementById('integration-task-displayName')!,
        ' retained'
      );
      const pop = async (idx: number) =>
        act(async () => {
          window.dispatchEvent(
            new PopStateEvent('popstate', { state: { idx } })
          );
        });
      await pop(2);

      expect(go).toHaveBeenLastCalledWith(1);
      expect(
        screen.queryByRole('button', { name: 'label.continue-editing' })
      ).not.toBeInTheDocument();

      await pop(1);

      expect(go).toHaveBeenLastCalledWith(2);
      expect(
        screen.queryByRole('button', { name: 'label.continue-editing' })
      ).not.toBeInTheDocument();

      await pop(3);

      expect(screen.getByRole('alert')).toHaveTextContent(
        'message.unsaved-form-data'
      );

      await click(
        screen.getByRole('button', { name: 'label.continue-editing' })
      );

      expect(
        document.getElementById('integration-task-displayName')
      ).toHaveValue(task.displayName + ' retained');

      await pop(3);

      expect(
        screen.queryByRole('button', { name: 'label.continue-editing' })
      ).not.toBeInTheDocument();
    } finally {
      go.mockRestore();
      window.history.replaceState(originalState, '', originalURL);
    }
  });

  it('uses Doris SQL and HTTP defaults and clears family settings when switching types', async () => {
    await renderSources();
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.createConnection',
      })
    );
    await choose(
      'connection-databaseType',
      'hospitalIntegration.databaseTypes.Doris'
    );

    expect(document.getElementById('connection-port')).toHaveValue(9030);
    expect(document.getElementById('connection-dorisHttpPort')).toHaveValue(
      8030
    );
    expect(
      document.getElementById('connection-databaseVersion')
    ).toHaveAttribute('placeholder', '4.0.8');
    expect(
      screen.getByText('hospitalIntegration.dorisTargetOnly')
    ).toBeInTheDocument();

    await choose('connection-role', 'label.target');
    await userEvent.type(
      document.getElementById('connection-database')!,
      'hospital_ods'
    );

    expect(document.getElementById('connection-schemas')).toHaveValue(
      'hospital_ods'
    );
    expect(document.getElementById('connection-schemas')).toBeDisabled();

    await click(document.getElementById('connection-tlsMode')!);

    expect(
      screen.queryByRole('option', { name: 'hospitalIntegration.tlsVerify' })
    ).not.toBeInTheDocument();

    await userEvent.keyboard('{Escape}');
    await choose(
      'connection-databaseType',
      'hospitalIntegration.databaseTypes.Oracle'
    );

    expect(
      document.getElementById('connection-dorisHttpPort')
    ).not.toBeInTheDocument();
    expect(document.getElementById('connection-port')).toHaveValue(1521);
    expect(
      document.getElementById('connection-oracleConnectionType')
    ).toHaveTextContent('hospitalIntegration.oracleServiceName');
  });

  const dorisConnection: api.IntegrationConnection = {
    ...targetConnection,
    databaseType: 'Doris',
    databaseVersion: '3.1.3',
    database: 'hospital_ods',
    schemas: ['hospital_ods'],
    port: 9030,
    dorisHttpPort: 8030,
    tlsMode: 'DISABLED',
  };
  const dorisTable: api.IntegrationTable = {
    ...table,
    schema: 'hospital_ods',
    name: 'synthetic_encounter',
    columns: [
      { ...table.columns[0], name: 'encounter_id', dataType: 'BIGINT' },
      { ...table.columns[1], dataType: 'STRING' },
    ],
  };
  const dorisPreview: api.IntegrationTargetTablePreview = {
    targetConnectionId: targetConnection.id,
    previewHash: 'preview-hash',
    ddl: 'CREATE TABLE hospital_ods.synthetic_encounter (encounter_id BIGINT, department STRING)',
    table: dorisTable,
    fieldMappings: [
      { source: 'id', target: 'encounter_id' },
      { source: 'department', target: 'department' },
    ],
  };
  const sqlQuery =
    'SELECT id, department FROM public.synthetic_encounter WHERE updated_at >= :window_start AND updated_at < :window_end';
  const sqlTask: api.IntegrationTask = {
    ...task,
    name: 'synthetic_sql_t1',
    mode: 'SQL_T1',
    sourceTable: 'sql_result',
    sourceQuery: sqlQuery,
    targetSchema: dorisTable.schema,
    targetTable: dorisTable.name,
    schedule: { enabled: false, dailyTime: '02:00', timeZone: 'Asia/Shanghai' },
    scheduleState: {
      lastBusinessDate: '2026-11-09',
      pendingBusinessDate: '2026-11-10',
    },
    latestRun: {
      ...finished,
      status: 'FAILED',
      businessDate: '2026-11-10',
      windowStart: '2026-11-10 00:00:00',
      windowEnd: '2026-11-11 00:00:00',
      trigger: 'SCHEDULED',
    },
  };
  const sqlColumns = {
    ...table,
    name: 'sql_result',
    columns: table.columns.map((column) => ({ ...column, primaryKey: false })),
  };
  const configureSql = async (connections?: api.IntegrationConnection[]) => {
    mockApi.getIntegrationConnections.mockResolvedValue(
      connections ?? [
        sourceConnection,
        targetConnection,
        {
          ...dorisConnection,
          id: 'doris-target',
          displayName: 'Synthetic Doris ODS',
        },
      ]
    );
    mockApi.getIntegrationQuerySchema.mockResolvedValue(sqlColumns);
    await renderWorkspace();
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.createTask' })
    );
    await click(document.getElementById('integration-mode')!);
    await click(
      await screen.findByRole('option', { name: 'hospitalIntegration.sqlT1' })
    );
  };

  const historyState: NonNullable<api.IntegrationTask['initializationState']> =
    {
      id: 'initialization-fixture',
      status: 'FAILED',
      startDate: '2024-02-14',
      cutoffDate: '2024-04-03',
      nextDate: '2024-03-01',
      batchUnit: 'MONTH',
      completedBatches: 1,
      totalBatches: 3,
      pendingStartDate: '2024-03-01',
      pendingEndDate: '2024-04-01',
      pendingJobId: '9223372036854775807',
      startedAt: 1,
      lastError: 'CONNECTION_UNAVAILABLE',
    };
  const historyTask: api.IntegrationTask = {
    ...sqlTask,
    fieldMappings: dorisPreview.fieldMappings,
    initialization: {
      enabled: true,
      startDate: historyState.startDate,
      batchUnit: 'MONTH',
    },
    initializationState: historyState,
    scheduleState: {},
    latestRun: {
      ...sqlTask.latestRun!,
      trigger: 'INITIALIZATION',
      initializationId: historyState.id,
      batchIndex: 2,
    },
  };
  const configureSqlEdit = async (editable: api.IntegrationTask) => {
    mockApi.getIntegrationTasks.mockResolvedValue([editable]);
    mockApi.getIntegrationTask.mockResolvedValue(editable);
    mockApi.updateIntegrationTask.mockResolvedValue(editable);
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      dorisConnection,
    ]);
    mockApi.getIntegrationTableOptions.mockResolvedValue([
      { ...dorisTable, columns: [] },
    ]);
    mockApi.getIntegrationTable.mockResolvedValue(dorisTable);
    mockApi.getIntegrationQuerySchema.mockResolvedValue(sqlColumns);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
  };

  it('keeps history optional and enables daily scheduling when historical initialization is selected', async () => {
    await configureSql();
    const history = screen.getByRole('checkbox', {
      name: 'hospitalIntegration.initializeBeforeDaily',
    });

    expect(history).not.toBeChecked();
    expect(
      document.getElementById('integration-initialization-start')
    ).not.toBeInTheDocument();

    await click(history);

    expect(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.enableSchedule',
      })
    ).toBeChecked();
    expect(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.enableSchedule',
      })
    ).toBeDisabled();
    expect(
      screen.getByLabelText(/hospitalIntegration.initializationStartDate/)
    ).toHaveAttribute('type', 'date');
    expect(
      document.getElementById('integration-initialization-unit')
    ).toHaveTextContent('hospitalIntegration.initializationDay');

    fireEvent.change(
      screen.getByLabelText(/hospitalIntegration.initializationStartDate/),
      { target: { value: '2024-02-14' } }
    );
    await click(document.getElementById('integration-initialization-unit')!);
    await click(
      await screen.findByRole('option', {
        name: 'hospitalIntegration.initializationMonth',
      })
    );

    expect(
      screen.getByTestId('integration-initialization-preview')
    ).toHaveTextContent('2024-03-01');
    expect(
      screen.getByText('hospitalIntegration.initializationMonthlyHint')
    ).toBeInTheDocument();
  });

  it('saves initialization configuration and an enabled schedule without server progress', async () => {
    await configureSqlEdit({
      ...sqlTask,
      fieldMappings: dorisPreview.fieldMappings,
      scheduleState: {},
    });
    await click(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.initializeBeforeDaily',
      })
    );
    fireEvent.change(
      screen.getByLabelText(/hospitalIntegration.initializationStartDate/),
      { target: { value: '2024-02-14' } }
    );
    await click(document.getElementById('integration-initialization-unit')!);
    await click(
      await screen.findByRole('option', {
        name: 'hospitalIntegration.initializationMonth',
      })
    );
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(mockApi.updateIntegrationTask).toHaveBeenCalled()
    );
    const payload = mockApi.updateIntegrationTask.mock.calls[0][1];

    expect(payload).toMatchObject({
      initialization: {
        enabled: true,
        startDate: '2024-02-14',
        batchUnit: 'MONTH',
      },
      schedule: {
        enabled: true,
        dailyTime: '02:00',
        timeZone: 'Asia/Shanghai',
      },
    });
    expect(payload).not.toHaveProperty('initializationState');
    expect(payload).not.toHaveProperty('latestRun');
  });

  it.each(['label.validate', 'label.save'])(
    'retains the SQL repair draft when authoritative monthly validation rejects it: %s',
    async (action) => {
      await configureSqlEdit(historyTask);
      const sourceQuery =
        'SELECT id, department FROM public.synthetic_encounter WHERE day = :biz_date';
      const query = screen.getByRole('textbox', {
        name: /hospitalIntegration.sourceQuery/,
      });
      fireEvent.change(query, { target: { value: sourceQuery } });
      await click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.readQueryColumns',
        })
      );
      await screen.findByText('hospitalIntegration.queryColumnsLoaded');
      mockApi.validateIntegrationTask.mockRejectedValueOnce({
        isAxiosError: true,
        response: {
          status: 400,
          data: { errorCode: 'SQL_INITIALIZATION_PARAMETERS_REQUIRED' },
        },
      });
      await click(screen.getByRole('button', { name: action }));

      await waitFor(() =>
        expect(mockApi.validateIntegrationTask).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceQuery,
            initialization: historyTask.initialization,
          })
        )
      );
      const error = await screen.findByText(
        'hospitalIntegration.errors.SQL_INITIALIZATION_PARAMETERS_REQUIRED'
      );
      const alert = error.closest('[role="alert"]') as HTMLElement;
      expectRevealedError(alert);

      expect(query).toHaveValue(sourceQuery);
      expect(
        screen.getByLabelText(/hospitalIntegration.initializationStartDate/)
      ).toHaveValue(historyState.startDate);
      expect(
        document.getElementById('integration-initialization-unit')
      ).toHaveTextContent('hospitalIntegration.initializationMonth');
      expect(
        screen.getByRole('checkbox', {
          name: 'hospitalIntegration.enableSchedule',
        })
      ).not.toBeChecked();
      expect(mockApi.updateIntegrationTask).not.toHaveBeenCalled();
      expect(mockApi.createIntegrationTask).not.toHaveBeenCalled();
    }
  );

  it('shows batch progress and continues the failed window using CAS without source metadata or an available engine', async () => {
    mockApi.getIntegrationTasks.mockResolvedValue([historyTask]);
    mockApi.getIntegrationTask.mockResolvedValue(historyTask);
    mockApi.getIntegrationStatus.mockResolvedValue({
      enabled: true,
      reachable: false,
    });
    let resolveAction: (value: api.IntegrationTask) => void = () => undefined;
    mockApi.updateIntegrationInitialization.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveAction = resolve;
        })
    );
    await renderWorkspace();
    await openTask();

    expect(
      screen.getByTestId('integration-initialization-progress')
    ).toHaveTextContent('1 / 3');
    expect(
      screen.getByTestId('integration-initialization-next-window')
    ).toHaveTextContent('[2024-03-01, 2024-04-01)');
    expect(
      screen.getByText('hospitalIntegration.errors.CONNECTION_UNAVAILABLE')
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'hospitalIntegration.backfill' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.retryBusinessDate',
      })
    ).not.toBeInTheDocument();

    const continueButton = screen.getByRole('button', {
      name: 'hospitalIntegration.continueInitialization',
    });

    expect(continueButton).toBeEnabled();

    await act(async () => {
      fireEvent.click(continueButton);
      fireEvent.click(continueButton);
    });

    expect(mockApi.updateIntegrationInitialization).toHaveBeenCalledTimes(1);
    expect(mockApi.updateIntegrationInitialization).toHaveBeenCalledWith(
      historyTask.id,
      'CONTINUE',
      7
    );

    await act(async () =>
      resolveAction({
        ...historyTask,
        version: 8,
        schedule: { ...historyTask.schedule!, enabled: true },
        initializationState: {
          ...historyState,
          status: 'PENDING',
          lastError: undefined,
        },
      })
    );

    expect(
      screen.getByTestId('integration-initialization-progress')
    ).toHaveTextContent('1 / 3');
    expect(mockApi.getIntegrationQuerySchema).not.toHaveBeenCalled();
    expect(mockApi.getIntegrationTable).not.toHaveBeenCalled();
    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.pauseInitialization',
      })
    );

    expect(mockApi.updateIntegrationInitialization).toHaveBeenLastCalledWith(
      historyTask.id,
      'PAUSE',
      8
    );

    await act(async () => resolveAction({ ...historyTask, version: 9 }));
  });

  it('keeps failed progress after a pause race and refreshes the CAS version before continuing', async () => {
    mockApi.getIntegrationTasks.mockResolvedValue([historyTask]);
    mockApi.getIntegrationTask.mockResolvedValue(historyTask);
    mockApi.updateIntegrationInitialization.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 409, data: { errorCode: 'VERSION_CONFLICT' } },
    });
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.continueInitialization',
      })
    );

    expect(
      await screen.findByText('hospitalIntegration.errors.VERSION_CONFLICT')
    ).toBeInTheDocument();
    expect(
      screen.getByTestId('integration-initialization-progress')
    ).toHaveTextContent('1 / 3');

    mockApi.getIntegrationTask.mockResolvedValue({
      ...historyTask,
      version: 9,
    });
    await click(screen.getByRole('button', { name: 'label.refresh' }));
    mockApi.updateIntegrationInitialization.mockResolvedValue({
      ...historyTask,
      version: 10,
    });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.continueInitialization',
      })
    );

    expect(mockApi.updateIntegrationInitialization).toHaveBeenLastCalledWith(
      historyTask.id,
      'CONTINUE',
      9
    );
  });

  it('freezes initialized routing and dates while allowing SQL repair without resetting mappings or pause state', async () => {
    await configureSqlEdit(historyTask);

    expect(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.initializeBeforeDaily',
      })
    ).toBeDisabled();
    expect(
      screen.getByLabelText(/hospitalIntegration.initializationStartDate/)
    ).toBeDisabled();

    for (const id of [
      'integration-initialization-unit',
      'integration-source-connection',
      'integration-source-schema',
      'integration-target-connection',
      'integration-target-table',
      'integration-primary-key',
      'integration-mode',
    ]) {
      expect(document.getElementById(id)).toBeDisabled();
    }

    expect(
      screen.getByTestId('integration-initialization-preview')
    ).toHaveTextContent('2024-04-03');

    const query = screen.getByRole('textbox', {
      name: /hospitalIntegration.sourceQuery/,
    });

    expect(query).toBeEnabled();

    fireEvent.change(query, { target: { value: sqlQuery + ' AND id > 0' } });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');

    expect(
      document.getElementById('integration-primary-key')
    ).toHaveTextContent('id');

    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
        historyTask.id,
        expect.objectContaining({
          initialization: historyTask.initialization,
          schedule: historyTask.schedule,
          fieldMappings: historyTask.fieldMappings,
          primaryKey: 'id',
          sourceQuery: sqlQuery + ' AND id > 0',
        }),
        7
      )
    );
  });

  it('retains completed initialization and restores ordinary backfill without offering a historical run retry', async () => {
    const completedTask: api.IntegrationTask = {
      ...historyTask,
      initializationState: {
        ...historyState,
        status: 'COMPLETED',
        completedBatches: 3,
        nextDate: '2024-04-03',
      },
    };
    mockApi.getIntegrationTasks.mockResolvedValue([completedTask]);
    mockApi.getIntegrationTask.mockResolvedValue(completedTask);
    await renderWorkspace();
    await openTask();

    expect(
      screen.getByTestId('integration-initialization-progress')
    ).toHaveTextContent('3 / 3');
    expect(
      screen.getByRole('button', { name: 'hospitalIntegration.backfill' })
    ).toBeEnabled();
    expect(
      screen.getByRole('button', {
        name: 'hospitalIntegration.retryBusinessDate',
      })
    ).toBeDisabled();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.continueInitialization',
      })
    ).not.toBeInTheDocument();
  });

  it('requires resolution of a pending daily window before enabling initialization on an existing task', async () => {
    await configureSqlEdit({
      ...sqlTask,
      fieldMappings: dorisPreview.fieldMappings,
    });

    expect(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.initializeBeforeDaily',
      })
    ).toBeDisabled();
    expect(
      screen.getByText('hospitalIntegration.initializationPendingDailyHint')
    ).toBeInTheDocument();
  });

  it('allows daily scheduling to be disabled after historical initialization completes', async () => {
    await configureSqlEdit({
      ...historyTask,
      schedule: { ...historyTask.schedule!, enabled: true },
      initializationState: {
        ...historyState,
        status: 'COMPLETED',
        completedBatches: 3,
        nextDate: historyState.cutoffDate,
      },
      latestRun: { ...historyTask.latestRun!, status: 'FINISHED' },
    });
    const schedule = screen.getByRole('checkbox', {
      name: 'hospitalIntegration.enableSchedule',
    });

    expect(schedule).toBeEnabled();

    await click(schedule);
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(screen.getByRole('button', { name: 'label.save' }));
    await waitFor(() =>
      expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
        historyTask.id,
        expect.objectContaining({
          initialization: historyTask.initialization,
          schedule: { ...historyTask.schedule!, enabled: false },
        }),
        7
      )
    );
  });

  it('reads SQL result columns only on request, starts scheduling disabled, and offers result aliases as keys', async () => {
    await configureSql();
    const query = screen.getByRole('textbox', {
      name: /hospitalIntegration.sourceQuery/,
    });

    expect(query).toHaveAttribute('maxlength', '16384');
    expect(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.enableSchedule',
      })
    ).not.toBeChecked();
    expect(screen.getByLabelText(/hospitalIntegration.dailyTime/)).toHaveValue(
      '02:00'
    );
    expect(screen.getByLabelText(/label.timezone/)).toHaveValue(
      'Asia/Shanghai'
    );
    expect(screen.getByLabelText(/label.timezone/)).toHaveAttribute('readonly');

    fireEvent.change(query, { target: { value: sqlQuery } });

    expect(mockApi.getIntegrationQuerySchema).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.createTargetFromSource',
      })
    ).not.toBeInTheDocument();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );

    expect(mockApi.getIntegrationQuerySchema).toHaveBeenCalledWith(
      { connectionId: sourceConnection.id, schema: 'public', query: sqlQuery },
      expect.any(AbortSignal)
    );

    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(document.getElementById('integration-primary-key')!);

    expect(
      await screen.findByRole('option', { name: 'id' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: 'department' })
    ).toBeInTheDocument();

    await click(screen.getByRole('option', { name: 'id' }));

    expect(
      mockApi.getIntegrationTable.mock.calls.some(
        ([id, , name]) => id === sourceConnection.id && name === 'sql_result'
      )
    ).toBe(false);

    await click(document.getElementById('integration-target-connection')!);

    expect(
      screen.queryByRole('option', { name: targetConnection.displayName })
    ).not.toBeInTheDocument();

    await click(
      await screen.findByRole('option', { name: 'Synthetic Doris ODS' })
    );
    await screen.findByRole('button', {
      name: 'hospitalIntegration.createTargetFromSource',
    });

    fireEvent.change(query, { target: { value: sqlQuery + ' ' } });

    expect(
      screen.queryByText('hospitalIntegration.queryColumnsLoaded')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.createTargetFromSource',
      })
    ).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-primary-key')
    ).not.toHaveTextContent('id');
  });

  it('ignores SQL columns returned for an obsolete draft', async () => {
    await configureSql();
    let resolveColumns: (value: api.IntegrationTable) => void = () => undefined;
    mockApi.getIntegrationQuerySchema.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveColumns = resolve;
        })
    );
    const query = screen.getByRole('textbox', {
      name: /hospitalIntegration.sourceQuery/,
    });
    fireEvent.change(query, { target: { value: sqlQuery } });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    fireEvent.change(query, { target: { value: sqlQuery + ' AND id > 10' } });
    await act(async () => resolveColumns(sqlColumns));

    expect(
      screen.queryByText('hospitalIntegration.queryColumnsLoaded')
    ).not.toBeInTheDocument();
    expect(document.getElementById('integration-primary-key')).toBeDisabled();
    expect(query).toHaveValue(sqlQuery + ' AND id > 10');
  });

  it('requires fresh result columns when editing SQL and saves an explicitly enabled daily schedule', async () => {
    const editable = { ...sqlTask, fieldMappings: dorisPreview.fieldMappings };
    mockApi.getIntegrationTasks.mockResolvedValue([editable]);
    mockApi.getIntegrationTask.mockResolvedValue(editable);
    mockApi.updateIntegrationTask.mockResolvedValue(editable);
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      dorisConnection,
    ]);
    mockApi.getIntegrationTableOptions.mockResolvedValue([
      { ...dorisTable, columns: [] },
    ]);
    mockApi.getIntegrationTable.mockResolvedValue(dorisTable);
    mockApi.getIntegrationQuerySchema.mockResolvedValue(sqlColumns);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await click(screen.getByRole('button', { name: 'label.save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.queryColumnsRequired'
    );
    expect(mockApi.updateIntegrationTask).not.toHaveBeenCalled();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(
      screen.getByRole('checkbox', {
        name: 'hospitalIntegration.enableSchedule',
      })
    );
    fireEvent.change(screen.getByLabelText(/hospitalIntegration.dailyTime/), {
      target: { value: '03:15' },
    });
    await click(screen.getByRole('button', { name: 'label.save' }));

    await waitFor(() =>
      expect(mockApi.updateIntegrationTask).toHaveBeenCalledWith(
        editable.id,
        expect.objectContaining({
          mode: 'SQL_T1',
          sourceQuery: sqlQuery,
          sourceTable: 'sql_result',
          primaryKey: 'id',
          schedule: {
            enabled: true,
            dailyTime: '03:15',
            timeZone: 'Asia/Shanghai',
          },
        }),
        editable.version
      )
    );
  });

  it.each(['success', 'failure', 'changed columns'] as const)(
    'retains the exact Doris creation draft while unchanged SQL is refreshed: %s',
    async (outcome) => {
      const editable = {
        ...sqlTask,
        fieldMappings: dorisPreview.fieldMappings,
      };
      mockApi.getIntegrationTasks.mockResolvedValue([editable]);
      mockApi.getIntegrationTask.mockResolvedValue(editable);
      mockApi.getIntegrationConnections.mockResolvedValue([
        sourceConnection,
        dorisConnection,
      ]);
      mockApi.getIntegrationTableOptions.mockResolvedValue([
        { ...dorisTable, columns: [] },
      ]);
      mockApi.getIntegrationTable.mockResolvedValue(dorisTable);
      mockApi.getIntegrationQuerySchema.mockResolvedValue(sqlColumns);
      mockApi.previewIntegrationTargetTable.mockResolvedValue(dorisPreview);
      await renderWorkspace();
      await openTask();
      await click(screen.getByRole('button', { name: 'label.edit' }));
      await click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.readQueryColumns',
        })
      );
      await click(
        await screen.findByRole('button', {
          name: 'hospitalIntegration.createTargetFromSource',
        })
      );
      const tableName = document.getElementById(
        'integration-new-target-table'
      )!;
      const idColumn = document.getElementById('integration-new-column-0')!;
      const departmentColumn = document.getElementById(
        'integration-new-column-1'
      )!;
      fireEvent.change(tableName, { target: { value: 'sql_daily_custom' } });
      fireEvent.change(idColumn, { target: { value: 'custom_id' } });
      fireEvent.change(departmentColumn, {
        target: { value: 'custom_department' },
      });
      await click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.previewTargetTable',
        })
      );
      await screen.findByTestId('integration-target-ddl');
      let resolveColumns: (value: api.IntegrationTable) => void = () =>
        undefined;
      let rejectColumns: (reason: unknown) => void = () => undefined;
      mockApi.getIntegrationQuerySchema.mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            resolveColumns = resolve;
            rejectColumns = reject;
          })
      );
      await click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.readQueryColumns',
        })
      );

      expect(document.getElementById('integration-new-target-table')).toBe(
        tableName
      );
      expect(tableName).toHaveValue('sql_daily_custom');
      expect(idColumn).toHaveValue('custom_id');
      expect(departmentColumn).toHaveValue('custom_department');
      expect(screen.getByRole('button', { name: 'label.save' })).toBeDisabled();
      expect(
        screen.getByRole('button', { name: 'label.validate' })
      ).toBeDisabled();

      await act(async () => {
        if (outcome === 'failure') {
          rejectColumns({
            isAxiosError: true,
            response: {
              status: 503,
              data: { errorCode: 'QUERY_METADATA_UNAVAILABLE' },
            },
          });
        } else {
          resolveColumns(
            outcome === 'changed columns'
              ? {
                  ...sqlColumns,
                  columns: [
                    ...sqlColumns.columns,
                    {
                      name: 'updated_at',
                      dataType: 'timestamp',
                      nullable: true,
                      primaryKey: false,
                    },
                  ],
                }
              : sqlColumns
          );
        }
      });

      expect(document.getElementById('integration-new-target-table')).toBe(
        tableName
      );
      expect(tableName).toHaveValue('sql_daily_custom');
      expect(document.getElementById('integration-new-column-0')).toHaveValue(
        'custom_id'
      );
      expect(document.getElementById('integration-new-column-1')).toHaveValue(
        'custom_department'
      );

      if (outcome === 'failure') {
        expect(await screen.findByRole('alert')).toHaveTextContent(
          'hospitalIntegration.errors.QUERY_METADATA_UNAVAILABLE'
        );
        expect(
          screen.getByRole('button', { name: 'label.save' })
        ).toBeDisabled();
        expect(
          screen.getByRole('button', { name: 'label.validate' })
        ).toBeDisabled();

        await click(
          screen.getByRole('button', {
            name: 'hospitalIntegration.readQueryColumns',
          })
        );
        await waitFor(() =>
          expect(screen.queryByRole('alert')).not.toBeInTheDocument()
        );

        expect(document.getElementById('integration-new-target-table')).toBe(
          tableName
        );
        expect(idColumn).toHaveValue('custom_id');
      } else if (outcome === 'changed columns') {
        expect(
          screen.queryByTestId('integration-target-ddl')
        ).not.toBeInTheDocument();
        expect(document.getElementById('integration-new-column-2')).toHaveValue(
          'updated_at'
        );
      } else {
        expect(
          screen.getByTestId('integration-target-ddl')
        ).toBeInTheDocument();
      }
      await click(screen.getByRole('button', { name: 'label.cancel' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'message.unsaved-form-data'
      );
    }
  );

  it('invalidates SQL metadata after schema or source changes without reading automatically', async () => {
    await configureSql([
      { ...sourceConnection, schemas: ['public', 'audit'] },
      { ...businessConnection, schemas: ['clinic'] },
      { ...dorisConnection, id: 'doris-target' },
    ]);
    const query = screen.getByRole('textbox', {
      name: /hospitalIntegration.sourceQuery/,
    });
    fireEvent.change(query, { target: { value: sqlQuery } });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(document.getElementById('integration-source-schema')!);
    await click(await screen.findByRole('option', { name: 'audit' }));

    expect(
      screen.queryByText('hospitalIntegration.queryColumnsLoaded')
    ).not.toBeInTheDocument();
    expect(mockApi.getIntegrationQuerySchema).toHaveBeenCalledTimes(1);

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.readQueryColumns',
      })
    );
    await screen.findByText('hospitalIntegration.queryColumnsLoaded');
    await click(document.getElementById('integration-source-connection')!);
    await click(
      await screen.findByRole('option', {
        name: businessConnection.displayName,
      })
    );

    expect(
      screen.queryByText('hospitalIntegration.queryColumnsLoaded')
    ).not.toBeInTheDocument();
    expect(
      document.getElementById('integration-source-schema')
    ).toHaveTextContent('clinic');
    expect(mockApi.getIntegrationQuerySchema).toHaveBeenCalledTimes(2);
  });

  it('keeps SQL backfill in a right drawer and retries the exact failed business date', async () => {
    mockApi.getIntegrationTasks.mockResolvedValue([sqlTask]);
    mockApi.getIntegrationTask.mockResolvedValue(sqlTask);
    mockApi.runIntegrationTask.mockResolvedValue(sqlTask);
    await renderWorkspace();
    await openTask();

    expect(screen.getAllByText('2026-11-10').length).toBeGreaterThan(0);
    expect(
      screen.getByText('hospitalIntegration.mergeHint')
    ).toBeInTheDocument();

    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.backfill' })
    );
    const drawer = await screen.findByTestId('integration-backfill-drawer');

    expect(within(drawer).getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByTestId('integration-task-detail')).toBeInTheDocument();

    fireEvent.change(
      screen.getByLabelText(/hospitalIntegration.businessDate/),
      { target: { value: '2026-11-08' } }
    );
    await click(within(drawer).getByRole('button', { name: 'label.run' }));
    await waitFor(() =>
      expect(mockApi.runIntegrationTask).toHaveBeenCalledWith(
        sqlTask.id,
        false,
        { businessDate: '2026-11-08' }
      )
    );
    await waitFor(() =>
      expect(
        screen.queryByTestId('integration-backfill-drawer')
      ).not.toBeInTheDocument()
    );
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.retryBusinessDate',
      })
    );

    expect(mockApi.runIntegrationTask).toHaveBeenLastCalledWith(
      sqlTask.id,
      false,
      { businessDate: '2026-11-10', retry: true }
    );
  });

  it('retains a failed backfill draft and blocks an invalid calendar date', async () => {
    mockApi.getIntegrationTasks.mockResolvedValue([sqlTask]);
    mockApi.getIntegrationTask.mockResolvedValue(sqlTask);
    mockApi.runIntegrationTask.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { errorCode: 'BUSINESS_DATE_INVALID' } },
    });
    await renderWorkspace();
    await openTask();
    await click(
      screen.getByRole('button', { name: 'hospitalIntegration.backfill' })
    );
    const date = screen.getByLabelText(/hospitalIntegration.businessDate/);
    fireEvent.change(date, { target: { value: '2026-02-30' } });
    await click(
      within(screen.getByTestId('integration-backfill-drawer')).getByRole(
        'button',
        { name: 'label.run' }
      )
    );

    expect(mockApi.runIntegrationTask).not.toHaveBeenCalled();

    fireEvent.change(date, { target: { value: '2026-11-08' } });
    await click(
      within(screen.getByTestId('integration-backfill-drawer')).getByRole(
        'button',
        { name: 'label.run' }
      )
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.BUSINESS_DATE_INVALID'
    );
    expect(date).toHaveValue('2026-11-08');
  });

  const openDorisCreate = async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      dorisConnection,
    ]);
    mockApi.getIntegrationTableOptions.mockImplementation(async (id) =>
      id === sourceConnection.id ? [{ ...table, columns: [] }] : []
    );
    mockApi.previewIntegrationTargetTable.mockResolvedValue(dorisPreview);
    mockApi.createIntegrationTargetTable.mockResolvedValue(dorisTable);
    await renderWorkspace();
    await openTask();
    await click(screen.getByRole('button', { name: 'label.edit' }));
    await click(
      await screen.findByRole('button', {
        name: 'hospitalIntegration.createTargetFromSource',
      })
    );
  };

  it('previews Doris target metadata and invalidates confirmation when table or mapped columns change', async () => {
    await openDorisCreate();

    expect(document.getElementById('integration-new-target-table')).toHaveValue(
      'synthetic_encounter'
    );

    await userEvent.clear(document.getElementById('integration-new-column-0')!);
    await userEvent.type(
      document.getElementById('integration-new-column-0')!,
      'encounter_id'
    );
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );

    expect(
      await screen.findByTestId('integration-target-ddl')
    ).toHaveTextContent(dorisPreview.ddl);
    expect(mockApi.previewIntegrationTargetTable).toHaveBeenCalledWith(
      targetConnection.id,
      {
        sourceConnectionId: sourceConnection.id,
        sourceSchema: 'public',
        sourceTable: table.name,
        targetSchema: 'hospital_ods',
        targetTable: 'synthetic_encounter',
        fieldMappings: dorisPreview.fieldMappings,
      },
      expect.any(AbortSignal)
    );

    await userEvent.type(
      document.getElementById('integration-new-target-table')!,
      '_v2'
    );

    expect(
      screen.queryByTestId('integration-target-ddl')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).not.toBeInTheDocument();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    await screen.findByTestId('integration-target-ddl');
    await userEvent.type(
      document.getElementById('integration-new-column-1')!,
      '_name'
    );

    expect(
      screen.queryByTestId('integration-target-ddl')
    ).not.toBeInTheDocument();
  });

  it('retains the Doris creation draft after a failure and selects the refreshed table with returned mappings', async () => {
    await openDorisCreate();
    mockApi.createIntegrationTargetTable
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: {
          status: 503,
          data: { errorCode: 'TARGET_TABLE_CREATE_FAILED' },
        },
      })
      .mockResolvedValue(dorisTable);
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    await click(
      await screen.findByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.TARGET_TABLE_CREATE_FAILED'
    );
    expect(document.getElementById('integration-new-target-table')).toHaveValue(
      'synthetic_encounter'
    );

    mockApi.getIntegrationTableOptions.mockImplementation(async (id) =>
      id === sourceConnection.id
        ? [{ ...table, columns: [] }]
        : [{ ...dorisTable, columns: [] }]
    );
    mockApi.getIntegrationTable.mockImplementation(async (id, schema, name) =>
      id === targetConnection.id ? dorisTable : { ...table, schema, name }
    );
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    );
    await waitFor(() =>
      expect(
        document.getElementById('integration-target-table')
      ).toHaveTextContent('hospital_ods.synthetic_encounter')
    );

    expect(screen.getByRole('textbox', { name: /label.name/ })).toHaveValue(
      task.name
    );
    expect(
      screen.getByRole('textbox', { name: /label.display-name/ })
    ).toHaveValue(task.displayName);

    await click(screen.getByRole('button', { name: 'label.validate' }));
    await waitFor(() =>
      expect(mockApi.validateIntegrationTask).toHaveBeenCalledWith(
        expect.objectContaining({
          targetSchema: dorisTable.schema,
          targetTable: dorisTable.name,
          fieldMappings: dorisPreview.fieldMappings,
          primaryKey: 'id',
          mode: 'CDC',
        })
      )
    );

    expect(
      mockApi.getIntegrationTableOptions.mock.calls.filter(
        ([id]) => id === targetConnection.id
      ).length
    ).toBeGreaterThan(1);
  });

  it('requires a fresh Doris preview after a stale preview error', async () => {
    await openDorisCreate();
    mockApi.createIntegrationTargetTable.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 409, data: { errorCode: 'TABLE_PREVIEW_STALE' } },
    });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    await click(
      await screen.findByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'hospitalIntegration.errors.TABLE_PREVIEW_STALE'
    );
    expect(
      screen.queryByTestId('integration-target-ddl')
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).not.toBeInTheDocument();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );

    expect(
      await screen.findByTestId('integration-target-ddl')
    ).toHaveTextContent(dorisPreview.ddl);
  });

  it('locks the task drawer and repeated Doris creation while the create request is pending', async () => {
    await openDorisCreate();
    let finish: (table: api.IntegrationTable) => void = () => undefined;
    mockApi.createIntegrationTargetTable.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        })
    );
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    const create = await screen.findByRole('button', {
      name: 'hospitalIntegration.confirmCreateTargetTable',
    });
    await click(create);
    await click(create);

    expect(mockApi.createIntegrationTargetTable).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'label.cancel' })).toBeDisabled();

    await userEvent.keyboard('{Escape}');

    expect(screen.getByTestId('integration-task-drawer')).toBeInTheDocument();

    await act(async () => finish(dorisTable));
  });

  it('shows and filters Doris data sources with both endpoint ports', async () => {
    mockApi.getIntegrationConnections.mockResolvedValue([
      sourceConnection,
      dorisConnection,
    ]);
    await renderSources();
    const row = within(
      screen.getByTestId('integration-connection-row-' + dorisConnection.id)
    );

    expect(
      row.getByText('hospitalIntegration.databaseTypes.Doris')
    ).toBeInTheDocument();
    expect(row.getByText('3.1.3')).toBeInTheDocument();
    expect(row.getByText(/8030/)).toBeInTheDocument();
    expect(row.getByText(/9030/)).toBeInTheDocument();

    await choose(
      'integration-filter-type',
      'hospitalIntegration.databaseTypes.Doris'
    );

    expect(
      screen.queryByTestId('integration-connection-row-' + sourceConnection.id)
    ).not.toBeInTheDocument();
    expect(
      screen.getByTestId('integration-connection-row-' + dorisConnection.id)
    ).toBeInTheDocument();
  });

  it('explains a rejected Doris label, retains the draft and previews again after shortening the name', async () => {
    await openDorisCreate();
    const targetName = document.getElementById('integration-new-target-table')!;
    const columnName = document.getElementById('integration-new-column-0')!;
    await userEvent.clear(targetName);
    await userEvent.type(targetName, 'a'.repeat(60));
    await userEvent.clear(columnName);
    await userEvent.type(columnName, 'encounter_id');
    mockApi.previewIntegrationTargetTable.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 400, data: { errorCode: 'DORIS_LABEL_INVALID' } },
    });
    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    const alert = await screen.findByRole('alert');

    expect(alert).toHaveTextContent(
      'hospitalIntegration.errors.DORIS_LABEL_INVALID'
    );

    expectRevealedError(alert);

    expect(targetName).toHaveValue('a'.repeat(60));
    expect(columnName).toHaveValue('encounter_id');
    expect(
      screen.queryByTestId('integration-target-ddl')
    ).not.toBeInTheDocument();
    expect(mockApi.createIntegrationTargetTable).not.toHaveBeenCalled();

    await userEvent.clear(targetName);
    await userEvent.type(targetName, 'encounter_daily');

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );

    expect(
      await screen.findByTestId('integration-target-ddl')
    ).toHaveTextContent(dorisPreview.ddl);
    expect(mockApi.previewIntegrationTargetTable).toHaveBeenLastCalledWith(
      targetConnection.id,
      expect.objectContaining({
        targetTable: 'encounter_daily',
        fieldMappings: dorisPreview.fieldMappings,
      }),
      expect.any(AbortSignal)
    );
  });
});
