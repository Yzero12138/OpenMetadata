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
import { render, screen, within } from '@testing-library/react';
import {
  IntegrationInitializationState,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import { EMPTY_INTEGRATION_TASK } from './HospitalIntegrationUtils';
import { IntegrationTaskForm } from './IntegrationTaskForm';

const initialization: NonNullable<IntegrationTaskInput['initialization']> = {
  enabled: true,
  startDate: '2026-10-14',
  batchUnit: 'MONTH',
};
const state: IntegrationInitializationState = {
  id: 'history-cutoff',
  status: 'PENDING',
  startDate: initialization.startDate,
  batchUnit: initialization.batchUnit,
  cutoffDate: '2026-11-12',
  nextDate: initialization.startDate,
  completedBatches: 0,
  totalBatches: 2,
};
const props: IntegrationTaskFormProps = {
  value: {
    ...EMPTY_INTEGRATION_TASK,
    mode: 'SQL_T1',
    initialization,
    schedule: { enabled: false, dailyTime: '02:00', timeZone: 'Asia/Shanghai' },
  },
  initializationState: state,
  connections: [],
  sourceTables: [],
  targetTables: [],
  tablesLoading: false,
  tablesUnavailable: false,
  disabled: false,
  saving: false,
  validating: false,
  errors: [],
  onChange: jest.fn(),
  onReloadTables: jest.fn(),
  onValidate: jest.fn(),
  onSave: jest.fn(),
  onCancel: jest.fn(),
  onTargetTableBusyChange: jest.fn(),
  onTargetTableDraftChange: jest.fn(),
  onTargetTableCreated: jest.fn(),
};
const lastWindow = () =>
  within(screen.getByTestId('integration-initialization-preview')).getByText(
    'hospitalIntegration.initializationLastBatch'
  ).nextElementSibling;

describe('Historical initialization cutoff preview', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-11-13T04:00:00Z'));
  });

  afterEach(() => jest.useRealTimers());

  it.each([
    {
      startDate: '2026-10-15',
      batchUnit: 'MONTH' as const,
      expected: '[2026-11-01, 2026-11-13)',
    },
    {
      startDate: initialization.startDate,
      batchUnit: 'DAY' as const,
      expected: '[2026-11-12, 2026-11-13)',
    },
  ])(
    'previews today after changing an unstarted paused configuration: $batchUnit $startDate',
    ({ startDate, batchUnit, expected }) => {
      const { rerender } = render(<IntegrationTaskForm {...props} />);

      expect(lastWindow()).toHaveTextContent('[2026-11-01, 2026-11-12)');

      rerender(
        <IntegrationTaskForm
          {...props}
          value={{
            ...props.value,
            initialization: { ...initialization, startDate, batchUnit },
          }}
        />
      );

      expect(lastWindow()).toHaveTextContent(expected);

      rerender(<IntegrationTaskForm {...props} />);

      expect(lastWindow()).toHaveTextContent('[2026-11-01, 2026-11-12)');
    }
  );

  it.each([
    { ...state, status: 'RUNNING' as const, startedAt: 1 },
    {
      ...state,
      status: 'COMPLETED' as const,
      completedBatches: 2,
      nextDate: state.cutoffDate,
    },
  ])(
    'retains the frozen cutoff after history has started or completed: $status',
    (initializationState) => {
      const { rerender } = render(
        <IntegrationTaskForm
          {...props}
          initializationState={initializationState}
        />
      );

      expect(lastWindow()).toHaveTextContent('[2026-11-01, 2026-11-12)');

      rerender(
        <IntegrationTaskForm
          {...props}
          initializationState={initializationState}
          value={{
            ...props.value,
            initialization: {
              ...initialization,
              startDate: '2026-10-15',
              batchUnit: 'DAY',
            },
          }}
        />
      );

      expect(lastWindow()).toHaveTextContent('[2026-11-11, 2026-11-12)');
    }
  );
});
