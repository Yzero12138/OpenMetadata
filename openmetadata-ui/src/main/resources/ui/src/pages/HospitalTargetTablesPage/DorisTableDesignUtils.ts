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
  DorisColumnInput,
  DorisColumnType,
  DorisTableDesignInput,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';
import { integrationErrorKey } from '../HospitalIntegrationPage/HospitalIntegrationUtils';
import {
  DorisColumnDraft,
  DorisTableDraft,
  DorisTaskTarget,
} from './HospitalTargetTablesPage.interface';

export const DORIS_COLUMN_TYPES: DorisColumnType[] = [
  'BIGINT',
  'INT',
  'SMALLINT',
  'TINYINT',
  'DECIMAL',
  'VARCHAR',
  'CHAR',
  'STRING',
  'DATE',
  'DATETIME',
  'BOOLEAN',
  'FLOAT',
  'DOUBLE',
];
const identifier = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const positiveInteger = (value: string, min: number, max: number) =>
  /^\d+$/.test(value) && Number(value) >= min && Number(value) <= max;

export const newDorisColumnDraft = (rowId: string): DorisColumnDraft => ({
  rowId,
  name: '',
  type: 'VARCHAR',
  length: '256',
  precision: '18',
  scale: '2',
  nullable: true,
  primaryKey: false,
  defaultKind: 'NONE',
  defaultValue: '',
  comment: '',
});

export const createDorisTableDraft = (
  targetSchema: string,
  targetTable = '',
  append = false
): DorisTableDraft => ({
  targetSchema,
  targetTable,
  comment: '',
  buckets: '1',
  columns: [
    {
      ...newDorisColumnDraft('initial-field'),
      ...(append
        ? {}
        : { name: 'id', type: 'BIGINT', nullable: false, primaryKey: true }),
    },
  ],
});

export const changeDorisColumnType = (
  column: DorisColumnDraft,
  type: DorisColumnType
): DorisColumnDraft => ({
  ...column,
  type,
  length: type === 'CHAR' ? '32' : '256',
  precision: '18',
  scale: type === 'DATETIME' ? '0' : '2',
  primaryKey:
    !['FLOAT', 'DOUBLE', 'STRING'].includes(type) && column.primaryKey,
  defaultKind:
    (column.defaultKind === 'CURRENT_DATE' && type !== 'DATE') ||
    (column.defaultKind === 'CURRENT_TIMESTAMP' && type !== 'DATETIME')
      ? 'NONE'
      : column.defaultKind,
});

export const serializeDorisColumn = (
  column: DorisColumnDraft
): DorisColumnInput => ({
  name: column.name.trim(),
  type: column.type,
  ...(['VARCHAR', 'CHAR'].includes(column.type)
    ? { length: Number(column.length) }
    : {}),
  ...(column.type === 'DECIMAL'
    ? { precision: Number(column.precision), scale: Number(column.scale) }
    : {}),
  ...(column.type === 'DATETIME' ? { scale: Number(column.scale) } : {}),
  nullable: column.nullable,
  primaryKey: column.primaryKey,
  defaultKind: column.defaultKind,
  ...(column.defaultKind === 'LITERAL'
    ? { defaultValue: column.defaultValue }
    : {}),
  comment: column.comment,
});

export const serializeDorisDesign = (
  draft: DorisTableDraft
): DorisTableDesignInput => ({
  targetSchema: draft.targetSchema,
  targetTable: draft.targetTable.trim(),
  comment: draft.comment,
  buckets: Number(draft.buckets),
  columns: draft.columns.map(serializeDorisColumn),
});

