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
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { IntegrationCdcPreflightResult } from '../../rest/hospitalIntegrationAPI';
import { EMPTY_INTEGRATION_TASK } from './HospitalIntegrationUtils';
import { IntegrationCdcPreflight } from './IntegrationCdcPreflight';
import { IntegrationExecutionSettings } from './IntegrationExecutionSettings';
import { useIntegrationScopedRead } from './useIntegrationScopedRead';

const result: IntegrationCdcPreflightResult = {
  connectionId: 'oracle',
  databaseType: 'Oracle',
  implemented: true,
  ready: false,
  checks: [],
};

describe('CDC operator settings and evidence', () => {
  it('lists required blockers before optional warnings and passes without mutating server evidence', () => {
    const evidence: IntegrationCdcPreflightResult = {
      ...result,
      checks: [
        {
          id: 'plugin',
          code: 'CDC_ENGINE_PLUGIN_AVAILABLE',
          status: 'PASS',
          required: true,
        },
        {
          id: 'network',
          code: 'ENGINE_SOURCE_NETWORK_UNVERIFIED',
          status: 'UNKNOWN',
          required: false,
        },
        {
          id: 'archive',
          code: 'ORACLE_ARCHIVELOG_REQUIRED',
          status: 'FAIL',
          required: true,
        },
        {
          id: 'minimum',
          code: 'ORACLE_MIN_SUPPLEMENTAL_UNVERIFIED',
          status: 'UNKNOWN',
          required: true,
        },
        {
          id: 'runtime',
          code: 'ORACLE_CDC_RUNTIME_UNVERIFIED',
          status: 'FAIL',
          required: false,
        },
        {
          id: 'key',
          code: 'CDC_PRIMARY_KEY_PROVEN',
          status: 'PASS',
          required: true,
        },
        {
          id: 'lob',
          code: 'ORACLE_CDC_LOB_UNVERIFIED',
          status: 'FAIL',
          required: true,
        },
      ],
    };
    const originalOrder = evidence.checks.map((check) => check.id);
    Object.freeze(evidence.checks);
    render(<IntegrationCdcPreflight result={evidence} />);
    const rows = screen.getAllByRole('listitem');
    for (const [index, message] of [
      'archive',
      'minimumLog',
      'lob',
      'network',
      'runtimeWarning',
      'plugin',
      'primaryKey',
    ].entries()) {
      expect(
        within(rows[index]).getByText('hospitalCdc.' + message)
      ).toBeInTheDocument();
    }

    expect(evidence.checks.map((check) => check.id)).toEqual(originalOrder);
  });

  it.each(['result', 'error'])(
    'focuses the current %s summary when an explicit preflight settles',
    (outcome) => {
      const { rerender } = render(
        <>
          <button>Keep editing</button>
          <IntegrationCdcPreflight loading />
        </>
      );
      const button = screen.getByRole('button', { name: 'Keep editing' });
      button.focus();
      rerender(
        <>
          <button>Keep editing</button>
          <IntegrationCdcPreflight
            errorCode={
              outcome === 'error' ? 'CONNECTION_UNAVAILABLE' : undefined
            }
            result={outcome === 'result' ? result : undefined}
          />
        </>
      );
      const summary = screen.getByText(
        outcome === 'result'
          ? 'hospitalCdc.requiredBlocked'
          : 'hospitalIntegration.errors.CONNECTION_UNAVAILABLE'
      );

      expect(summary).toHaveAttribute('tabindex', '-1');
      expect(summary).toHaveFocus();
    }
  );

  it('does not move focus when a stale preflight resolves after a scope change', async () => {
    let resolve!: (value: IntegrationCdcPreflightResult) => void;
    const pending = new Promise<IntegrationCdcPreflightResult>((success) => {
      resolve = success;
    });
    const Harness = ({ scope }: { scope: string }) => {
      const read =
        useIntegrationScopedRead<IntegrationCdcPreflightResult>(scope);

      return (
        <>
          <button onClick={() => void read.execute(() => pending)}>
            Check
          </button>
          <button>Keep editing</button>
          <IntegrationCdcPreflight
            errorCode={read.errorCode}
            loading={read.loading}
            result={read.data}
          />
        </>
      );
    };
    const { rerender } = render(<Harness scope="source:a" />);
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    rerender(<Harness scope="source:b" />);
    const button = screen.getByRole('button', { name: 'Keep editing' });
    button.focus();
    await act(async () => resolve(result));

    expect(button).toHaveFocus();
    expect(
      screen.queryByText('hospitalCdc.requiredBlocked')
    ).not.toBeInTheDocument();
  });

  it('shows required unknown blockers and optional uncertainty with text and never infers runtime acceptance', () => {
    render(
      <IntegrationCdcPreflight
        result={{
          connectionId: 'oracle',
          databaseType: 'Oracle',
          implemented: true,
          ready: false,
          checks: [
            {
              id: 'bytes',
              code: 'ORACLE_CDC_STRING_BYTES_UNVERIFIED',
              required: true,
              status: 'UNKNOWN',
            },
            {
              id: 'network',
              code: 'ENGINE_SOURCE_NETWORK_UNVERIFIED',
              required: false,
              status: 'UNKNOWN',
            },
          ],
        }}
      />
    );

    expect(screen.getByText('hospitalCdc.requiredBlocked')).toBeInTheDocument();
    expect(screen.getByText('hospitalCdc.stringBytes')).toBeInTheDocument();
    expect(screen.getByText('hospitalCdc.network')).toBeInTheDocument();
    expect(screen.getByText('hospitalCdc.acceptanceHint')).toBeInTheDocument();
  });

  it('renders strict runtime controls with inherited defaults and reports invalid text without persisting coercions', () => {
    const change = jest.fn();
    const invalid = jest.fn();
    const { rerender } = render(
      <IntegrationExecutionSettings
        disabled={false}
        value={EMPTY_INTEGRATION_TASK}
        onChange={change}
        onValidityChange={invalid}
      />
    );
    const fetch = screen.getByLabelText(/hospitalCdc.fetchSize/);

    expect(fetch).toHaveValue('');
    expect(fetch).toHaveAttribute('placeholder', '1000');
    expect(change).not.toHaveBeenCalled();

    fireEvent.change(fetch, { target: { value: '1000x' } });

    expect(invalid).toHaveBeenLastCalledWith(true);
    expect(change).not.toHaveBeenCalled();

    fireEvent.change(fetch, { target: { value: '2000' } });

    expect(change).toHaveBeenLastCalledWith(
      expect.objectContaining({ runtime: { fetchSize: 2000 } })
    );

    rerender(
      <IntegrationExecutionSettings
        disabled={false}
        value={{ ...EMPTY_INTEGRATION_TASK, mode: 'SQL_T1' }}
        onChange={change}
        onValidityChange={invalid}
      />
    );

    expect(
      screen.queryByLabelText(/hospitalCdc.splitSize/)
    ).not.toBeInTheDocument();
  });
});
