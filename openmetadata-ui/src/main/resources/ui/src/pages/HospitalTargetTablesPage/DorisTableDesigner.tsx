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
import { Button, Input } from '@openmetadata/ui-core-components';
import { RefreshCw01 } from '@untitledui/icons';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import {
  addDorisTableColumns,
  createDesignedDorisTable,
  DorisColumnInput,
  DorisTableDesignPreview,
  DorisTableStructure,
  getDorisTableStructure,
  getIntegrationFailure,
  IntegrationTargetTableInput,
  previewDesignedDorisTable,
  previewDorisTableColumns,
  previewIntegrationTargetTable,
} from '../../rest/hospitalIntegrationAPI';
import { suggestIntegrationIdentifier } from '../HospitalIntegrationPage/HospitalIntegrationUtils';
import { IntegrationDiscardConfirmation } from '../HospitalIntegrationPage/IntegrationDiscardConfirmation';
import { IntegrationDrawerAlert } from '../HospitalIntegrationPage/IntegrationDrawerAlert';
import { DorisFieldEditor } from './DorisFieldEditor';
import {
  columnsFromTargetPreview,
  createDorisTableDraft,
  dorisChangeActive,
  dorisColumnsMatch,
  dorisDesignErrorKey,
  serializeDorisDesign,
  validateDorisDesign,
} from './DorisTableDesignUtils';
import { DorisTableStructureView } from './DorisTableStructureView';
import './hospital-table-design.less';
import { DorisTableDesignerProps } from './HospitalTargetTablesPage.interface';

const visibleColumns = (
  structure: DorisTableStructure,
  expected: DorisColumnInput[]
) =>
  expected.every((column) =>
    structure.columns.some((actual) => dorisColumnsMatch(actual, column))
  );