export const validateDorisDesign = (
  draft: DorisTableDraft,
  append = false,
  existingColumns: string[] = []
): { errors: string[]; fields: Record<string, string[]> } => {
  const errors = new Set<string>();
  const fields: Record<string, string[]> = {};
  const add = (key: string, rowId?: string) => {
    errors.add(key);
    if (rowId) {
      fields[rowId] = [...(fields[rowId] ?? []), key];
    }
  };
  if (
    !identifier.test(draft.targetTable.trim()) ||
    draft.targetSchema.length + draft.targetTable.trim().length > 67
  ) {
    add('hospitalTableDesign.invalidNames');
  }
  if (!positiveInteger(draft.buckets, 1, 128)) {
    add('hospitalTableDesign.invalidTypeSettings');
  }
  if (new TextEncoder().encode(draft.comment).length > 1024) {
    add('hospitalTableDesign.textTooLong');
  }
  if (
    !draft.columns.length ||
    draft.columns.length + existingColumns.length > 128
  ) {
    add('hospitalTableDesign.fieldLimit');
  }
  const names = new Set(existingColumns.map((name) => name.toLowerCase()));
  let keys = 0;
  for (const column of draft.columns) {
    if (column.comment.includes('\\')) {
      add('hospitalTableDesign.commentBackslash', column.rowId);
    }
    if (
      new TextEncoder().encode(column.comment).length > 1024 ||
      (column.defaultKind === 'LITERAL' &&
        new TextEncoder().encode(column.defaultValue).length > 4096)
    ) {
      add('hospitalTableDesign.textTooLong', column.rowId);
    }
    const name = column.name.trim().toLowerCase();
    if (!identifier.test(column.name.trim()) || names.has(name)) {
      add('hospitalTableDesign.invalidNames', column.rowId);
    }
    names.add(name);
    if (
      !DORIS_COLUMN_TYPES.includes(column.type) ||
      (column.type === 'VARCHAR' &&
        !positiveInteger(column.length, 1, 65533)) ||
      (column.type === 'CHAR' && !positiveInteger(column.length, 1, 255)) ||
      (column.type === 'DECIMAL' &&
        (!positiveInteger(column.precision, 1, 38) ||
          !positiveInteger(column.scale, 0, Number(column.precision)))) ||
      (column.type === 'DATETIME' && !positiveInteger(column.scale, 0, 6))
    ) {
      add('hospitalTableDesign.invalidTypeSettings', column.rowId);
    }
    if (column.primaryKey) {
      keys += 1;
      if (
        column.nullable ||
        ['FLOAT', 'DOUBLE', 'STRING'].includes(column.type) ||
        append
      ) {
        add('hospitalTableDesign.keyRequired', column.rowId);
      }
    }
    if (column.defaultKind === 'NULL' && !column.nullable) {
      add('hospitalTableDesign.invalidDefault', column.rowId);
    }
    if (
      (column.defaultKind === 'CURRENT_DATE' && column.type !== 'DATE') ||
      (column.defaultKind === 'CURRENT_TIMESTAMP' && column.type !== 'DATETIME')
    ) {
      add('hospitalTableDesign.invalidDefault', column.rowId);
    }
    if (
      append &&
      !column.nullable &&
      ['NONE', 'NULL'].includes(column.defaultKind)
    ) {
      add('hospitalTableDesign.defaultRequired', column.rowId);
    }
  }
  if (!append && keys !== 1) {
    add('hospitalTableDesign.keyRequired');
  }

  return { errors: [...errors], fields };
};

export const columnsFromTargetPreview = (
  table: IntegrationTable
): DorisColumnDraft[] =>
  table.columns.map((column, index) => {
    const match =
      /^([A-Z][A-Z0-9]*)(?:\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\))?$/.exec(
        column.dataType.toUpperCase()
      );
    const normalized = (match?.[1] ?? '').replace(/V[23]$/, '');
    const type = normalized === 'INTEGER' ? 'INT' : normalized;
    if (!DORIS_COLUMN_TYPES.some((item) => item === type)) {
      throw new Error('UNSUPPORTED_TARGET_TYPE');
    }
    const acceptedType =
      DORIS_COLUMN_TYPES.find((item) => item === type) ?? 'VARCHAR';

    return {
      ...newDorisColumnDraft(`imported-${index}`),
      name: column.name,
      type: acceptedType,
      nullable: column.nullable,
      primaryKey: column.primaryKey,
      ...(['VARCHAR', 'CHAR'].includes(type)
        ? { length: match?.[2] ?? '' }
        : {}),
      ...(type === 'DECIMAL'
        ? { precision: match?.[2] ?? '', scale: match?.[3] ?? '0' }
        : {}),
      ...(type === 'DATETIME' ? { scale: match?.[2] ?? '0' } : {}),
    };
  });

