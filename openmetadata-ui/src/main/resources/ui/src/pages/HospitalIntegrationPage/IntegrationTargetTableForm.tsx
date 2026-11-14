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
import { Button, Checkbox, Input } from '@openmetadata/ui-core-components';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  createIntegrationTargetTable,
  getIntegrationFailure,
  IntegrationTargetTableInput,
  IntegrationTargetTablePreview,
  previewIntegrationTargetTable,
} from '../../rest/hospitalIntegrationAPI';
import { IntegrationTargetTableFormProps } from './HospitalIntegrationPage.interface';
import {
  integrationErrorKey,
  suggestIntegrationIdentifier,
} from './HospitalIntegrationUtils';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';

export const IntegrationTargetTableForm = ({
  source,
  sourceConnectionId,
  mode,
  sourceQuery,
  primaryKey,
  targetConnection,
  disabled,
  onBusyChange,
  onDraftChange,
  onCreated,
}: IntegrationTargetTableFormProps) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [targetTable, setTargetTable] = useState(() =>
    suggestIntegrationIdentifier(source.name)
  );
  const [columnDraft, setColumnDraft] = useState<
    {
      source: string;
      target: string;
      included: boolean;
      primaryKey: boolean;
    }[]
  >([]);
  const columns = (() => {
    const used = new Set(
      columnDraft
        .filter((draft) =>
          source.columns.some((column) => column.name === draft.source)
        )
        .map((draft) => draft.target)
    );

    return source.columns.map((column) => {
      const draft = columnDraft.find((item) => item.source === column.name);
      if (draft) {
        return {
          ...draft,
          primaryKey: column.primaryKey,
          included: draft.included || column.primaryKey,
        };
      }
      const base = suggestIntegrationIdentifier(column.name);
      let target = base;
      let suffix = 1;
      while (used.has(target)) {
        target = base.slice(0, 58) + '_' + suffix++;
      }
      used.add(target);

      return {
        source: column.name,
        target,
        included: true,
        primaryKey: column.primaryKey,
      };
    });
  })();
  const sourceColumnsKey = JSON.stringify(source.columns);
  const [preview, setPreview] = useState<IntegrationTargetTablePreview>();
  const [errorCode, setErrorCode] = useState<string>();
  const [validationError, setValidationError] = useState(false);
  const [pending, setPending] = useState<'preview' | 'create'>();
  const [created, setCreated] = useState(false);
  const alive = useRef(true);
  const mutation = useRef(false);
  const revision = useRef(0);
  const controller = useRef<AbortController>();
  const input: IntegrationTargetTableInput = {
    sourceConnectionId,
    sourceSchema: source.schema,
    sourceTable: source.name,
    targetSchema: targetConnection.database,
    targetTable,
    fieldMappings: columns
      .filter((column) => column.included)
      .map(({ source, target }) => ({ source, target })),
    ...(mode === 'SQL_T1' ? { mode, sourceQuery, primaryKey } : {}),
  };
  const inputKey = JSON.stringify(input);
  const latestInput = useRef(inputKey);
  latestInput.current = inputKey;
  const previewInput = useRef<string>();
  const previewSource = useRef<string>();

  useEffect(() => {
    alive.current = true;

    return () => {
      alive.current = false;
      revision.current += 1;
      controller.current?.abort();
      onBusyChange(false);
      onDraftChange(false);
    };
  }, [onBusyChange, onDraftChange]);

  const invalidate = () => {
    revision.current += 1;
    controller.current?.abort();
    setPreview(undefined);
    previewInput.current = undefined;
    setValidationError(false);
    setErrorCode(undefined);
    setCreated(false);
    onDraftChange(true);
  };
  const perform = async (action: 'preview' | 'create') => {
    if (mutation.current || disabled) {
      return;
    }
    const identifier = /^[a-z][a-z0-9_]{0,63}$/;
    const names = input.fieldMappings.map((mapping) => mapping.target);
    const valid =
      identifier.test(targetTable) &&
      names.length > 0 &&
      new Set(names).size === names.length &&
      names.every((name) => identifier.test(name)) &&
      source.columns.some((column) => column.primaryKey) &&
      source.columns
        .filter((column) => column.primaryKey)
        .every((column) =>
          input.fieldMappings.some((mapping) => mapping.source === column.name)
        );
    setValidationError(!valid);
    if (
      !valid ||
      (action === 'create' &&
        (!preview ||
          previewInput.current !== inputKey ||
          previewSource.current !== sourceColumnsKey))
    ) {
      return;
    }
    const capturedRevision = revision.current;
    const capturedInput = inputKey;
    const current = () =>
      alive.current &&
      revision.current === capturedRevision &&
      latestInput.current === capturedInput;
    mutation.current = true;
    setPending(action);
    onBusyChange(true);
    setErrorCode(undefined);
    try {
      if (action === 'preview') {
        controller.current?.abort();
        controller.current = new AbortController();
        const result = await previewIntegrationTargetTable(
          targetConnection.id,
          input,
          controller.current.signal
        );
        if (
          current() &&
          !controller.current.signal.aborted &&
          result.targetConnectionId === targetConnection.id
        ) {
          previewInput.current = capturedInput;
          previewSource.current = sourceColumnsKey;
          setPreview(result);
        }
      } else if (preview) {
        const table = await createIntegrationTargetTable(targetConnection.id, {
          ...input,
          previewHash: preview.previewHash,
        });
        if (current()) {
          onCreated(table, preview.fieldMappings);
          onDraftChange(false);
          setOpen(false);
          setPreview(undefined);
          setCreated(true);
        }
      }
    } catch (error) {
      if (current()) {
        const code = getIntegrationFailure(error).code;
        setErrorCode(code);
        if (code === 'TABLE_PREVIEW_STALE' || code === 'TABLE_ALREADY_EXISTS') {
          setPreview(undefined);
          previewInput.current = undefined;
        }
      }
    } finally {
      mutation.current = false;
      if (alive.current) {
        setPending(undefined);
        onBusyChange(false);
      }
    }
  };

  return (
    <div
      className="hospital-integration__target-create"
      data-testid="integration-target-create">
      <Button
        aria-controls="integration-target-create-fields"
        aria-expanded={open}
        color="link-color"
        isDisabled={disabled || Boolean(pending)}
        onClick={() => {
          setOpen(!open);
          if (!open) {
            onDraftChange(true);
            setCreated(false);
          }
        }}>
        {t('hospitalIntegration.createTargetFromSource')}
      </Button>
      {created && (
        <p className="hospital-integration__hint" role="status">
          {t('hospitalIntegration.targetTableCreated')}
        </p>
      )}
      {open && (
        <div
          aria-busy={Boolean(pending)}
          className="hospital-integration__target-create-fields"
          id="integration-target-create-fields">
          <p>{t('hospitalIntegration.targetTableHint')}</p>
          <p className="hospital-integration__hint">
            {t('hospitalIntegration.targetTableDatabase', {
              database: targetConnection.database,
            })}
          </p>
          {errorCode && (
            <IntegrationDrawerAlert focusKey={errorCode}>
              <p>{t(integrationErrorKey(errorCode))}</p>
            </IntegrationDrawerAlert>
          )}
          {validationError && (
            <IntegrationDrawerAlert focusKey="targetTableNamesInvalid">
              <p>{t('hospitalIntegration.targetTableNamesInvalid')}</p>
            </IntegrationDrawerAlert>
          )}
          <Input
            isRequired
            id="integration-new-target-table"
            inputDataTestId="integration-new-target-table"
            isDisabled={disabled || Boolean(pending)}
            label={t('hospitalIntegration.targetTable')}
            maxLength={64}
            value={targetTable}
            onChange={(name) => {
              invalidate();
              setTargetTable(name);
            }}
          />
          <div className="hospital-integration__mapping">
            {columns.map((column, index) => (
              <div
                className="hospital-integration__target-column"
                key={column.source}>
                <div>
                  <strong>{column.source}</strong>
                  <small>
                    {source.columns[index].dataType}
                    {column.primaryKey ? ' · ' + t('label.primary-key') : ''}
                  </small>
                </div>
                <Input
                  aria-label={t('hospitalIntegration.mappingFor', {
                    field: column.source,
                  })}
                  id={'integration-new-column-' + index}
                  inputDataTestId={'integration-new-column-' + index}
                  isDisabled={disabled || Boolean(pending) || !column.included}
                  maxLength={64}
                  value={column.target}
                  onChange={(target) => {
                    invalidate();
                    setColumnDraft(
                      columns.map((item, i) =>
                        i === index ? { ...item, target } : item
                      )
                    );
                  }}
                />
                <Checkbox
                  aria-label={t('hospitalIntegration.includeField', {
                    field: column.source,
                  })}
                  isDisabled={disabled || Boolean(pending) || column.primaryKey}
                  isSelected={column.included}
                  label={t(
                    column.included
                      ? 'hospitalIntegration.includeColumn'
                      : 'hospitalIntegration.omitField'
                  )}
                  onChange={(included) => {
                    invalidate();
                    setColumnDraft(
                      columns.map((item, i) =>
                        i === index ? { ...item, included } : item
                      )
                    );
                  }}
                />
              </div>
            ))}
          </div>
          <p className="hospital-integration__hint">
            {t('hospitalIntegration.targetTablePrimaryKeyHint')}
          </p>
          <Button
            color="secondary"
            isDisabled={disabled || Boolean(pending)}
            isLoading={pending === 'preview'}
            onClick={() => void perform('preview')}>
            {t('hospitalIntegration.previewTargetTable')}
          </Button>
          {pending && (
            <p className="hospital-integration__hint" role="status">
              {t(
                pending === 'create'
                  ? 'hospitalIntegration.creatingTargetTable'
                  : 'hospitalIntegration.loading'
              )}
            </p>
          )}
          {preview &&
            previewInput.current === inputKey &&
            previewSource.current === sourceColumnsKey && (
              <div
                className="hospital-integration__target-preview"
                data-testid="integration-target-preview">
                <h3>{t('hospitalIntegration.targetTablePreview')}</h3>
                <div className="hospital-integration__table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th scope="col">{t('label.name')}</th>
                        <th scope="col">{t('label.type')}</th>
                        <th scope="col">{t('label.primary-key')}</th>
                        <th scope="col">{t('hospitalIntegration.nullable')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.table.columns.map((column) => (
                        <tr key={column.name}>
                          <td>{column.name}</td>
                          <td>{column.dataType}</td>
                          <td>
                            {t(column.primaryKey ? 'label.yes' : 'label.no')}
                          </td>
                          <td>
                            {t(column.nullable ? 'label.yes' : 'label.no')}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <details>
                  <summary>{t('hospitalIntegration.generatedDdl')}</summary>
                  <pre
                    className="hospital-integration__ddl"
                    data-testid="integration-target-ddl">
                    <code>{preview.ddl}</code>
                  </pre>
                </details>
                <p className="hospital-integration__hint">
                  {t('hospitalIntegration.targetTableCreateHint')}
                </p>
                <Button
                  isDisabled={disabled || Boolean(pending)}
                  isLoading={pending === 'create'}
                  onClick={() => void perform('create')}>
                  {t('hospitalIntegration.confirmCreateTargetTable')}
                </Button>
              </div>
            )}
        </div>
      )}
    </div>
  );
};
