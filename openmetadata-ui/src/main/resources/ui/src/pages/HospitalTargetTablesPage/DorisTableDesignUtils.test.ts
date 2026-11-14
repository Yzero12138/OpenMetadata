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
import { DorisColumnInput } from '../../rest/hospitalIntegrationAPI';
import {
  columnsFromTargetPreview,
  createDorisTableDraft,
  dorisColumnsMatch,
  dorisDesignErrorKey,
  newDorisColumnDraft,
  serializeDorisDesign,
  validateDorisDesign,
} from './DorisTableDesignUtils';

describe('Doris target design drafts', () => {
  it.each(['FLOAT', 'DOUBLE'])(
    'matches equivalent %s defaults in exponent and plain forms',
    (type) => {
      const expected: DorisColumnInput = {
        name: 'amount',
        type,
        nullable: true,
        primaryKey: false,
        defaultKind: 'LITERAL',
        defaultValue: '1.0E7',
        comment: '',
      };

      expect(
        dorisColumnsMatch({ ...expected, defaultValue: '10000000' }, expected)
      ).toBe(true);
      expect(
        dorisColumnsMatch({ ...expected, defaultValue: '1e+07' }, expected)
      ).toBe(true);
      expect(
        dorisColumnsMatch({ ...expected, defaultValue: '10000001' }, expected)
      ).toBe(false);
    }
  );

  it.each(['BIGINT', 'DECIMAL'])(
    'preserves exact %s default digits above JavaScript safe integer precision',
    (type) => {
      const expected: DorisColumnInput = {
        name: 'amount',
        type,
        nullable: true,
        primaryKey: false,
        defaultKind: 'LITERAL',
        defaultValue: '9007199254740993',
        comment: '',
      };

      expect(
        dorisColumnsMatch(
          { ...expected, defaultValue: '+09007199254740993.00' },
          expected
        )
      ).toBe(true);
      expect(
        dorisColumnsMatch(
          { ...expected, defaultValue: '9007199254740992' },
          expected
        )
      ).toBe(false);
    }
  );

  it('serializes only structured DDL inputs and applicable type parameters', () => {
    const draft = createDorisTableDraft('ods_validation');
    draft.targetTable = 'visits';
    draft.columns.push({
      ...newDorisColumnDraft('amount'),
      name: 'amount',
      type: 'DECIMAL',
      precision: '12',
      scale: '2',
      defaultKind: 'LITERAL',
      defaultValue: '0.00',
    });
    const input = serializeDorisDesign(draft);

    expect(validateDorisDesign(draft).errors).toEqual([]);
    expect(input.columns[0]).toEqual({
      name: 'id',
      type: 'BIGINT',
      nullable: false,
      primaryKey: true,
      defaultKind: 'NONE',
      comment: '',
    });
    expect(input.columns[1]).toEqual({
      name: 'amount',
      type: 'DECIMAL',
      precision: 12,
      scale: 2,
      nullable: true,
      primaryKey: false,
      defaultKind: 'LITERAL',
      defaultValue: '0.00',
      comment: '',
    });
    expect(JSON.stringify(input)).not.toContain('rowId');
  });

  it('requires a real key and rejects case-insensitive duplicate names', () => {
    const draft = createDorisTableDraft('ods_validation');
    draft.targetTable = 'visits';
    draft.columns[0].primaryKey = false;
    draft.columns.push({ ...newDorisColumnDraft('duplicate'), name: 'ID' });

    expect(validateDorisDesign(draft).errors).toEqual(
      expect.arrayContaining([
        'hospitalTableDesign.keyRequired',
        'hospitalTableDesign.invalidNames',
      ])
    );
  });

  it('requires non-null defaults on appended required fields and preserves blank literal values', () => {
    const draft = createDorisTableDraft('ods_validation', 'visits', true);
    draft.columns[0] = {
      ...newDorisColumnDraft('new'),
      name: 'note',
      nullable: false,
    };

    expect(validateDorisDesign(draft, true).errors).toContain(
      'hospitalTableDesign.defaultRequired'
    );

    draft.columns[0].defaultKind = 'LITERAL';
    draft.columns[0].defaultValue = '';

    expect(validateDorisDesign(draft, true).errors).toEqual([]);
    expect(serializeDorisDesign(draft).columns[0].defaultValue).toBe('');
  });

  it.each([
    { type: 'VARCHAR' as const, length: '65534' },
    { type: 'CHAR' as const, length: '256' },
    { type: 'DECIMAL' as const, precision: '39', scale: '2' },
    { type: 'DECIMAL' as const, precision: '12', scale: '13' },
    { type: 'DATETIME' as const, scale: '7' },
  ])('rejects invalid type settings: $type', (settings) => {
    const draft = createDorisTableDraft('ods_validation');
    draft.targetTable = 'visits';
    draft.columns.push({
      ...newDorisColumnDraft('new'),
      name: 'value',
      ...settings,
    });

    expect(validateDorisDesign(draft).errors).toContain(
      'hospitalTableDesign.invalidTypeSettings'
    );
  });

  it('imports lossless types from a live source-derived target preview', () => {
    const columns = columnsFromTargetPreview({
      schema: 'ods_validation',
      name: 'visits',
      columns: [
        {
          name: 'visit_id',
          dataType: 'BIGINT',
          nullable: false,
          primaryKey: true,
        },
        {
          name: 'name',
          dataType: 'VARCHAR(256)',
          nullable: true,
          primaryKey: false,
        },
        {
          name: 'amount',
          dataType: 'DECIMAL(12,2)',
          nullable: true,
          primaryKey: false,
        },
        {
          name: 'updated_at',
          dataType: 'DATETIMEV2(6)',
          nullable: false,
          primaryKey: false,
        },
      ],
    });

    expect(columns.map((c) => c.type)).toEqual([
      'BIGINT',
      'VARCHAR',
      'DECIMAL',
      'DATETIME',
    ]);
    expect(columns[1].length).toBe('256');
    expect(columns[2].precision).toBe('12');
    expect(columns[2].scale).toBe('2');
    expect(columns[3].scale).toBe('6');
  });

  it('maps target ALTER permission failures without exposing server internals', () => {
    expect(dorisDesignErrorKey('TARGET_TABLE_PERMISSION_DENIED')).toBe(
      'hospitalTableDesign.errors.permission'
    );
    expect(dorisDesignErrorKey('TABLE_PREVIEW_STALE')).toBe(
      'hospitalIntegration.errors.TABLE_PREVIEW_STALE'
    );
  });

  it('rejects column comments the pinned Doris parser cannot preserve', () => {
    const draft = createDorisTableDraft('ods_validation', 'visits');
    draft.columns[0].comment = 'source\\field';

    expect(validateDorisDesign(draft).errors).toContain(
      'hospitalTableDesign.commentBackslash'
    );
  });
});
