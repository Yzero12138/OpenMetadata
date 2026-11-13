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
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { IntegrationConnection } from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import { EMPTY_INTEGRATION_TASK } from './HospitalIntegrationUtils';
import { IntegrationTaskForm } from './IntegrationTaskForm';

const target: IntegrationConnection = {
  id: 'doris-target',
  version: 1,
  name: 'ods',
  displayName: 'Doris ODS',
  role: 'TARGET',
  databaseType: 'Doris',
  databaseVersion: '4.0.8',
  host: 'doris.invalid',
  port: 9030,
  dorisHttpPort: 8030,
  database: 'ods_validation',
  schemas: ['ods_validation'],
  username: 'writer',
  tlsMode: 'DISABLED',
  enabled: true,
  managed: false,
  synthetic: true,
  passwordSet: true,
  supportedModes: ['FULL', 'SQL_T1'],
};

const props: IntegrationTaskFormProps = {
  value: { ...EMPTY_INTEGRATION_TASK, targetConnectionId: target.id },
  connections: [target],
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

describe('Manual target design in the task drawer', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
  });

  it('can design a Doris target before selecting a source table', async () => {
    render(<IntegrationTaskForm {...props} />);

    await userEvent.click(
      screen.getByRole('button', { name: 'hospitalTableDesign.create' })
    );

    expect(screen.getByTestId('doris-table-designer')).toBeVisible();
    expect(screen.getByRole('dialog')).toHaveClass('form-drawer__dialog');
    expect(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ })
    ).toBeEnabled();
    expect(
      screen.getByRole('button', { name: 'hospitalTableDesign.addField' })
    ).toBeEnabled();
  });

  it('retains a preselected Doris target when selecting the required source connection', async () => {
    const changed = jest.fn();
    const sourceConnection: IntegrationConnection = {
      ...target,
      id: 'his-source',
      name: 'his',
      displayName: 'HIS MySQL',
      role: 'SOURCE',
      databaseType: 'Mysql',
      database: 'his',
      schemas: ['his'],
      supportedModes: ['FULL'],
    };
    const targetTable = {
      schema: target.database,
      name: 'designed_visits',
      columns: [
        { name: 'id', dataType: 'BIGINT', primaryKey: true, nullable: false },
      ],
    };
    const Harness = () => {
      const [value, setValue] = useState({
        ...EMPTY_INTEGRATION_TASK,
        targetConnectionId: target.id,
        targetSchema: targetTable.schema,
        targetTable: targetTable.name,
      });

      return (
        <IntegrationTaskForm
          {...props}
          connections={[sourceConnection, target]}
          targetTables={[targetTable]}
          value={value}
          onChange={(next) => {
            changed(next);
            setValue(next);
          }}
        />
      );
    };
    render(<Harness />);
    await userEvent.click(
      screen.getByRole('button', {
        name: /hospitalIntegration.sourceConnection/,
      })
    );
    await userEvent.click(
      screen.getByRole('option', { name: sourceConnection.displayName })
    );

    expect(changed).toHaveBeenLastCalledWith(
      expect.objectContaining({
        sourceConnectionId: sourceConnection.id,
        targetConnectionId: target.id,
        targetSchema: targetTable.schema,
        targetTable: targetTable.name,
      })
    );
    expect(
      screen.getByRole('button', { name: /hospitalIntegration.targetTable/ })
    ).toHaveTextContent(targetTable.name);
  });

  it('keeps a source-derived draft dirty after opening and closing a clean manual designer', async () => {
    const dirty = jest.fn();
    const source = {
      schema: 'his',
      name: 'visits',
      columns: [
        { name: 'id', dataType: 'BIGINT', primaryKey: true, nullable: false },
      ],
    };
    const connections: IntegrationConnection[] = [
      target,
      {
        ...target,
        id: 'his-source',
        role: 'SOURCE',
        databaseType: 'Mysql',
        database: 'his',
        schemas: ['his'],
        supportedModes: ['FULL'],
      },
    ];
    const Harness = () => {
      const [value, setValue] = useState({
        ...EMPTY_INTEGRATION_TASK,
        sourceConnectionId: 'his-source',
        sourceSchema: 'his',
        sourceTable: 'visits',
        targetConnectionId: target.id,
      });

      return (
        <IntegrationTaskForm
          {...props}
          connections={connections}
          sourceTables={[source]}
          value={value}
          onChange={setValue}
          onTargetTableDraftChange={dirty}
        />
      );
    };
    render(<Harness />);
    await userEvent.click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.createTargetFromSource',
      })
    );
    await userEvent.type(
      document.getElementById('integration-new-target-table')!,
      '_draft'
    );

    expect(dirty).toHaveBeenLastCalledWith(true);

    await userEvent.click(
      screen.getByRole('button', { name: 'hospitalTableDesign.create' })
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'label.cancel',
      })
    );

    expect(document.getElementById('integration-new-target-table')).toHaveValue(
      'visits_draft'
    );
    expect(dirty).toHaveBeenLastCalledWith(true);
  });

  it('keeps target design unavailable when the task route is locked', () => {
    render(
      <IntegrationTaskForm
        {...props}
        initializationState={{
          id: 'frozen-history',
          status: 'RUNNING',
          startDate: '2026-10-01',
          cutoffDate: '2026-11-13',
          nextDate: '2026-10-01',
          batchUnit: 'MONTH',
          completedBatches: 0,
          totalBatches: 2,
          startedAt: 1,
        }}
        value={{
          ...props.value,
          mode: 'SQL_T1',
          initialization: {
            enabled: true,
            startDate: '2026-10-01',
            batchUnit: 'MONTH',
          },
        }}
      />
    );

    expect(
      screen.queryByRole('button', { name: 'hospitalTableDesign.create' })
    ).not.toBeInTheDocument();
  });
});
