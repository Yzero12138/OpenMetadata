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
import { useState } from 'react';
import { BrowserRouter, useLocation } from 'react-router-dom';
import { IntegrationNavigationGuardProvider } from './IntegrationNavigationGuardProvider';
import { useIntegrationNavigationGuard } from './useIntegrationNavigationGuard';

const Fixture = () => {
  const [enabled, setEnabled] = useState(false);
  const { blocked, stay } = useIntegrationNavigationGuard({
    enabled,
    locked: false,
  });
  const location = useLocation();

  return (
    <>
      <p data-testid="route">{location.pathname}</p>
      <button onClick={() => setEnabled(true)}>Edit</button>
      {blocked && <button onClick={stay}>Continue</button>}
    </>
  );
};

describe('integration browser history boundary', () => {
  it('keeps BrowserRouter on the editor while restoring an unindexed forward entry', async () => {
    const url = window.location.href;
    const state: unknown = window.history.state;
    window.history.replaceState({ idx: 0 }, '', '/hospital/integration/tasks');
    const go = jest
      .spyOn(window.history, 'go')
      .mockImplementation(() => undefined);
    try {
      render(
        <IntegrationNavigationGuardProvider>
          <BrowserRouter>
            <Fixture />
          </BrowserRouter>
        </IntegrationNavigationGuardProvider>
      );
      window.history.replaceState(null, '', '/hospital/integration/tasks');
      await act(async () => {
        screen.getByRole('button', { name: 'Edit' }).click();
      });
      await act(async () => {
        window.history.replaceState(
          { idx: 1 },
          '',
          '/hospital/integration/sources'
        );
        window.dispatchEvent(
          new PopStateEvent('popstate', { state: { idx: 1 } })
        );
      });

      expect(go).toHaveBeenLastCalledWith(1);
      expect(screen.getByTestId('route')).toHaveTextContent(
        '/hospital/integration/tasks'
      );
    } finally {
      go.mockRestore();
      window.history.replaceState(state, '', url);
    }
  });
});
