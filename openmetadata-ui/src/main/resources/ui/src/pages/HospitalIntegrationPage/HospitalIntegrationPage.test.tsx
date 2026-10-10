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
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import * as api from '../../rest/hospitalIntegrationAPI';
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
  getIntegrationTasks: jest.fn(),
  getIntegrationTask: jest.fn(),
  validateIntegrationTask: jest.fn(),
  createIntegrationTask: jest.fn(),
  updateIntegrationTask: jest.fn(),
  runIntegrationTask: jest.fn(),
  stopIntegrationTask: jest.fn(),
  syncIntegrationCatalog: jest.fn(),
  testIntegrationConnection: jest.fn(),
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

const renderWorkspace = async (isAdmin = true) => {
  await act(async () => {
    render(
      <MemoryRouter>
        <HospitalIntegrationWorkspace isAdmin={isAdmin} />
      </MemoryRouter>
    );
  });
};
const click = async (element: HTMLElement) => {
  await act(async () => {
    await userEvent.click(element);
  });
};
const openTask = async () => {
  await act(async () => {
    await click(await screen.findByRole('button', { name: task.displayName }));
  });
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
      screen.getByText('hospitalIntegration.runStatus.SUBMITTED')
    ).toBeInTheDocument();

    await act(async () => {
      jest.advanceTimersByTime(5000);
    });

    expect(
      screen.getByText('hospitalIntegration.runStatus.FINISHED')
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

  const openConnections = async () => {
    await click(
      screen.getByRole('tab', { name: 'hospitalIntegration.dataSources' })
    );
  };
  const editBusinessConnection = async () => {
    await openConnections();
    await click(
      within(
        screen.getByTestId('integration-connection-row-business-source')
      ).getByRole('button', { name: 'label.edit' })
    );
  };
  const choose = async (id: string, label: string) => {
    const trigger = document.getElementById(id);

    expect(trigger).toBeDefined();

    await click(trigger!);
    await click(await screen.findByRole('option', { name: label }));
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

  it('has one create action per tab and keeps environment seeds immutable', async () => {
    await renderWorkspace();
    await openConnections();

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
    await renderWorkspace();
    await openConnections();
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
    await renderWorkspace();
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
    await renderWorkspace();
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
    await renderWorkspace();
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
    await renderWorkspace();
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
    await renderWorkspace();
    await editBusinessConnection();
    await userEvent.type(
      document.getElementById('connection-password')!,
      'synthetic-transient-password'
    );
    await click(screen.getByRole('button', { name: 'label.cancel' }));
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
    await renderWorkspace();
    await openConnections();
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
    await renderWorkspace();
    await openConnections();
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

  it('clears dependent selections and removes unsupported CDC on source change', async () => {
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
    ).not.toHaveTextContent(table.name);
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
    await renderWorkspace();
    await openConnections();
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

  it.each(['before', 'during'])(
    'retains a created source when a stale background GET starts %s the save',
    async (timing) => {
      jest.useFakeTimers();
      const raceUser = userEvent.setup({
        advanceTimers: jest.advanceTimersByTime,
      });
      const raceClick = async (element: HTMLElement) => {
        await act(async () => {
          await raceUser.click(element);
        });
      };
      let resolveSave: (value: api.IntegrationConnection) => void = () =>
        undefined;
      let resolveReload: (value: api.IntegrationTask[]) => void = () =>
        undefined;
      const reloadTasks = new Promise<api.IntegrationTask[]>((resolve) => {
        resolveReload = resolve;
      });
      const running = withRun({
        ...finished,
        status: 'RUNNING',
        finishedAt: undefined,
      });
      mockApi.getIntegrationTasks
        .mockResolvedValueOnce([running])
        .mockRejectedValueOnce(new Error('synthetic polling failure'))
        .mockImplementationOnce(() => reloadTasks);
      mockApi.createIntegrationConnection.mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveSave = resolve;
          })
      );
      await renderWorkspace();
      await raceClick(
        screen.getByRole('tab', { name: 'hospitalIntegration.dataSources' })
      );
      await raceClick(
        screen.getByRole('button', {
          name: 'hospitalIntegration.createConnection',
        })
      );
      for (const [id, value] of Object.entries({
        name: 'created_source',
        displayName: 'Created source',
        databaseVersion: '17',
        host: 'source.example.invalid',
        database: 'synthetic_db',
        username: 'reader',
        password: 'synthetic-password',
      })) {
        await raceUser.type(
          document.getElementById('connection-' + id)!,
          value
        );
      }
      const triggerReload = async () => {
        await act(async () => {
          jest.advanceTimersByTime(5000);
        });

        expect(mockApi.getIntegrationConnections).toHaveBeenCalledTimes(2);
      };
      if (timing === 'before') {
        await triggerReload();
      }
      await raceClick(screen.getByRole('button', { name: 'label.save' }));

      expect(mockApi.createIntegrationConnection).toHaveBeenCalledTimes(1);

      if (timing === 'during') {
        await triggerReload();
      }
      await act(async () => {
        resolveSave({
          ...businessConnection,
          id: 'created-source',
          name: 'created_source',
        });
      });
      await act(async () => {
        resolveReload([withRun(finished)]);
      });

      expect(
        screen.getByTestId('integration-connection-row-created-source')
      ).toBeInTheDocument();
      expect(
        screen.getByTestId('integration-connection-row-business-source')
      ).toBeInTheDocument();
    }
  );

  it('does not restore a deleted source from a GET started during deletion', async () => {
    jest.useFakeTimers();
    const raceUser = userEvent.setup({
      advanceTimers: jest.advanceTimersByTime,
    });
    const raceClick = async (element: HTMLElement) => {
      await act(async () => {
        await raceUser.click(element);
      });
    };
    let resolveDelete: () => void = () => undefined;
    let resolveReload: (value: api.IntegrationTask[]) => void = () => undefined;
    const reloadTasks = new Promise<api.IntegrationTask[]>((resolve) => {
      resolveReload = resolve;
    });
    mockApi.getIntegrationTasks
      .mockResolvedValueOnce([
        withRun({ ...finished, status: 'RUNNING', finishedAt: undefined }),
      ])
      .mockRejectedValueOnce(new Error('synthetic polling failure'))
      .mockImplementationOnce(() => reloadTasks);
    mockApi.deleteIntegrationConnection.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveDelete = () => resolve(undefined);
        })
    );
    await renderWorkspace();
    await raceClick(
      screen.getByRole('tab', { name: 'hospitalIntegration.dataSources' })
    );
    const row = within(
      screen.getByTestId('integration-connection-row-business-source')
    );
    await raceClick(row.getByRole('button', { name: 'label.delete' }));
    await raceClick(
      row.getByRole('button', {
        name: 'hospitalIntegration.confirmDeleteConnection',
      })
    );
    await act(async () => {
      jest.advanceTimersByTime(5000);
    });

    expect(mockApi.getIntegrationConnections).toHaveBeenCalledTimes(2);

    await act(async () => {
      resolveDelete();
    });
    await act(async () => {
      resolveReload([withRun(finished)]);
    });

    expect(
      screen.queryByTestId('integration-connection-row-business-source')
    ).not.toBeInTheDocument();
    expect(
      screen.getByTestId('integration-connection-row-synthetic-source')
    ).toBeInTheDocument();
  });

  it('reveals data-source client validation and keeps the unsaved draft', async () => {
    await renderWorkspace();
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
});