export const DorisTableDesigner = ({
  connection,
  existing,
  source,
  onClose,
  onApplied,
  onBusyChange,
  onDraftChange,
  navigationBlocked = false,
  onKeepNavigation,
  onDiscardNavigation,
}: DorisTableDesignerProps) => {
  const { t } = useTranslation();
  const append = Boolean(existing);
  const [draft, setDraft] = useState(() =>
    createDorisTableDraft(
      connection.database,
      existing?.targetTable ??
        (source ? suggestIntegrationIdentifier(source.table.name) : ''),
      append
    )
  );
  const initial = useRef(JSON.stringify(draft));
  const initialFields = useRef(JSON.stringify(draft.columns));
  const [preview, setPreview] = useState<DorisTableDesignPreview>();
  const [previewKey, setPreviewKey] = useState<string>();
  const [pending, setPending] = useState<
    'preview' | 'apply' | 'import' | 'refresh'
  >();
  const [errorCode, setErrorCode] = useState<string>();
  const [validation, setValidation] = useState<
    ReturnType<typeof validateDorisDesign>
  >({ errors: [], fields: {} });
  const [discard, setDiscard] = useState(false);
  const [replaceImport, setReplaceImport] = useState(false);
  const [submitted, setSubmitted] = useState<DorisColumnInput[]>();
  const [uncertain, setUncertain] = useState(false);
  const [refreshStopped, setRefreshStopped] = useState(false);
  const [seedMappings, setSeedMappings] = useState<
    IntegrationTargetTableInput['fieldMappings']
  >([]);
  const alive = useRef(true);
  const mutation = useRef(false);
  const finished = useRef(false);
  const revision = useRef(0);
  const controller = useRef<AbortController>();
  const callbacks = useRef({ onClose, onApplied, onBusyChange, onDraftChange });
  callbacks.current = { onClose, onApplied, onBusyChange, onDraftChange };
  const input = serializeDorisDesign(draft);
  const inputKey = JSON.stringify(input);
  const currentKey = useRef(inputKey);
  currentKey.current = inputKey;
  const sourceKey = JSON.stringify(source);
  const currentSource = useRef(sourceKey);
  currentSource.current = sourceKey;
  const dirty = !submitted && JSON.stringify(draft) !== initial.current;
  const disabled = Boolean(pending || submitted || uncertain);

  useEffect(() => {
    alive.current = true;

    return () => {
      alive.current = false;
      revision.current += 1;
      controller.current?.abort();
      callbacks.current.onBusyChange?.(false);
      callbacks.current.onDraftChange?.(false);
    };
  }, []);
  useEffect(() => {
    callbacks.current.onDraftChange?.(dirty);
  }, [dirty]);

  const invalidate = () => {
    revision.current += 1;
    controller.current?.abort();
    setPreview(undefined);
    setPreviewKey(undefined);
    setErrorCode(undefined);
    setValidation({ errors: [], fields: {} });
    setReplaceImport(false);
  };
  const finish = (structure: DorisTableStructure) => {
    if (finished.current) {
      return;
    }
    finished.current = true;
    setSubmitted(undefined);
    setUncertain(false);
    const validTargets = new Set(
      structure.table.columns.map((column) => column.name)
    );
    const mappings = seedMappings.filter((mapping) =>
      validTargets.has(mapping.target)
    );
    for (const column of source?.table.columns ?? []) {
      if (mappings.some((mapping) => mapping.source === column.name)) {
        continue;
      }
      const target = structure.table.columns.find(
        (item) => item.name.toLowerCase() === column.name.toLowerCase()
      );
      if (
        target &&
        !mappings.some((mapping) => mapping.target === target.name)
      ) {
        mappings.push({ source: column.name, target: target.name });
      }
    }
    initial.current = JSON.stringify(draft);
    callbacks.current.onDraftChange?.(false);
    callbacks.current.onApplied(structure, mappings);
  };
  const start = (kind: NonNullable<typeof pending>) => {
    mutation.current = true;
    setPending(kind);
    callbacks.current.onBusyChange?.(true);
    setErrorCode(undefined);
  };
  const end = () => {
    mutation.current = false;
    if (alive.current) {
      setPending(undefined);
      callbacks.current.onBusyChange?.(false);
    }
  };
  const refresh = async () => {
    if (mutation.current || finished.current || !submitted || !alive.current) {
      return;
    }
    start('refresh');
    controller.current?.abort();
    const request = new AbortController();
    controller.current = request;
    try {
      const structure = await getDorisTableStructure(
        connection.id,
        input.targetSchema,
        input.targetTable,
        request.signal
      );
      if (!alive.current || request.signal.aborted) {
        return;
      }
      const headersMatch =
        append ||
        (structure.model === 'UNIQUE' &&
          (structure.comment ?? '') === input.comment &&
          structure.buckets === input.buckets);
      if (
        headersMatch &&
        visibleColumns(structure, submitted) &&
        !dorisChangeActive(structure.change?.state) &&
        !['CANCELLED', 'CANCELED', 'FAILED'].includes(
          structure.change?.state ?? ''
        )
      ) {
        setUncertain(false);
        finish(structure);
      } else {
        setRefreshStopped(
          Boolean(
            structure.change &&
              ['CANCELLED', 'CANCELED', 'FAILED'].includes(
                structure.change.state
              )
          )
        );
        if (
          structure.change &&
          ['CANCELLED', 'CANCELED', 'FAILED'].includes(structure.change.state)
        ) {
          setErrorCode('TARGET_TABLE_ALTER_FAILED');
        }
      }
    } catch (error) {
      if (alive.current && !request.signal.aborted) {
        const failure = getIntegrationFailure(error);
        if (failure.code === 'TABLE_NOT_FOUND' && uncertain) {
          setSubmitted(undefined);
          setUncertain(false);
          setPreview(undefined);
          setPreviewKey(undefined);
        } else {
          setErrorCode(failure.code);
          setRefreshStopped(true);
        }
      }
    } finally {
      end();
    }
  };
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  useEffect(() => {
    if (!submitted || uncertain || refreshStopped) {
      return;
    }
    const timer = window.setInterval(() => void refreshRef.current(), 3000);

    return () => window.clearInterval(timer);
  }, [submitted, uncertain, refreshStopped]);

  const perform = async (action: 'preview' | 'apply') => {
    if (mutation.current || finished.current || submitted || uncertain) {
      return;
    }
    const checked = validateDorisDesign(
      draft,
      append,
      existing?.columns.map((column) => column.name)
    );
    setValidation(checked);
    if (checked.errors.length) {
      return;
    }
    if (action === 'apply' && (!preview || previewKey !== inputKey)) {
      return;
    }
    const capturedRevision = revision.current;
    const capturedKey = inputKey;
    const current = () =>
      alive.current &&
      revision.current === capturedRevision &&
      currentKey.current === capturedKey;
    start(action);
    controller.current?.abort();
    controller.current = new AbortController();
    try {
      if (action === 'preview') {
        const result = existing
          ? await previewDorisTableColumns(
              connection.id,
              existing.targetSchema,
              existing.targetTable,
              { columns: input.columns },
              controller.current.signal
            )
          : await previewDesignedDorisTable(
              connection.id,
              input,
              controller.current.signal
            );
        if (
          current() &&
          !controller.current.signal.aborted &&
          result.targetConnectionId === connection.id &&
          result.targetSchema === input.targetSchema &&
          result.targetTable === input.targetTable &&
          result.action === (append ? 'ADD_COLUMNS' : 'CREATE')
        ) {
          setPreview(result);
          setPreviewKey(capturedKey);
        }
      } else if (preview) {
        const result = existing
          ? await addDorisTableColumns(
              connection.id,
              existing.targetSchema,
              existing.targetTable,
              { columns: input.columns, previewHash: preview.previewHash }
            )
          : await createDesignedDorisTable(connection.id, {
              ...input,
              previewHash: preview.previewHash,
            });
        if (!current()) {
          return;
        }
        if (
          result.status === 'APPLIED' &&
          visibleColumns(result.structure, preview.columns)
        ) {
          finish(result.structure);
        } else {
          setSubmitted(preview.columns);
          initial.current = JSON.stringify(draft);
          callbacks.current.onDraftChange?.(false);
        }
      }
    } catch (error) {
      if (current()) {
        const failure = getIntegrationFailure(error);
        setErrorCode(failure.code);
        setPreview(undefined);
        setPreviewKey(undefined);
        if (action === 'apply' && failure.uncertain) {
          setSubmitted(preview?.columns ?? input.columns);
          setUncertain(true);
        }
      }
    } finally {
      end();
    }
  };

  const importSource = async () => {
    if (mutation.current || !source || disabled) {
      return;
    }
    const used = new Set<string>();
    const mappings = source.table.columns.map((column) => {
      const base = suggestIntegrationIdentifier(column.name);
      let target = base;
      let suffix = 1;
      while (used.has(target)) {
        target = base.slice(0, 58) + '_' + suffix++;
      }
      used.add(target);

      return { source: column.name, target };
    });
    const capturedRevision = revision.current;
    const capturedSource = sourceKey;
    start('import');
    const request = new AbortController();
    controller.current = request;
    setReplaceImport(false);
    try {
      const result = await previewIntegrationTargetTable(
        connection.id,
        {
          sourceConnectionId: source.connectionId,
          sourceSchema: source.table.schema,
          sourceTable: source.table.name,
          targetSchema: input.targetSchema,
          targetTable: input.targetTable,
          fieldMappings: mappings,
          ...(source.mode === 'SQL_T1'
            ? {
                mode: source.mode,
                sourceQuery: source.sourceQuery,
                primaryKey: source.primaryKey,
              }
            : {}),
        },
        request.signal
      );
      if (
        alive.current &&
        !request.signal.aborted &&
        revision.current === capturedRevision &&
        currentSource.current === capturedSource
      ) {
        const columns = columnsFromTargetPreview(result.table);
        invalidate();
        setDraft((value) => ({ ...value, columns }));
        setSeedMappings(result.fieldMappings);
      }
    } catch (error) {
      if (alive.current && !request.signal.aborted) {
        setErrorCode(
          error instanceof Error && error.message === 'UNSUPPORTED_TARGET_TYPE'
            ? 'UNSUPPORTED_TARGET_TYPE'
            : getIntegrationFailure(error).code
        );
      }
    } finally {
      end();
    }
  };
  const close = () => {
    if (mutation.current) {
      return;
    }
    if (dirty) {
      setDiscard(true);
    } else {
      onClose();
    }
  };

  return (
    <FormDrawer
      isOpen
      className="hospital-integration__drawer hospital-table-design__drawer"
      data-testid="doris-table-designer"
      footer={
        <>
          <Button
            color="secondary"
            isDisabled={Boolean(pending)}
            onClick={close}>
            {t(submitted ? 'label.close' : 'label.cancel')}
          </Button>
          {submitted ? (
            <Button
              iconLeading={RefreshCw01}
              isLoading={pending === 'refresh'}
              onClick={() => {
                setRefreshStopped(false);
                void refresh();
              }}>
              {t('hospitalTableDesign.refreshStructure')}
            </Button>
          ) : (
            <>
              <Button
                color="secondary"
                isDisabled={disabled || discard}
                isLoading={pending === 'preview'}
                onClick={() => void perform('preview')}>
                {t('hospitalIntegration.previewTargetTable')}
              </Button>
              <Button
                isDisabled={
                  disabled || discard || !preview || previewKey !== inputKey
                }
                isLoading={pending === 'apply'}
                onClick={() => void perform('apply')}>
                {t(
                  append
                    ? 'hospitalTableDesign.applyColumns'
                    : 'hospitalIntegration.confirmCreateTargetTable'
                )}
              </Button>
            </>
          )}
        </>
      }
      isSubmitting={Boolean(pending)}
      subtitle={`${connection.displayName} · ${connection.database}`}
      title={t(
        append ? 'hospitalTableDesign.addColumns' : 'hospitalTableDesign.create'
      )}
      width={1100}
      onClose={close}>
      {(discard || navigationBlocked) && (
        <IntegrationDiscardConfirmation
          onContinue={() => {
            setDiscard(false);
            onKeepNavigation?.();
          }}
          onDiscard={
            navigationBlocked && onDiscardNavigation
              ? onDiscardNavigation
              : onClose
          }
        />
      )}
      {errorCode && (
        <IntegrationDrawerAlert focusKey={errorCode}>
          <p>{t(dorisDesignErrorKey(errorCode))}</p>
        </IntegrationDrawerAlert>
      )}
      {validation.errors.length > 0 && (
        <IntegrationDrawerAlert focusKey={validation.errors}>
          <ul>
            {validation.errors.map((key) => (
              <li key={key}>{t(key)}</li>
            ))}
          </ul>
        </IntegrationDrawerAlert>
      )}
      {submitted && (
        <div className="hospital-table-design__notice" role="status">
          <h3>
            {t(
              uncertain
                ? 'hospitalTableDesign.uncertain'
                : 'hospitalTableDesign.submitted'
            )}
          </h3>
          <p>
            {t(
              uncertain
                ? 'hospitalTableDesign.uncertainHint'
                : 'hospitalTableDesign.submittedHint'
            )}
          </p>
        </div>
      )}
      {existing && (
        <details className="hospital-table-design__existing">
          <summary>
            {t('hospitalTableDesign.existingStructure')} (
            {existing.columns.length})
          </summary>
          <DorisTableStructureView structure={existing} />
        </details>
      )}
      <fieldset className="hospital-table-design__fieldset" disabled={disabled}>
        <section>
          <h3>{t('hospitalTableDesign.tableSettings')}</h3>
          <div className="hospital-table-design__field-grid">
            <Input
              isReadOnly
              id="doris-target-database"
              label={t('label.database')}
              value={draft.targetSchema}
            />
            <Input
              isRequired
              id="doris-target-table"
              isDisabled={disabled || append}
              label={t('hospitalIntegration.targetTable')}
              maxLength={64}
              value={draft.targetTable}
              onChange={(targetTable) => {
                invalidate();
                setDraft((value) => ({ ...value, targetTable }));
              }}
            />
            {!append && (
              <>
                <Input
                  id="doris-table-comment"
                  isDisabled={disabled}
                  label={t('label.description')}
                  maxLength={1024}
                  value={draft.comment}
                  onChange={(comment) => {
                    invalidate();
                    setDraft((value) => ({ ...value, comment }));
                  }}
                />
                <Input
                  isRequired
                  hint={t('hospitalTableDesign.bucketsHint')}
                  id="doris-table-buckets"
                  inputMode="numeric"
                  isDisabled={disabled}
                  label={t('hospitalTableDesign.buckets')}
                  value={draft.buckets}
                  onChange={(buckets) => {
                    invalidate();
                    setDraft((value) => ({ ...value, buckets }));
                  }}
                />
              </>
            )}
          </div>
        </section>
        {source && !append && (
          <div className="hospital-table-design__import">
            <Button
              color="secondary"
              isDisabled={disabled || !draft.targetTable.trim()}
              isLoading={pending === 'import'}
              onClick={() => {
                if (JSON.stringify(draft.columns) !== initialFields.current) {
                  setReplaceImport(true);
                } else {
                  void importSource();
                }
              }}>
              {t('hospitalTableDesign.importSource')}
            </Button>
            {replaceImport && (
              <div className="hospital-table-design__notice" role="status">
                <p>{t('hospitalTableDesign.replaceFieldsHint')}</p>
                <div className="hospital-integration__actions">
                  <Button
                    color="secondary"
                    onClick={() => setReplaceImport(false)}>
                    {t('label.cancel')}
                  </Button>
                  <Button onClick={() => void importSource()}>
                    {t('hospitalTableDesign.importSource')}
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}
        <DorisFieldEditor
          append={append}
          columns={draft.columns}
          disabled={disabled}
          errors={validation.fields}
          onChange={(columns) => {
            invalidate();
            setDraft((value) => ({ ...value, columns }));
          }}
        />
      </fieldset>
      {preview && (
        <section
          className="hospital-table-design__preview"
          data-testid="doris-table-preview">
          <h3>{t('hospitalIntegration.targetTablePreview')}</h3>
          <pre>{preview.ddl}</pre>
          <p className="hospital-integration__hint">
            {t(
              append
                ? 'hospitalTableDesign.appendHint'
                : 'hospitalTableDesign.createHint'
            )}
          </p>
        </section>
      )}
    </FormDrawer>
  );
};
