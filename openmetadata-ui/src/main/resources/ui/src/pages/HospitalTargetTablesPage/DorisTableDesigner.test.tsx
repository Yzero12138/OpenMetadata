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
import {
  dorisConnection,
  dorisStructure,
} from './DorisTableDesign.test-fixtures';
import { DorisTableDesigner } from './DorisTableDesigner';

jest.mock('../../rest/hospitalIntegrationAPI', () => ({
  ...jest.requireActual('../../rest/hospitalIntegrationAPI'),
  previewDesignedDorisTable: jest.fn(),
  createDesignedDorisTable: jest.fn(),
  previewDorisTableColumns: jest.fn(),
  addDorisTableColumns: jest.fn(),
  getDorisTableStructure: jest.fn(),
  previewIntegrationTargetTable: jest.fn(),
}));
const mockApi = api as jest.Mocked<typeof api>;
const applied = jest.fn();
const close = jest.fn();
const previewClick = async () =>
  act(async () => {
    await userEvent.click(
      screen.getByRole('button', {
        name: 'hospitalIntegration.previewTargetTable',
      })
    );
  });
const createPreview: api.DorisTableDesignPreview = {
  targetConnectionId: dorisConnection.id,
  action: 'CREATE',
  targetSchema: 'ods_validation',
  targetTable: 'visits',
  previewHash: 'b'.repeat(64),
  ddl: dorisStructure.ddl,
  columns: dorisStructure.columns,
  table: dorisStructure.table,
};

