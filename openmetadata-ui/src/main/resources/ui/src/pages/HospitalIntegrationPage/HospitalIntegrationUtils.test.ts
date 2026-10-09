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
  EMPTY_INTEGRATION_TASK,
  isIntegrationRunBusy,
  isIntegrationRunUncertain,
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
});
