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
import {
  getIntegrationFailure,
  IntegrationRun,
  IntegrationTable,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';
import {
  canResumeIntegrationTask,
  changeIntegrationDatabaseType,
  emptyIntegrationConnection,
  EMPTY_INTEGRATION_TASK,
  integrationErrorKey,
  isIntegrationRunBusy,
  isIntegrationRunUncertain,
  suggestIntegrationIdentifier,
  toIntegrationConnectionInput,
  toIntegrationTaskInput,
  validateIntegrationConnectionInput,
  validateIntegrationInput,
} from './HospitalIntegrationUtils';

const source: IntegrationTable = {
  schema: 'public',
  name: 'encounter',
  columns: [
    { name: 'id', dataType: 'bigint', nullable: false, primaryKey: true },
    { name: 'value', dataType: 'varchar', nullable: true, primaryKey: false },
  ],
};
const target: IntegrationTable = { ...source, schema: 'ods' };
const input: IntegrationTaskInput = {
  ...EMPTY_INTEGRATION_TASK,
  name: 'encounter_cdc',
  displayName: 'Synthetic encounter',
  sourceSchema: source.schema,
  sourceTable: source.name,
  targetSchema: target.schema,
  targetTable: target.name,
  mode: 'CDC',
  primaryKey: 'id',
  fieldMappings: [{ source: 'id', target: 'id' }],
};

describe('Hospital integration validation and run safety', () => {
  const sqlSource: IntegrationTable = {
    ...source,
    name: 'sql_result',
    columns: source.columns.map((column) => ({ ...column, primaryKey: false })),
  };
  const sqlInput: IntegrationTaskInput = {
    ...input,
    mode: 'SQL_T1',
    sourceTable: 'sql_result',
    sourceQuery: 'SELECT id, value FROM public.encounter WHERE day = :biz_date',
    schedule: { enabled: false, dailyTime: '02:00', timeZone: 'Asia/Shanghai' },
  };

  it('accepts an inspected SQL alias as the merge key and requires it to be mapped', () => {
    expect(validateIntegrationInput(sqlInput, [sqlSource], [target])).toEqual(
      []
    );
    expect(
      validateIntegrationInput(
        { ...sqlInput, primaryKey: 'missing' },
        [sqlSource],
        [target]
      )
    ).toContain('sqlPrimaryKeyRequired');
    expect(
      validateIntegrationInput(
        { ...sqlInput, primaryKey: 'value' },
        [sqlSource],
        [target]
      )
    ).toContain('sqlPrimaryKeyRequired');
    expect(
      validateIntegrationInput(
        { ...sqlInput, fieldMappings: [] },
        [sqlSource],
        [target]
      )
    ).toContain('sqlPrimaryKeyRequired');
  });

  it('requires fresh SQL columns, bounded query text and a valid daily schedule', () => {
    expect(validateIntegrationInput(sqlInput, [], [target])).toContain(
      'queryColumnsRequired'
    );
    expect(
      validateIntegrationInput(
        { ...sqlInput, sourceQuery: '' },
        [sqlSource],
        [target]
      )
    ).toContain('sourceQueryRequired');
    expect(
      validateIntegrationInput(
        {
          ...sqlInput,
          schedule: {
            enabled: true,
            dailyTime: '25:00',
            timeZone: 'Asia/Shanghai',
          },
        },
        [sqlSource],
        [target]
      )
    ).toContain('scheduleInvalid');
  });

  it('retains SQL and schedule when opening an existing task for edit', () => {
    const task = {
      ...sqlInput,
      id: 'sql-task',
      version: 1,
      createdAt: 1,
      updatedAt: 1,
      updatedBy: 'synthetic-admin',
      runs: [],
      catalog: { status: 'PENDING' as const },
    };
    const edited = toIntegrationTaskInput(task);

    expect(edited.sourceQuery).toBe(sqlInput.sourceQuery);
    expect(edited.schedule).toEqual(sqlInput.schedule);
    expect(edited.schedule).not.toBe(task.schedule);
  });

  it('keeps ambiguous failures blocked but permits retry after an explicit engine rejection', () => {
    expect(
      getIntegrationFailure({
        isAxiosError: true,
        response: { status: 503, data: { errorCode: 'ENGINE_UNAVAILABLE' } },
      }).uncertain
    ).toBe(false);
    expect(
      getIntegrationFailure({
        isAxiosError: true,
        response: { status: 502, data: null },
      }).uncertain
    ).toBe(true);
    expect(getIntegrationFailure({ isAxiosError: true }).uncertain).toBe(true);
    expect(
      getIntegrationFailure({
        isAxiosError: true,
        response: { status: 409, data: {} },
      }).conflict
    ).toBe(true);
  });

  it('rejects unavailable columns and duplicate target mappings', () => {
    const errors = validateIntegrationInput(
      {
        ...input,
        fieldMappings: [
          { source: 'id', target: 'id' },
          { source: 'missing', target: 'id' },
        ],
      },
      [source],
      [target]
    );

    expect(errors).toContain('mappingRequired');
  });

  it('requires the actual source primary key to be mapped for CDC', () => {
    expect(
      validateIntegrationInput(
        { ...input, primaryKey: 'value' },
        [source],
        [target]
      )
    ).toContain('primaryKeyRequired');
    expect(
      validateIntegrationInput(
        { ...input, fieldMappings: [{ source: 'value', target: 'value' }] },
        [source],
        [target]
      )
    ).toContain('primaryKeyRequired');
    expect(validateIntegrationInput(input, [source], [target])).toEqual([]);
  });

  it('treats new or unknown engine states as busy and uncertain', () => {
    const run: IntegrationRun = {
      jobId: '9223372036854775807',
      status: 'UNRECOGNIZED',
      submittedAt: 1,
    };

    expect(isIntegrationRunBusy(run)).toBe(true);
    expect(isIntegrationRunUncertain(run)).toBe(true);
    expect(canResumeIntegrationTask('CDC', { ...run, canResume: true })).toBe(
      false
    );
  });

  it('allows confirmed SAVEPOINT_DONE resume only for CDC', () => {
    const run: IntegrationRun = {
      jobId: '9223372036854775807',
      status: 'SAVEPOINT_DONE',
      submittedAt: 1,
      canResume: true,
    };

    expect(isIntegrationRunBusy(run)).toBe(false);
    expect(canResumeIntegrationTask('CDC', run)).toBe(true);
    expect(canResumeIntegrationTask('FULL', run)).toBe(false);
    expect(canResumeIntegrationTask('CDC', { ...run, canResume: false })).toBe(
      false
    );
  });

  it('matches the server lowercase technical-name boundary', () => {
    for (const name of ['UpperCase', 'a'.repeat(64), '1invalid']) {
      expect(
        validateIntegrationInput({ ...input, name }, [source], [target])
      ).toContain('invalidName');
    }

    expect(
      validateIntegrationInput(
        { ...input, name: 'a'.repeat(63) },
        [source],
        [target]
      )
    ).not.toContain('invalidName');
  });

  it('defaults to verified TLS and requires a real version, credentials, and bounded schema scope', () => {
    const value = {
      ...emptyIntegrationConnection(),
      name: 'synthetic_source',
      displayName: 'Synthetic source',
      host: 'example.invalid',
      database: 'synthetic',
      username: 'reader',
      password: 'synthetic-test-password',
    };

    expect(value.tlsMode).toBe('VERIFY');
    expect(validateIntegrationConnectionInput(value, false)).toContain(
      'databaseVersionRequired'
    );

    const valid = { ...value, databaseVersion: '17.2' };

    expect(validateIntegrationConnectionInput(valid, false)).toEqual([]);
    expect(
      validateIntegrationConnectionInput({ ...valid, password: '' }, false)
    ).toContain('passwordRequired');
    expect(
      validateIntegrationConnectionInput({ ...valid, password: '' }, true)
    ).not.toContain('passwordRequired');
    expect(
      validateIntegrationConnectionInput({ ...valid, schemas: [] }, true)
    ).toContain('schemasInvalid');
    expect(
      validateIntegrationConnectionInput(
        {
          ...valid,
          schemas: Array.from({ length: 9 }, (_, index) => 'scope_' + index),
        },
        true
      )
    ).toContain('schemasInvalid');
    expect(
      validateIntegrationConnectionInput(
        { ...valid, schemas: ['public', 'public'] },
        true
      )
    ).toContain('schemasInvalid');
    expect(
      validateIntegrationConnectionInput(
        { ...valid, databaseType: 'Mysql' },
        true
      )
    ).toContain('mysqlScopeHint');
  });

  it('validates Doris target role, HTTP endpoint and database scope', () => {
    const value = {
      ...changeIntegrationDatabaseType(emptyIntegrationConnection(), 'Doris'),
      name: 'doris_target',
      displayName: 'Doris target',
      databaseVersion: '3.1.3',
      host: 'doris.example.invalid',
      database: 'hospital_ods',
      schemas: ['hospital_ods'],
      username: 'writer',
      password: 'synthetic-password',
      role: 'TARGET' as const,
    };

    expect(validateIntegrationConnectionInput(value, false)).toEqual([]);
    expect(
      validateIntegrationConnectionInput({ ...value, role: 'SOURCE' }, false)
    ).toContain('dorisTargetOnly');

    for (const dorisHttpPort of [undefined, 0, 65536, 8030.5]) {
      expect(
        validateIntegrationConnectionInput({ ...value, dorisHttpPort }, false)
      ).toContain('dorisHttpPortInvalid');
    }

    expect(
      validateIntegrationConnectionInput(
        { ...value, schemas: ['public'] },
        false
      )
    ).toContain('dorisScopeHint');
    expect(
      validateIntegrationConnectionInput({ ...value, tlsMode: 'VERIFY' }, false)
    ).toContain('dorisTlsHint');
    expect(value).toMatchObject({
      port: 9030,
      dorisHttpPort: 8030,
      tlsMode: 'DISABLED',
    });

    const oracle = changeIntegrationDatabaseType(value, 'Oracle');

    expect(oracle).not.toHaveProperty('dorisHttpPort');
    expect(oracle).toMatchObject({
      port: 1521,
      oracleConnectionType: 'SERVICE_NAME',
    });
    expect(changeIntegrationDatabaseType(oracle, 'Doris')).not.toHaveProperty(
      'oracleConnectionType'
    );

    const saved = toIntegrationConnectionInput({
      ...value,
      id: 'doris',
      version: 1,
      managed: false,
      synthetic: true,
      passwordSet: true,
      supportedModes: ['FULL'],
    });

    expect(saved.dorisHttpPort).toBe(8030);
  });

  it('suggests bounded lowercase ASCII identifiers for real source names', () => {
    expect(suggestIntegrationIdentifier('PATIENT-Records')).toBe(
      'patient_records'
    );
    expect(suggestIntegrationIdentifier('123 ID')).toBe('target_123_id');
    expect(suggestIntegrationIdentifier('患者')).toMatch(
      /^[a-z][a-z0-9_]{0,63}$/
    );
    expect(suggestIntegrationIdentifier('X'.repeat(90))).toHaveLength(64);
  });

  it('classifies an invalid Doris label as an explicit pre-submission rejection with dedicated recovery copy', () => {
    expect(integrationErrorKey('DORIS_LABEL_INVALID')).toBe(
      'hospitalIntegration.errors.DORIS_LABEL_INVALID'
    );
    expect(
      getIntegrationFailure({
        isAxiosError: true,
        response: { status: 503, data: { errorCode: 'DORIS_LABEL_INVALID' } },
      })
    ).toEqual({
      code: 'DORIS_LABEL_INVALID',
      conflict: false,
      uncertain: false,
    });
  });
});
