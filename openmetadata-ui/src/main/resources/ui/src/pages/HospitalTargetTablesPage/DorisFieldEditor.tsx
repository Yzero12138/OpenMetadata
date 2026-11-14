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
  Button,
  Checkbox,
  Input,
  Select,
} from '@openmetadata/ui-core-components';
import {
  ChevronDown,
  ChevronUp,
  Copy01,
  Plus,
  Settings01,
  Trash01,
} from '@untitledui/icons';
import { Fragment, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DorisDefaultKind } from '../../rest/hospitalIntegrationAPI';
import {
  changeDorisColumnType,
  DORIS_COLUMN_TYPES,
  newDorisColumnDraft,
} from './DorisTableDesignUtils';
import {
  DorisColumnDraft,
  DorisFieldEditorProps,
} from './HospitalTargetTablesPage.interface';

export const DorisFieldEditor = ({
  columns,
  append,
  disabled,
  errors,
  onChange,
}: DorisFieldEditorProps) => {
  const { t } = useTranslation();
  const serial = useRef(0);
  const [expanded, setExpanded] = useState<string | undefined>(
    columns[0]?.rowId
  );
  const update = (rowId: string, values: Partial<DorisColumnDraft>) => {
    onChange(
      columns.map((column) =>
        column.rowId === rowId ? { ...column, ...values } : column
      )
    );
  };
  const move = (index: number, delta: number) => {
    const next = [...columns];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    onChange(next);
  };
  const add = () => {
    const column = newDorisColumnDraft(`new-field-${++serial.current}`);
    onChange([...columns, column]);
    setExpanded(column.rowId);
    requestAnimationFrame(() =>
      document.getElementById(`doris-name-${column.rowId}`)?.focus()
    );
  };
  const duplicate = (index: number) => {
    const source = columns[index];
    const used = new Set(columns.map((column) => column.name.toLowerCase()));
    let suffix = 1;
    let name = '';
    do {
      name = source.name.slice(0, 55) + '_' + suffix++;
    } while (used.has(name.toLowerCase()));
    const column = {
      ...source,
      rowId: `new-field-${++serial.current}`,
      name,
      primaryKey: false,
    };
    onChange([
      ...columns.slice(0, index + 1),
      column,
      ...columns.slice(index + 1),
    ]);
    setExpanded(column.rowId);
  };

  return (
    <section
      aria-labelledby="doris-fields-title"
      className="hospital-table-design__fields">
      <div className="hospital-table-design__section-heading">
        <h3 id="doris-fields-title">
          {t(append ? 'hospitalTableDesign.newFields' : 'label.column-plural')}
        </h3>
        <Button
          color="secondary"
          iconLeading={Plus}
          isDisabled={disabled || columns.length >= 128}
          size="sm"
          onClick={add}>
          {t('hospitalTableDesign.addField')}
        </Button>
      </div>
      <p className="hospital-integration__hint">
        {t(
          append
            ? 'hospitalTableDesign.appendHint'
            : 'hospitalTableDesign.keyHint'
        )}
      </p>
      <div className="hospital-table-design__table-scroll">
        <table className="hospital-table-design__editor-table">
          <thead>
            <tr>
              <th scope="col">{t('label.name')}</th>
              <th scope="col">{t('label.type')}</th>
              {!append && <th scope="col">{t('label.primary-key')}</th>}
              <th scope="col">{t('hospitalTableDesign.nullable')}</th>
              <th scope="col">{t('label.action-plural')}</th>
            </tr>
          </thead>
          <tbody>
            {columns.map((column, index) => {
              const open = expanded === column.rowId;
              const settingId = `doris-settings-${column.rowId}`;
              const defaultKinds: DorisDefaultKind[] = [
                'NONE',
                ...(column.nullable ? ['NULL' as const] : []),
                'LITERAL',
                ...(column.type === 'DATE' ? ['CURRENT_DATE' as const] : []),
                ...(column.type === 'DATETIME'
                  ? ['CURRENT_TIMESTAMP' as const]
                  : []),
              ];

              return (
                <Fragment key={column.rowId}>
                  <tr
                    data-invalid={Boolean(errors[column.rowId]?.length)}
                    data-selected={open}>
                    <td>
                      <Input
                        aria-label={`${t('label.name')} ${index + 1}`}
                        id={`doris-name-${column.rowId}`}
                        isDisabled={disabled}
                        isInvalid={Boolean(errors[column.rowId]?.length)}
                        maxLength={64}
                        value={column.name}
                        onChange={(name) => update(column.rowId, { name })}
                      />
                    </td>
                    <td>
                      <Select
                        aria-label={`${t('label.type')} ${index + 1}`}
                        isDisabled={disabled}
                        items={DORIS_COLUMN_TYPES.map((id) => ({
                          id,
                          label: id,
                        }))}
                        selectedKey={column.type}
                        onSelectionChange={(key) => {
                          const type = DORIS_COLUMN_TYPES.find(
                            (item) => item === key
                          );
                          if (type) {
                            update(
                              column.rowId,
                              changeDorisColumnType(column, type)
                            );
                          }
                        }}>
                        {(item) => <Select.Item {...item} />}
                      </Select>
                    </td>
                    {!append && (
                      <td className="hospital-table-design__flag">
                        <input
                          aria-label={`${t('label.primary-key')} ${index + 1}`}
                          checked={column.primaryKey}
                          disabled={
                            disabled ||
                            ['FLOAT', 'DOUBLE', 'STRING'].includes(column.type)
                          }
                          name="doris-primary-key"
                          type="radio"
                          onChange={() =>
                            onChange(
                              columns.map((item) => ({
                                ...item,
                                primaryKey: item.rowId === column.rowId,
                                ...(item.rowId === column.rowId
                                  ? {
                                      nullable: false,
                                      defaultKind:
                                        item.defaultKind === 'NULL'
                                          ? ('NONE' as const)
                                          : item.defaultKind,
                                    }
                                  : {}),
                              }))
                            )
                          }
                        />
                      </td>
                    )}
                    <td className="hospital-table-design__flag">
                      <Checkbox
                        aria-label={`${t('hospitalTableDesign.nullable')} ${
                          index + 1
                        }`}
                        isDisabled={disabled || column.primaryKey}
                        isSelected={column.nullable}
                        onChange={(nullable) =>
                          update(column.rowId, {
                            nullable,
                            ...(!nullable && column.defaultKind === 'NULL'
                              ? { defaultKind: 'NONE' }
                              : {}),
                          })
                        }
                      />
                    </td>
                    <td>
                      <div className="hospital-table-design__row-actions">
                        <Button
                          aria-controls={settingId}
                          aria-expanded={open}
                          aria-label={`${t('label.configure')} ${index + 1}`}
                          color="tertiary"
                          iconLeading={Settings01}
                          isDisabled={disabled}
                          size="sm"
                          onClick={() =>
                            setExpanded(open ? undefined : column.rowId)
                          }
                        />
                        <Button
                          aria-label={`${t('hospitalTableDesign.moveUp')} ${
                            index + 1
                          }`}
                          color="tertiary"
                          iconLeading={ChevronUp}
                          isDisabled={disabled || index === 0}
                          size="sm"
                          onClick={() => move(index, -1)}
                        />
                        <Button
                          aria-label={`${t('hospitalTableDesign.moveDown')} ${
                            index + 1
                          }`}
                          color="tertiary"
                          iconLeading={ChevronDown}
                          isDisabled={disabled || index === columns.length - 1}
                          size="sm"
                          onClick={() => move(index, 1)}
                        />
                        <Button
                          aria-label={`${t('label.duplicate')} ${index + 1}`}
                          color="tertiary"
                          iconLeading={Copy01}
                          isDisabled={disabled || columns.length >= 128}
                          size="sm"
                          onClick={() => duplicate(index)}
                        />
                        <Button
                          aria-label={`${t('label.delete')} ${index + 1}`}
                          color="tertiary"
                          iconLeading={Trash01}
                          isDisabled={disabled}
                          size="sm"
                          onClick={() => {
                            onChange(
                              columns.filter(
                                (item) => item.rowId !== column.rowId
                              )
                            );
                            if (open) {
                              setExpanded(
                                columns[index + 1]?.rowId ??
                                  columns[index - 1]?.rowId
                              );
                            }
                          }}
                        />
                      </div>
                    </td>
                  </tr>
                  {open && (
                    <tr className="hospital-table-design__settings-row">
                      <td colSpan={append ? 4 : 5}>
                        <div
                          className="hospital-table-design__settings"
                          id={settingId}>
                          <h4>
                            {t('hospitalTableDesign.fieldSettings', {
                              name: column.name || index + 1,
                            })}
                          </h4>
                          <div className="hospital-table-design__field-grid">
                            {['CHAR', 'VARCHAR'].includes(column.type) && (
                              <Input
                                isRequired
                                hint={t('hospitalTableDesign.byteLengthHint')}
                                id={`doris-length-${column.rowId}`}
                                inputMode="numeric"
                                isDisabled={disabled}
                                label={t('hospitalTableDesign.byteLength')}
                                value={column.length}
                                onChange={(length) =>
                                  update(column.rowId, { length })
                                }
                              />
                            )}
                            {column.type === 'DECIMAL' && (
                              <>
                                <Input
                                  isRequired
                                  id={`doris-precision-${column.rowId}`}
                                  inputMode="numeric"
                                  isDisabled={disabled}
                                  label={t('hospitalTableDesign.precision')}
                                  value={column.precision}
                                  onChange={(precision) =>
                                    update(column.rowId, { precision })
                                  }
                                />
                                <Input
                                  isRequired
                                  id={`doris-scale-${column.rowId}`}
                                  inputMode="numeric"
                                  isDisabled={disabled}
                                  label={t('hospitalTableDesign.scale')}
                                  value={column.scale}
                                  onChange={(scale) =>
                                    update(column.rowId, { scale })
                                  }
                                />
                              </>
                            )}
                            {column.type === 'DATETIME' && (
                              <Input
                                isRequired
                                id={`doris-time-${column.rowId}`}
                                inputMode="numeric"
                                isDisabled={disabled}
                                label={t('hospitalTableDesign.timePrecision')}
                                value={column.scale}
                                onChange={(scale) =>
                                  update(column.rowId, { scale })
                                }
                              />
                            )}
                            <Select
                              isDisabled={disabled}
                              items={defaultKinds.map((id) => ({
                                id,
                                label: t('hospitalTableDesign.defaults.' + id),
                              }))}
                              label={t('hospitalTableDesign.defaultKind')}
                              selectedKey={column.defaultKind}
                              onSelectionChange={(key) => {
                                const defaultKind = defaultKinds.find(
                                  (item) => item === key
                                );
                                if (defaultKind) {
                                  update(column.rowId, { defaultKind });
                                }
                              }}>
                              {(item) => <Select.Item {...item} />}
                            </Select>
                            {column.defaultKind === 'LITERAL' && (
                              <Input
                                hint={t('hospitalTableDesign.defaultHint')}
                                id={`doris-default-${column.rowId}`}
                                isDisabled={disabled}
                                label={t('hospitalTableDesign.defaultValue')}
                                maxLength={4096}
                                value={column.defaultValue}
                                onChange={(defaultValue) =>
                                  update(column.rowId, { defaultValue })
                                }
                              />
                            )}
                            <Input
                              hint={t('hospitalTableDesign.commentHint')}
                              id={`doris-comment-${column.rowId}`}
                              isDisabled={disabled}
                              label={t('label.description')}
                              maxLength={1024}
                              value={column.comment}
                              onChange={(comment) =>
                                update(column.rowId, { comment })
                              }
                            />
                          </div>
                          {errors[column.rowId]?.map((key) => (
                            <p
                              className="hospital-table-design__field-error"
                              key={key}>
                              {t(key)}
                            </p>
                          ))}
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      {!columns.length && (
        <p role="status">{t('hospitalTableDesign.addField')}</p>
      )}
    </section>
  );
};
