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
import userEvent from '@testing-library/user-event';
import * as api from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import { EMPTY_INTEGRATION_TASK } from './HospitalIntegrationUtils';
import { IntegrationTaskForm } from './IntegrationTaskForm';

jest.mock('../../rest/hospitalIntegrationAPI', () => ({
  ...jest.requireActual('../../rest/hospitalIntegrationAPI'),
  previewIntegrationTargetTable: jest.fn(),
  createIntegrationTargetTable: jest.fn(),
}));
const mockApi = api as jest.Mocked<typeof api>;
const source: api.IntegrationTable = {
  schema: 'public',
  name: 'Patient Records',
  columns: [
    { name: 'ID', dataType: 'bigint', nullable: false, primaryKey: true },
    { name: 'notes', dataType: 'text', nullable: true, primaryKey: false },
  ],
};
const target: api.IntegrationConnection = {
  id: 'doris',
  version: 1,
  name: 'target',
  displayName: 'Doris target',
  role: 'TARGET',
  databaseType: 'Doris',
  databaseVersion: '3.1.3',
  database: 'hospital_ods',
  schemas: ['hospital_ods'],
  host: 'doris.example.invalid',
  port: 9030,
  dorisHttpPort: 8030,
  username: 'writer',
  tlsMode: 'DISABLED',
  enabled: true,
  managed: false,
  synthetic: true,
  passwordSet: true,
  supportedModes: ['FULL'],
};
const props: IntegrationTaskFormProps = {
  value: {
    ...EMPTY_INTEGRATION_TASK,
    sourceConnectionId: 'source',
    sourceSchema: source.schema,
    sourceTable: source.name,
    targetConnectionId: target.id,
  },
  connections: [target],
  sourceTables: [source],
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
const open = async () => {
  await userEvent.click(
    screen.getByRole('button', {
      name: 'hospitalIntegration.createTargetFromSource',
    })
  );
};

describe('Doris target table draft lifecycle', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
  });

  it('previews SQL result metadata with its query and declared alias key', async () => {
    const query =
      'SELECT ID, notes FROM public.synthetic WHERE day = :biz_date';
    const sqlSource = {
      ...source,
      name: 'sql_result',
      columns: source.columns.map((column) => ({
        ...column,
        primaryKey: false,
      })),
    };
    render(
      <IntegrationTaskForm
        {...props}
        sourceTables={[sqlSource]}
        value={{
          ...props.value,
          mode: 'SQL_T1',
          sourceTable: 'sql_result',
          sourceQuery: query,
          primaryKey: 'ID',
          schedule: {
            enabled: false,
            dailyTime: '02:00',
            timeZone: 'Asia/Shanghai',
          },
        }}
      />
    );
    await open();
    await userEvent.click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );

    expect(mockApi.previewIntegrationTargetTable).toHaveBeenCalledWith(
      target.id,
      expect.objectContaining({
        mode: 'SQL_T1',
        sourceQuery: query,
        primaryKey: 'ID',
        sourceSchema: 'public',
        sourceTable: 'sql_result',
      }),
      expect.any(AbortSignal)
    );
  });

  it('ignores an old preview after the source column metadata changes', async () => {
    let resolvePreview: (
      value: api.IntegrationTargetTablePreview
    ) => void = () => undefined;
    mockApi.previewIntegrationTargetTable.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolvePreview = resolve;
        })
    );
    const { rerender } = render(<IntegrationTaskForm {...props} />);
    await open();
    await userEvent.click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
    rerender(
      <IntegrationTaskForm
        {...props}
        sourceTables={[
          {
            ...source,
            columns: [
              ...source.columns,
              {
                name: 'changed',
                dataType: 'text',
                nullable: true,
                primaryKey: false,
              },
            ],
          },
        ]}
      />
    );
    await act(async () =>
      resolvePreview({
        targetConnectionId: target.id,
        previewHash: 'stale',
        ddl: 'STALE DDL',
        table: source,
        fieldMappings: [],
      })
    );

    expect(
      screen.queryByTestId('integration-target-ddl')
    ).not.toBeInTheDocument();

    await open();

    expect(document.getElementById('integration-new-column-2')).toHaveValue(
      'changed'
    );
    expect(mockApi.createIntegrationTargetTable).not.toHaveBeenCalled();
  });

  it('keeps source keys included and makes skipped fields explicit in the preview payload', async () => {
    mockApi.previewIntegrationTargetTable.mockResolvedValue({
      targetConnectionId: target.id,
      previewHash: 'hash',
      ddl: 'DDL',
      table: source,
      fieldMappings: [{ source: 'ID', target: 'id' }],
    });
    render(<IntegrationTaskForm {...props} />);
    await open();
    const checkboxes = screen.getAllByRole('checkbox');

    expect(checkboxes[0]).toBeDisabled();
    expect(checkboxes[0]).toBeChecked();

    await userEvent.click(checkboxes[1]);

    expect(document.getElementById('integration-new-column-1')).toBeDisabled();

    await userEvent.click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );

    expect(
      mockApi.previewIntegrationTargetTable.mock.calls[0][1].fieldMappings
    ).toEqual([{ source: 'ID', target: 'id' }]);
  });
});
