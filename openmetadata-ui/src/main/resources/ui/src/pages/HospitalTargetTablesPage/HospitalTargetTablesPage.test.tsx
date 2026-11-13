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
import { MemoryRouter } from 'react-router-dom';
import * as api from '../../rest/hospitalIntegrationAPI';
import {
  dorisConnection,
  dorisStructure,
  dorisTable,
} from './DorisTableDesign.test-fixtures';
import { HospitalTargetTablesWorkspace } from './HospitalTargetTablesPage';

jest.mock('../../rest/hospitalIntegrationAPI', () => ({
  ...jest.requireActual('../../rest/hospitalIntegrationAPI'),
  getIntegrationConnections: jest.fn(),
  getIntegrationTableOptions: jest.fn(),
  getDorisTableStructure: jest.fn(),
}));
const mockApi = api as jest.Mocked<typeof api>;

describe('Doris target table management', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    mockApi.getIntegrationConnections.mockResolvedValue([dorisConnection]);
    mockApi.getIntegrationTableOptions.mockResolvedValue([dorisTable]);
    mockApi.getDorisTableStructure.mockResolvedValue(dorisStructure);
  });

  it('lists Doris targets independently and opens manual create without a source', async () => {
    await act(async () => {
      render(
        <MemoryRouter>
          <HospitalTargetTablesWorkspace isAdmin />
        </MemoryRouter>
      );
    });
    await waitFor(() =>
      expect(mockApi.getIntegrationConnections).toHaveBeenCalled()
    );
    await waitFor(() =>
      expect(mockApi.getIntegrationTableOptions).toHaveBeenCalledWith(
        dorisConnection.id,
        expect.any(AbortSignal)
      )
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'hospitalTableDesign.create' })
      ).toBeEnabled()
    );

    expect(screen.getByText('visits')).toBeVisible();

    await userEvent.click(
      screen.getByRole('button', { name: 'hospitalTableDesign.create' })
    );

    expect(screen.getByTestId('doris-table-designer')).toBeVisible();
    expect(mockApi.getIntegrationTableOptions).toHaveBeenCalledWith(
      dorisConnection.id,
      expect.any(AbortSignal)
    );
  });

  it('loads the real existing structure before appending fields', async () => {
    await act(async () => {
      render(
        <MemoryRouter>
          <HospitalTargetTablesWorkspace isAdmin />
        </MemoryRouter>
      );
    });
    await waitFor(() =>
      expect(mockApi.getIntegrationTableOptions).toHaveBeenCalledWith(
        dorisConnection.id,
        expect.any(AbortSignal)
      )
    );
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', { name: 'hospitalTableDesign.addColumns' })
      );
    });

    expect(await screen.findByTestId('doris-table-designer')).toBeVisible();
    expect(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ })
    ).toBeDisabled();
    expect(mockApi.getDorisTableStructure).toHaveBeenCalledWith(
      'doris-target',
      'ods_validation',
      'visits',
      expect.any(AbortSignal)
    );
  });

  it('does not discover any connection when the user is not an administrator', () => {
    render(
      <MemoryRouter>
        <HospitalTargetTablesWorkspace isAdmin={false} />
      </MemoryRouter>
    );

    expect(screen.getByText('hospitalIntegration.adminOnly')).toBeVisible();
    expect(mockApi.getIntegrationConnections).not.toHaveBeenCalled();
  });
});
