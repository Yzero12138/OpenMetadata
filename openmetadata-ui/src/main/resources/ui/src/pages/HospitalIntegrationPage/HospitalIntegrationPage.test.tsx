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
import { act, render, screen, waitFor } from '@testing-library/react';
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
    mockApi.getIntegrationStatus.mockResolvedValue({
      enabled: true,
      reachable: true,
      engineVersion: '3.0.0',
    });
    mockApi.getIntegrationConnections.mockResolvedValue([
      {
        id: 'synthetic-source',
        displayName: 'Synthetic PostgreSQL source',
        role: 'SOURCE',
        databaseType: 'Postgres',
        synthetic: true,
      },
      {
        id: 'synthetic-ods',
        displayName: 'Synthetic PostgreSQL ODS',
        role: 'TARGET',
        databaseType: 'Postgres',
        synthetic: true,
      },
    ]);
    mockApi.getIntegrationTables.mockImplementation(async (id) => [
      { ...table, schema: id === 'synthetic-source' ? 'public' : 'ods' },
    ]);
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
      screen.getByText('hospitalIntegration.runStatus.STOP_FAILED')
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
      screen.getByText('hospitalIntegration.runStatus.FINISHED')
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
      screen.getByText('hospitalIntegration.runStatus.FINISHED')
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
});
