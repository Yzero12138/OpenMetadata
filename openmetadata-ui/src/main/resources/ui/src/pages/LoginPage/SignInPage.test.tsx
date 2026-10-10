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
import { act, render, screen } from '@testing-library/react';
import axios from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ROUTES } from '../../constants/constants';
import { useApplicationStore } from '../../hooks/useApplicationStore';
import SignInPage from './SignInPage';

jest.mock('axios');
jest.mock('../../hooks/useApplicationStore');
const mockedGet = axios.get as jest.Mock;
const mockedStore = useApplicationStore as unknown as jest.Mock;

describe('Hospital portal sign-in', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    mockedStore.mockReturnValue({ isAuthenticated: false });
    mockedGet.mockResolvedValue({
      data: {
        enabled: true,
        issuer: 'https://integrate.hospital.test',
        portalUrl: 'https://integrate.hospital.test/s/portal',
      },
    });
  });

  it('offers the employee portal and has no native email or password fields', async () => {
    let container: HTMLElement;
    await act(async () => {
      ({ container } = render(<SignInPage />, { wrapper: MemoryRouter }));
    });
    const portal = await screen.findByRole('link', { name: 'portalAction' });

    expect(portal).toHaveAttribute(
      'href',
      'https://integrate.hospital.test/s/portal'
    );
    expect(container!.querySelector('input')).toBeNull();
    expect(screen.queryByText('label.sign-up')).not.toBeInTheDocument();
  });

  it('shows an actionable error when the connection is disabled', async () => {
    mockedGet.mockResolvedValue({ data: { enabled: false } });
    await act(async () => {
      render(<SignInPage />, { wrapper: MemoryRouter });
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('configError');
    expect(screen.getByRole('button', { name: 'portalAction' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'retry' })).toBeEnabled();
  });

  it('returns an already authenticated employee to the workbench', async () => {
    mockedStore.mockReturnValue({ isAuthenticated: true });
    render(
      <MemoryRouter initialEntries={[ROUTES.SIGNIN]}>
        <Routes>
          <Route element={<SignInPage />} path={ROUTES.SIGNIN} />
          <Route element={<p>Employee workbench</p>} path={ROUTES.HOME} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText('Employee workbench')).toBeInTheDocument();
  });
});
