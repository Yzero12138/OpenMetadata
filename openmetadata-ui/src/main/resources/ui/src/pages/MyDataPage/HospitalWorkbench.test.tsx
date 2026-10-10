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
import { HelmetProvider } from 'react-helmet-async';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import APIClient from '../../rest';
import HospitalWorkbench from './HospitalWorkbench';

jest.mock('../../rest', () => ({
  __esModule: true,
  default: { get: jest.fn() },
}));
const get = APIClient.get as jest.Mock;

const renderWorkbench = async () => {
  await act(async () => {
    render(
      <HelmetProvider>
        <MemoryRouter>
          <Routes>
            <Route element={<HospitalWorkbench />} path="/" />
            <Route element={<p>Catalog search results</p>} path="/explore/*" />
          </Routes>
        </MemoryRouter>
      </HelmetProvider>
    );
  });
};

describe('Hospital governance workbench', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    get.mockResolvedValue({ data: { data: [], paging: { total: 0 } } });
  });

  it('shows actual zero totals and the ingestion empty state', async () => {
    await renderWorkbench();

    expect(await screen.findByText('empty')).toBeInTheDocument();
    expect(screen.getAllByText('0')).toHaveLength(4);
    expect(screen.queryByText('notAvailable')).not.toBeInTheDocument();
  });

  it('distinguishes denied metrics from a real zero and can recover on reload', async () => {
    get.mockRejectedValue({ response: { status: 403 } });
    await renderWorkbench();

    expect(await screen.findByRole('alert')).toHaveTextContent('failed');
    expect(screen.getAllByText('notAvailable')).toHaveLength(4);
    expect(screen.queryByText('0')).not.toBeInTheDocument();

    get.mockResolvedValue({ data: { data: [], paging: { total: 0 } } });
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: 'reload' }));
    });

    expect(await screen.findByText('empty')).toBeInTheDocument();
  });

  it('opens a catalog asset using its fully qualified name and searches natively', async () => {
    get.mockImplementation((path: string) =>
      Promise.resolve({
        data: {
          data:
            path === '/tables'
              ? [
                  {
                    id: 'fixture',
                    name: 'encounters',
                    displayName: 'Synthetic HIS encounters',
                    fullyQualifiedName: 'HIS.demo.public.encounters',
                    service: { name: 'HIS' },
                    owners: [{ name: 'clinical-data-team' }],
                  },
                ]
              : [],
          paging: { total: path === '/tables' ? 1 : 0 },
        },
      })
    );
    await renderWorkbench();
    const asset = await screen.findByRole('link', {
      name: 'Synthetic HIS encounters',
    });

    expect(asset).toHaveAttribute('href', '/table/HIS.demo.public.encounters');

    await userEvent.type(
      screen.getByRole('textbox', { name: /searchLabel/ }),
      'HIS'
    );
    await userEvent.click(screen.getByRole('button', { name: 'search' }));
    await waitFor(() =>
      expect(screen.getByText('Catalog search results')).toBeInTheDocument()
    );
  });
});
