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
import APIClient from '.';
import {
  createIntegrationConnection,
  createIntegrationTargetTable,
  deleteIntegrationConnection,
  getIntegrationConnection,
  getIntegrationQuerySchema,
  getIntegrationTable,
  getIntegrationTableOptions,
  IntegrationConnectionInput,
  previewIntegrationTargetTable,
  runIntegrationTask,
  updateIntegrationConnection,
  updateIntegrationInitialization,
} from './hospitalIntegrationAPI';

jest.mock('.', () => ({
  get: jest.fn(),
  post: jest.fn(),
  put: jest.fn(),
  delete: jest.fn(),
}));
const client = APIClient as jest.Mocked<typeof APIClient>;
const input: IntegrationConnectionInput = {
  name: 'synthetic_source',
  displayName: 'Synthetic source',
  role: 'SOURCE',
  databaseType: 'Postgres',
  databaseVersion: '17.2',
  host: 'example.invalid',
  port: 5432,
  database: 'synthetic',
  username: 'synthetic_reader',
  password: 'synthetic-test-password',
  schemas: ['public'],
  tlsMode: 'VERIFY',
  enabled: true,
};

describe('Hospital managed connection HTTP boundary', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client.get.mockResolvedValue({ data: { data: [] } });
    client.post.mockResolvedValue({ data: { id: 'new' } });
    client.put.mockResolvedValue({ data: { id: 'saved' } });
    client.delete.mockResolvedValue({});
  });

  it('creates a structured definition without testing connectivity implicitly', async () => {
    await expect(createIntegrationConnection(input)).resolves.toEqual({
      id: 'new',
    });
    expect(client.post).toHaveBeenCalledTimes(1);
    expect(client.post).toHaveBeenCalledWith(
      '/hospital/integration/connections',
      input
    );
  });

  it.each([undefined, ''])(
    'omits unchanged password %s while retaining the original version',
    async (password) => {
      await updateIntegrationConnection('source/id', { ...input, password }, 7);
      const body = client.put.mock.calls[0][1];

      expect(body).not.toHaveProperty('password');
      expect(body).toMatchObject({ name: input.name, version: 7 });
      expect(client.put.mock.calls[0][0]).toBe(
        '/hospital/integration/connections/source%2Fid'
      );
    }
  );

  it('sends an explicitly changed password and version once', async () => {
    await updateIntegrationConnection('source', input, 8);

    expect(client.put).toHaveBeenCalledWith(
      '/hospital/integration/connections/source',
      { ...input, version: 8 }
    );
  });

  it('deletes only the requested version of the encoded identifier', async () => {
    await deleteIntegrationConnection('source/id', 3);

    expect(client.delete).toHaveBeenCalledWith(
      '/hospital/integration/connections/source%2Fid',
      { params: { version: 3 } }
    );
  });

  it('passes cancellation through definition, names-only and selected-column discovery', async () => {
    const controller = new AbortController();
    await getIntegrationConnection('source/id', controller.signal);
    await getIntegrationTableOptions('source/id', controller.signal);
    await getIntegrationTable(
      'source/id',
      'schema name',
      'table/name',
      controller.signal
    );

    expect(client.get.mock.calls).toEqual([
      [
        '/hospital/integration/connections/source%2Fid',
        { signal: controller.signal },
      ],
      [
        '/hospital/integration/connections/source%2Fid/table-options',
        { signal: controller.signal },
      ],
      [
        '/hospital/integration/connections/source%2Fid/tables/schema%20name/table%2Fname',
        { signal: controller.signal },
      ],
    ]);
  });

  it('previews and creates a target table through structured metadata and a server preview hash', async () => {
    const definition = {
      sourceConnectionId: 'source',
      sourceSchema: 'public',
      sourceTable: 'patient',
      targetSchema: 'hospital_ods',
      targetTable: 'patient',
      fieldMappings: [{ source: 'PATIENT_ID', target: 'patient_id' }],
    };
    const controller = new AbortController();
    await previewIntegrationTargetTable(
      'doris/id',
      definition,
      controller.signal
    );
    await createIntegrationTargetTable('doris/id', {
      ...definition,
      previewHash: 'server-generated-hash',
    });

    expect(client.post.mock.calls).toEqual([
      [
        '/hospital/integration/connections/doris%2Fid/tables/preview',
        definition,
        { signal: controller.signal },
      ],
      [
        '/hospital/integration/connections/doris%2Fid/tables',
        { ...definition, previewHash: 'server-generated-hash' },
      ],
    ]);
    expect(client.post.mock.calls[1][1]).not.toHaveProperty('ddl');
  });

  it('inspects SQL columns explicitly without requesting source rows', async () => {
    const definition = {
      connectionId: 'source/id',
      schema: 'public',
      query: 'SELECT id FROM public.synthetic_visits WHERE day = :biz_date',
    };
    const controller = new AbortController();
    await getIntegrationQuerySchema(definition, controller.signal);

    expect(client.post).toHaveBeenCalledWith(
      '/hospital/integration/query/schema',
      definition,
      { signal: controller.signal }
    );
  });

  it('preserves legacy runs and sends an explicit business date for SQL backfill and retry', async () => {
    await runIntegrationTask('task/id');
    await runIntegrationTask('task/id', false, { businessDate: '2026-11-10' });
    await runIntegrationTask('task/id', false, {
      businessDate: '2026-11-10',
      retry: true,
    });

    expect(client.post.mock.calls).toEqual([
      ['/hospital/integration/tasks/task%2Fid/run', { resume: false }],
      [
        '/hospital/integration/tasks/task%2Fid/run',
        { resume: false, businessDate: '2026-11-10' },
      ],
      [
        '/hospital/integration/tasks/task%2Fid/run',
        { resume: false, businessDate: '2026-11-10', retry: true },
      ],
    ]);
  });

  it('pauses and continues initialization with only the encoded task, action and original CAS version', async () => {
    await updateIntegrationInitialization('task/id', 'PAUSE', 7);
    await updateIntegrationInitialization('task/id', 'CONTINUE', 8);

    expect(client.post.mock.calls).toEqual([
      [
        '/hospital/integration/tasks/task%2Fid/initialization',
        { version: 7, action: 'PAUSE' },
      ],
      [
        '/hospital/integration/tasks/task%2Fid/initialization',
        { version: 8, action: 'CONTINUE' },
      ],
    ]);
  });
});