describe('Doris table designer mutations', () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    mockApi.previewDesignedDorisTable.mockResolvedValue(createPreview);
    mockApi.createDesignedDorisTable.mockResolvedValue({
      status: 'APPLIED',
      structure: dorisStructure,
    });
  });

  it('previews then creates an actual configured table without raw caller DDL', async () => {
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ }),
      'visits'
    );
    await previewClick();

    expect(screen.getByText('hospitalTableDesign.createHint')).toBeVisible();

    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.confirmCreateTargetTable',
        })
      );
    });

    expect(mockApi.createDesignedDorisTable).toHaveBeenCalledWith(
      dorisConnection.id,
      {
        targetSchema: 'ods_validation',
        targetTable: 'visits',
        comment: '',
        buckets: 1,
        columns: dorisStructure.columns,
        previewHash: createPreview.previewHash,
      }
    );
    expect(applied).toHaveBeenCalledWith(dorisStructure, []);
  });

  it('invalidates an old preview after changing the table name', async () => {
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        onApplied={applied}
        onClose={close}
      />
    );
    const name = screen.getByRole('textbox', {
      name: /hospitalIntegration.targetTable/,
    });
    await userEvent.type(name, 'visits');
    await previewClick();

    expect(
      screen.getByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).toBeEnabled();

    await userEvent.type(name, '_changed');

    expect(
      screen.getByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).toBeDisabled();
    expect(screen.queryByTestId('doris-table-preview')).not.toBeInTheDocument();
    expect(mockApi.createDesignedDorisTable).not.toHaveBeenCalled();
  });

  it('retains draft fields and explains an execution permission error', async () => {
    mockApi.createDesignedDorisTable.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 403,
        data: { errorCode: 'TARGET_TABLE_PERMISSION_DENIED' },
      },
    });
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ }),
      'visits'
    );
    await previewClick();
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.confirmCreateTargetTable',
        })
      );
    });

    expect(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ })
    ).toHaveValue('visits');
    expect(
      screen.getByText('hospitalTableDesign.errors.permission')
    ).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });

  it('does not finish while the Doris schema job is active even if new columns are visible', async () => {
    const column: api.DorisColumnInput = {
      name: 'note',
      type: 'VARCHAR',
      length: 256,
      nullable: true,
      primaryKey: false,
      defaultKind: 'NONE',
      comment: '',
    };
    const structure: api.DorisTableStructure = {
      ...dorisStructure,
      columns: [...dorisStructure.columns, column],
      table: {
        ...dorisStructure.table,
        columns: [
          ...dorisStructure.table.columns,
          {
            name: 'note',
            dataType: 'VARCHAR(256)',
            nullable: true,
            primaryKey: false,
          },
        ],
      },
      change: { jobId: 'schema-1', state: 'RUNNING' },
    };
    mockApi.previewDorisTableColumns.mockResolvedValue({
      ...createPreview,
      action: 'ADD_COLUMNS',
      ddl: 'ALTER TABLE visits ADD COLUMN note VARCHAR(256) NULL',
      columns: [column],
      table: structure.table,
    });
    mockApi.addDorisTableColumns.mockResolvedValue({
      status: 'SUBMITTED',
      structure,
    });
    mockApi.getDorisTableStructure.mockResolvedValue(structure);
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        existing={dorisStructure}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: 'label.name 1' }),
      'note'
    );
    await previewClick();
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', { name: 'hospitalTableDesign.applyColumns' })
      );
    });
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(applied).not.toHaveBeenCalled();
    expect(screen.getByText('hospitalTableDesign.submitted')).toBeVisible();

    mockApi.getDorisTableStructure.mockResolvedValue({
      ...structure,
      change: { jobId: 'schema-1', state: 'FINISHED' },
    });
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(applied).toHaveBeenCalledTimes(1);
  });

  it('waits for the configured default and comment before completing an appended field', async () => {
    const expected: api.DorisColumnInput = {
      name: 'note',
      type: 'VARCHAR',
      length: 256,
      nullable: false,
      primaryKey: false,
      defaultKind: 'LITERAL',
      defaultValue: 'unassigned',
      comment: 'Imported note',
    };
    const actual = {
      ...expected,
      defaultKind: 'NONE' as const,
      defaultValue: undefined,
    };
    const structure: api.DorisTableStructure = {
      ...dorisStructure,
      columns: [...dorisStructure.columns, actual],
      table: {
        ...dorisStructure.table,
        columns: [
          ...dorisStructure.table.columns,
          {
            name: 'note',
            dataType: 'VARCHAR(256)',
            nullable: false,
            primaryKey: false,
          },
        ],
      },
      change: { jobId: '10001', state: 'FINISHED' },
    };
    mockApi.previewDorisTableColumns.mockResolvedValue({
      ...createPreview,
      action: 'ADD_COLUMNS',
      ddl: 'ALTER TABLE visits ADD COLUMN note VARCHAR(256) NOT NULL DEFAULT "unassigned" COMMENT "Imported note"',
      columns: [expected],
      table: structure.table,
    });
    mockApi.addDorisTableColumns.mockResolvedValue({
      status: 'SUBMITTED',
      structure: dorisStructure,
    });
    mockApi.getDorisTableStructure.mockResolvedValue(structure);
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        existing={dorisStructure}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: 'label.name 1' }),
      'note'
    );
    await userEvent.click(
      screen.getByRole('checkbox', { name: 'hospitalTableDesign.nullable 1' })
    );
    await userEvent.click(
      screen.getByRole('button', { name: /hospitalTableDesign.defaultKind/ })
    );
    await userEvent.click(
      screen.getByRole('option', {
        name: 'hospitalTableDesign.defaults.LITERAL',
      })
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalTableDesign.defaultValue/ }),
      'unassigned'
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /label.description/ }),
      'Imported note'
    );
    await previewClick();
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', { name: 'hospitalTableDesign.applyColumns' })
      );
    });
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(applied).not.toHaveBeenCalled();

    mockApi.getDorisTableStructure.mockResolvedValue({
      ...structure,
      columns: [...dorisStructure.columns, { ...expected, comment: '' }],
    });
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(applied).not.toHaveBeenCalled();

    const completed = {
      ...structure,
      columns: [...dorisStructure.columns, expected],
    };
    mockApi.getDorisTableStructure.mockResolvedValue(completed);
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(applied).toHaveBeenCalledTimes(1);
    expect(applied).toHaveBeenCalledWith(completed, []);
  });

  it('fills fields from live source metadata while preserving table settings', async () => {
    const table: api.IntegrationTable = {
      schema: 'public',
      name: 'visits',
      columns: [
        { name: 'ID', dataType: 'bigint', nullable: false, primaryKey: true },
      ],
    };
    mockApi.previewIntegrationTargetTable.mockResolvedValue({
      targetConnectionId: dorisConnection.id,
      previewHash: 'c'.repeat(64),
      ddl: 'CREATE TABLE visits_ods',
      table: {
        schema: 'ods_validation',
        name: 'visits_ods',
        columns: [
          {
            name: 'visit_id',
            dataType: 'BIGINT',
            nullable: false,
            primaryKey: true,
          },
        ],
      },
      fieldMappings: [{ source: 'ID', target: 'visit_id' }],
    });
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        source={{ connectionId: 'source', table }}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ }),
      '_ods'
    );
    await userEvent.type(
      screen.getAllByRole('textbox', { name: /label.description/ })[0],
      'Table draft'
    );
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', { name: 'hospitalTableDesign.importSource' })
      );
    });

    expect(mockApi.previewIntegrationTargetTable).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ })
    ).toHaveValue('visits_ods');
    expect(
      screen.getAllByRole('textbox', { name: /label.description/ })[0]
    ).toHaveValue('Table draft');
    expect(screen.getByRole('textbox', { name: 'label.name 1' })).toHaveValue(
      'visit_id'
    );
  });

  it('blocks duplicate create and close while the mutation result is pending', async () => {
    let resolve: ((value: api.DorisTableMutationResult) => void) | undefined;
    mockApi.createDesignedDorisTable.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        })
    );
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ }),
      'visits'
    );
    await previewClick();
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.confirmCreateTargetTable',
        })
      );
    });

    expect(screen.getByRole('button', { name: 'label.close' })).toBeDisabled();
    expect(
      screen.getByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).toBeDisabled();
    expect(mockApi.createDesignedDorisTable).toHaveBeenCalledTimes(1);
    expect(close).not.toHaveBeenCalled();

    await act(async () =>
      resolve?.({ status: 'APPLIED', structure: dorisStructure })
    );

    expect(applied).toHaveBeenCalledTimes(1);
  });

  it('refreshes actual structure after an uncertain result without repeating CREATE', async () => {
    mockApi.createDesignedDorisTable.mockRejectedValue({
      isAxiosError: true,
      message: 'network test fixture',
    });
    mockApi.getDorisTableStructure.mockResolvedValue(dorisStructure);
    render(
      <DorisTableDesigner
        connection={dorisConnection}
        onApplied={applied}
        onClose={close}
      />
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /hospitalIntegration.targetTable/ }),
      'visits'
    );
    await previewClick();
    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalIntegration.confirmCreateTargetTable',
        })
      );
    });

    expect(screen.getByText('hospitalTableDesign.uncertain')).toBeVisible();
    expect(
      screen.queryByRole('button', {
        name: 'hospitalIntegration.confirmCreateTargetTable',
      })
    ).not.toBeInTheDocument();

    await act(async () => {
      await userEvent.click(
        screen.getByRole('button', {
          name: 'hospitalTableDesign.refreshStructure',
        })
      );
    });

    expect(mockApi.createDesignedDorisTable).toHaveBeenCalledTimes(1);
    expect(applied).toHaveBeenCalledWith(dorisStructure, []);
  });
});