export const dorisDesignErrorKey = (code?: string): string => {
  const keys: Record<string, string> = {
    INVALID_TABLE_DESIGN: 'definition',
    COLUMN_ALREADY_EXISTS: 'columnExists',
    TABLE_COLUMN_LIMIT_EXCEEDED: 'fieldLimit',
    TARGET_TABLE_MODEL_UNSUPPORTED: 'model',
    TARGET_TABLE_ACTIVE: 'activeTask',
    TARGET_SCHEMA_CHANGE_ACTIVE: 'activeChange',
    TARGET_TABLE_PERMISSION_DENIED: 'permission',
    TARGET_TABLE_STRUCTURE_UNAVAILABLE: 'structure',
    TARGET_TABLE_ALTER_FAILED: 'alter',
  };

  return code && keys[code]
    ? `hospitalTableDesign.errors.${keys[code]}`
    : integrationErrorKey(code);
};

export const dorisChangeActive = (state?: string): boolean =>
  Boolean(
    state && !['FINISHED', 'CANCELLED', 'CANCELED', 'FAILED'].includes(state)
  );

const normalizedDorisDefault = (column: DorisColumnInput): string => {
  if (column.defaultKind !== 'LITERAL') {
    return column.nullable && ['NONE', 'NULL'].includes(column.defaultKind)
      ? 'NULL'
      : column.defaultKind;
  }
  const value = column.defaultValue ?? '';
  if (
    ['FLOAT', 'DOUBLE'].includes(column.type) &&
    value.trim() &&
    Number.isFinite(Number(value))
  ) {
    return String(Number(value));
  }
  if (column.type === 'BOOLEAN') {
    return ['true', '1'].includes(value.toLowerCase())
      ? 'true'
      : ['false', '0'].includes(value.toLowerCase())
      ? 'false'
      : value;
  }
  if (
    [
      'TINYINT',
      'SMALLINT',
      'INT',
      'BIGINT',
      'DECIMAL',
      'FLOAT',
      'DOUBLE',
    ].includes(column.type) &&
    /^[+-]?\d+(?:\.\d+)?$/.test(value)
  ) {
    const [integer, fraction = ''] = value.replace(/^[+-]/, '').split('.');
    const whole = integer.replace(/^0+(?=\d)/, '');
    const decimals = fraction.replace(/0+$/, '');
    const number = whole + (decimals ? `.${decimals}` : '');

    return value.startsWith('-') && number !== '0' ? `-${number}` : number;
  }
  if (column.type === 'DATETIME') {
    return value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  }

  return value;
};

export const dorisColumnsMatch = (
  actual: DorisColumnInput,
  expected: DorisColumnInput
): boolean =>
  actual.name === expected.name &&
  actual.type === expected.type &&
  actual.nullable === expected.nullable &&
  actual.primaryKey === expected.primaryKey &&
  (actual.length ?? null) === (expected.length ?? null) &&
  (actual.precision ?? null) === (expected.precision ?? null) &&
  (actual.scale ?? 0) === (expected.scale ?? 0) &&
  (actual.comment ?? '') === (expected.comment ?? '') &&
  normalizedDorisDefault(actual) === normalizedDorisDefault(expected) &&
  (actual.defaultKind === 'LITERAL') === (expected.defaultKind === 'LITERAL');

export const readDorisTaskTarget = (
  state: unknown
): DorisTaskTarget | undefined => {
  if (!state || typeof state !== 'object' || !('dorisTarget' in state)) {
    return;
  }
  const value = state.dorisTarget;
  if (
    !value ||
    typeof value !== 'object' ||
    !('connectionId' in value) ||
    !('schema' in value) ||
    !('table' in value) ||
    typeof value.connectionId !== 'string' ||
    !value.connectionId ||
    value.connectionId.length > 64 ||
    typeof value.schema !== 'string' ||
    !identifier.test(value.schema) ||
    typeof value.table !== 'string' ||
    !identifier.test(value.table) ||
    value.schema.length + value.table.length > 67
  ) {
    return;
  }

  return {
    connectionId: value.connectionId,
    schema: value.schema,
    table: value.table,
  };
};
