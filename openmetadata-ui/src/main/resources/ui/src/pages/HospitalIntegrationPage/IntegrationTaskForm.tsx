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
  TextArea,
} from '@openmetadata/ui-core-components';
import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FormDrawerActions } from '../../components/common/atoms/drawer/FormDrawerActions';
import { IntegrationTable } from '../../rest/hospitalIntegrationAPI';
import { DorisTableDesigner } from '../HospitalTargetTablesPage/DorisTableDesigner';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import {
  DEFAULT_INTEGRATION_SCHEDULE,
  findIntegrationTable,
  getIntegrationInitializationPreview,
  integrationErrorKey,
  integrationModeKey,
  integrationTableKey,
  integrationToday,
  isIntegrationInitializationLocked,
  SQL_QUERY_MAX_BYTES,
} from './HospitalIntegrationUtils';
import { IntegrationCdcPreflight } from './IntegrationCdcPreflight';
import { changeIntegrationMode } from './IntegrationCdcUtils';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';
import { IntegrationExecutionSettings } from './IntegrationExecutionSettings';
import { IntegrationMssqlCdcOptions } from './IntegrationMssqlCdcOptions';
import { IntegrationOracleCdcOptions } from './IntegrationOracleCdcOptions';
import { IntegrationTargetTableForm } from './IntegrationTargetTableForm';

export const IntegrationTaskForm = ({
  value,
  sourceTables,
  connections,
  tablesLoading,
  onReloadTables,
  queryLoading = false,
  queryErrorCode,
  onReadQuerySchema,
  initializationState,
  initializationUnavailable = false,
  onTargetTableBusyChange,
  onTargetTableDraftChange,
  onTargetTableCreated,
  navigationBlocked,
  onKeepNavigation,
  onDiscardNavigation,
  targetTables,
  errors,
  disabled,
  validating,
  saving,
  tablesUnavailable,
  onChange,
  onValidate,
  onSave,
  onCancel,
  preflight,
  preflightLoading,
  preflightErrorCode,
  onPreflight,
  onRuntimeValidityChange,
  onRuntimeDraftChange,
}: IntegrationTaskFormProps) => {
  const { t } = useTranslation();
  const [designerOpen, setDesignerOpen] = useState(false);
  const designerState = useRef({
    automaticBusy: false,
    manualBusy: false,
    automaticDirty: false,
    manualDirty: false,
  });
  const automaticBusy = useCallback(
    (busy: boolean) => {
      designerState.current.automaticBusy = busy;
      onTargetTableBusyChange?.(busy || designerState.current.manualBusy);
    },
    [onTargetTableBusyChange]
  );
  const manualBusy = useCallback(
    (busy: boolean) => {
      designerState.current.manualBusy = busy;
      onTargetTableBusyChange?.(busy || designerState.current.automaticBusy);
    },
    [onTargetTableBusyChange]
  );
  const automaticDirty = useCallback(
    (dirty: boolean) => {
      designerState.current.automaticDirty = dirty;
      onTargetTableDraftChange?.(dirty || designerState.current.manualDirty);
    },
    [onTargetTableDraftChange]
  );
  const manualDirty = useCallback(
    (dirty: boolean) => {
      designerState.current.manualDirty = dirty;
      onTargetTableDraftChange?.(dirty || designerState.current.automaticDirty);
    },
    [onTargetTableDraftChange]
  );
  const sqlMode = value.mode === 'SQL_T1';
  const schedule = value.schedule ?? DEFAULT_INTEGRATION_SCHEDULE;
  const initialization = value.initialization ?? {
    enabled: false,
    startDate: '',
    batchUnit: 'DAY' as const,
  };
  const routeLocked = isIntegrationInitializationLocked(initializationState);
  const routeDisabled = disabled || routeLocked;
  const initializationUnchanged =
    initializationState?.startDate === initialization.startDate &&
    initializationState?.batchUnit === initialization.batchUnit;
  const cutoffDate =
    initializationState && (routeLocked || initializationUnchanged)
      ? initializationState.cutoffDate
      : integrationToday();
  const preview = initialization.enabled
    ? getIntegrationInitializationPreview(initialization, cutoffDate)
    : undefined;
  const source = findIntegrationTable(
    sourceTables,
    value.sourceSchema,
    value.sourceTable
  );
  const target = findIntegrationTable(
    targetTables,
    value.targetSchema,
    value.targetTable
  );
  const tableItems = (tables: IntegrationTable[]) =>
    tables.map((table) => ({
      id: integrationTableKey(table),
      label: integrationTableKey(table),
    }));
  const selectTable = (role: 'source' | 'target', key: string) => {
    const tables = role === 'source' ? sourceTables : targetTables;
    const table = tables.find((item) => integrationTableKey(item) === key);
    if (!table) {
      return;
    }
    onChange({
      ...value,
      [`${role}Schema`]: table.schema,
      [`${role}Table`]: table.name,
      primaryKey: role === 'source' ? '' : value.primaryKey,
      fieldMappings: [],
    });
  };

  const sourceConnection = connections.find(
    (connection) =>
      connection.id === value.sourceConnectionId &&
      connection.enabled &&
      connection.role === 'SOURCE'
  );
  const targetConnection = connections.find(
    (connection) =>
      connection.id === value.targetConnectionId &&
      connection.enabled &&
      connection.role === 'TARGET'
  );
  const supportedModes = (sourceConnection?.supportedModes ?? []).filter(
    (mode) =>
      mode !== 'CDC' ||
      sourceConnection?.databaseType === 'Oracle' ||
      sourceConnection?.databaseType === 'Postgres' ||
      sourceConnection?.databaseType === 'Mssql'
  );
  const modes =
    supportedModes.includes('FULL') && !supportedModes.includes('SQL_T1')
      ? [...supportedModes, 'SQL_T1' as const]
      : supportedModes;

  return (
    <form
      noValidate
      className="hospital-integration__form"
      data-testid="integration-task-form"
      id="integration-task-form"
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}>
      {errors.length > 0 && (
        <IntegrationDrawerAlert focusKey={errors}>
          <p>{t('hospitalIntegration.checkForm')}</p>
          <ul>
            {errors.map((error) => (
              <li key={error}>
                {t(
                  `${
                    error.startsWith('runtime')
                      ? 'hospitalCdc'
                      : 'hospitalIntegration'
                  }.${error}`
                )}
              </li>
            ))}
          </ul>
        </IntegrationDrawerAlert>
      )}
      <section aria-labelledby="integration-basic-title">
        <h2 id="integration-basic-title">
          {t('hospitalIntegration.taskSetup')}
        </h2>
        <div className="hospital-integration__field-grid">
          <Input
            isRequired
            hint={t('hospitalIntegration.nameHint')}
            id="integration-task-name"
            isDisabled={disabled}
            label={t('label.name')}
            maxLength={63}
            value={value.name}
            onChange={(name) => onChange({ ...value, name })}
          />
          <Input
            isRequired
            id="integration-task-displayName"
            isDisabled={disabled}
            label={t('label.display-name')}
            value={value.displayName}
            onChange={(displayName) => onChange({ ...value, displayName })}
          />
        </div>
      </section>
      <section aria-labelledby="integration-route-title">
        <h2 id="integration-route-title">
          {t('hospitalIntegration.transferRoute')}
        </h2>
        {routeLocked && (
          <p className="hospital-integration__hint" role="status">
            {t('hospitalIntegration.initializationLockedHint')}
          </p>
        )}
        <div className="hospital-integration__actions hospital-integration__table-tools">
          <Button
            color="link-gray"
            isDisabled={disabled || tablesLoading}
            onClick={onReloadTables}>
            {t('hospitalIntegration.reloadTables')}
          </Button>
          {tablesLoading && (
            <p role="status">{t('hospitalIntegration.loading')}</p>
          )}
        </div>
        {tablesUnavailable && (
          <p className="hospital-integration__inline-error" role="alert">
            {t('hospitalIntegration.tablesUnavailable')}
          </p>
        )}
        <div className="hospital-integration__field-grid">
          <div className="hospital-integration__route-fields">
            <Select
              isRequired
              emptyState={t('hospitalIntegration.noConnections')}
              id="integration-source-connection"
              isDisabled={routeDisabled}
              items={connections
                .filter(
                  (connection) =>
                    connection.enabled &&
                    connection.role === 'SOURCE' &&
                    connection.databaseType !== 'Doris'
                )
                .map((connection) => ({
                  id: connection.id,
                  label: connection.displayName,
                }))}
              label={t('hospitalIntegration.sourceConnection')}
              placeholder={t('hospitalIntegration.chooseConnection')}
              selectedKey={value.sourceConnectionId || null}
              onSelectionChange={(key) => {
                const connection = connections.find((item) => item.id === key);
                if (!connection || connection.id === value.sourceConnectionId) {
                  return;
                }
                const mode =
                  (connection.supportedModes.includes(value.mode) &&
                    (value.mode !== 'CDC' ||
                      connection.databaseType === 'Oracle' ||
                      connection.databaseType === 'Postgres' ||
                      connection.databaseType === 'Mssql')) ||
                  (value.mode === 'SQL_T1' &&
                    connection.supportedModes.includes('FULL'))
                    ? value.mode
                    : 'FULL';
                const nextSql = mode === 'SQL_T1';
                onChange({
                  ...changeIntegrationMode(value, mode),
                  cdc: undefined,
                  sourceConnectionId: connection.id,
                  sourceSchema: nextSql ? connection.schemas[0] ?? '' : '',
                  sourceTable: nextSql ? 'sql_result' : '',
                  primaryKey: '',
                  fieldMappings: [],
                  mode,
                  sourceQuery: nextSql ? value.sourceQuery : undefined,
                  schedule: nextSql ? value.schedule : undefined,
                  initialization: nextSql ? value.initialization : undefined,
                });
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
            {sqlMode ? (
              <Select
                isRequired
                id="integration-source-schema"
                isDisabled={routeDisabled || !sourceConnection}
                items={(sourceConnection?.schemas ?? []).map((schema) => ({
                  id: schema,
                  label: schema,
                }))}
                label={t('hospitalIntegration.sourceSchema')}
                selectedKey={value.sourceSchema || null}
                onSelectionChange={(key) =>
                  onChange({
                    ...value,
                    sourceSchema: String(key ?? ''),
                    sourceTable: 'sql_result',
                    primaryKey: '',
                    fieldMappings: [],
                  })
                }>
                {(item) => <Select.Item {...item} />}
              </Select>
            ) : (
              <Select
                isRequired
                emptyState={t('hospitalIntegration.noTables')}
                id="integration-source-table"
                isDisabled={routeDisabled || sourceTables.length === 0}
                items={tableItems(sourceTables)}
                label={t('hospitalIntegration.sourceTable')}
                placeholder={t('hospitalIntegration.chooseTable')}
                selectedKey={source ? integrationTableKey(source) : null}
                onSelectionChange={(key) => selectTable('source', String(key))}>
                {(item) => <Select.Item {...item} />}
              </Select>
            )}
          </div>
          <div className="hospital-integration__route-fields">
            <Select
              isRequired
              emptyState={t('hospitalIntegration.noConnections')}
              id="integration-target-connection"
              isDisabled={routeDisabled}
              items={connections
                .filter(
                  (connection) =>
                    connection.enabled &&
                    connection.role === 'TARGET' &&
                    (!sqlMode || connection.databaseType === 'Doris')
                )
                .map((connection) => ({
                  id: connection.id,
                  label: connection.displayName,
                }))}
              label={t('hospitalIntegration.targetConnection')}
              placeholder={t('hospitalIntegration.chooseConnection')}
              selectedKey={value.targetConnectionId || null}
              onSelectionChange={(key) => {
                if (!key || key === value.targetConnectionId) {
                  return;
                }
                onChange({
                  ...value,
                  targetConnectionId: String(key),
                  targetSchema: '',
                  targetTable: '',
                  fieldMappings: [],
                });
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
            <Select
              isRequired
              emptyState={t('hospitalIntegration.noTables')}
              id="integration-target-table"
              isDisabled={routeDisabled || targetTables.length === 0}
              items={tableItems(targetTables)}
              label={t('hospitalIntegration.targetTable')}
              placeholder={t('hospitalIntegration.chooseTable')}
              selectedKey={target ? integrationTableKey(target) : null}
              onSelectionChange={(key) => selectTable('target', String(key))}>
              {(item) => <Select.Item {...item} />}
            </Select>
          </div>
          {targetConnection?.databaseType === 'Doris' && !routeLocked && (
            <div className="hospital-integration__actions">
              <Button
                color="secondary"
                isDisabled={disabled}
                onClick={() => setDesignerOpen(true)}>
                {t('hospitalTableDesign.create')}
              </Button>
            </div>
          )}
          <Select
            hint={t(
              `hospitalIntegration.${
                sqlMode
                  ? 'sqlT1Hint'
                  : value.mode === 'CDC'
                  ? 'cdcHint'
                  : 'fullHint'
              }`
            )}
            id="integration-mode"
            isDisabled={routeDisabled || !sourceConnection}
            items={modes.map((mode) => ({
              id: mode,
              label: t(integrationModeKey(mode)),
            }))}
            label={t('label.mode')}
            placeholder={t('label.mode')}
            selectedKey={modes.includes(value.mode) ? value.mode : null}
            onSelectionChange={(key) => {
              if (
                (key === 'FULL' || key === 'CDC' || key === 'SQL_T1') &&
                modes.includes(key)
              ) {
                const nextSql = key === 'SQL_T1';
                if (nextSql === sqlMode) {
                  onChange(changeIntegrationMode(value, key));

                  return;
                }
                const targetAllowed =
                  !nextSql || targetConnection?.databaseType === 'Doris';
                onChange({
                  ...changeIntegrationMode(value, key),
                  mode: key,
                  sourceSchema: nextSql
                    ? sourceConnection?.schemas[0] ?? ''
                    : '',
                  sourceTable: nextSql ? 'sql_result' : '',
                  targetConnectionId: targetAllowed
                    ? value.targetConnectionId
                    : '',
                  targetSchema: targetAllowed ? value.targetSchema : '',
                  targetTable: targetAllowed ? value.targetTable : '',
                  sourceQuery: nextSql ? value.sourceQuery ?? '' : undefined,
                  schedule: nextSql
                    ? value.schedule ?? { ...DEFAULT_INTEGRATION_SCHEDULE }
                    : undefined,
                  initialization: nextSql ? value.initialization : undefined,
                  primaryKey: '',
                  fieldMappings: [],
                });
              }
            }}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <Select
            hint={t(
              sqlMode
                ? 'hospitalIntegration.sqlPrimaryKeyHint'
                : 'hospitalIntegration.primaryKeyHint'
            )}
            id="integration-primary-key"
            isDisabled={routeDisabled || !source}
            isRequired={value.mode === 'CDC' || sqlMode}
            items={(
              source?.columns.filter(
                (column) => sqlMode || column.primaryKey
              ) ?? []
            ).map((column) => ({ id: column.name, label: column.name }))}
            label={t('label.primary-key')}
            placeholder={t('hospitalIntegration.choosePrimaryKey')}
            selectedKey={value.primaryKey || null}
            onSelectionChange={(key) =>
              onChange({ ...value, primaryKey: String(key ?? '') })
            }>
            {(item) => <Select.Item {...item} />}
          </Select>
        </div>
      </section>
      {value.mode === 'CDC' && sourceConnection?.databaseType === 'Oracle' && (
        <section aria-labelledby="integration-oracle-options-title">
          <h2 id="integration-oracle-options-title">
            {t('hospitalCdc.oracleSettings')}
          </h2>
          <IntegrationOracleCdcOptions
            disabled={disabled}
            value={value.cdc}
            onChange={(cdc) => onChange({ ...value, cdc })}
          />
        </section>
      )}
      {value.mode === 'CDC' && sourceConnection?.databaseType === 'Mssql' && (
        <section aria-labelledby="integration-mssql-options-title">
          <h2 id="integration-mssql-options-title">
            {t('hospitalCdc.mssqlSettings')}
          </h2>
          <IntegrationMssqlCdcOptions
            disabled={disabled}
            value={value.cdc}
            onChange={(cdc) => onChange({ ...value, cdc })}
          />
        </section>
      )}
      <IntegrationExecutionSettings
        disabled={disabled}
        key={JSON.stringify([
          value.mode,
          value.sourceConnectionId,
          value.targetConnectionId,
        ])}
        value={value}
        onChange={onChange}
        onDraftChange={onRuntimeDraftChange}
        onValidityChange={onRuntimeValidityChange}
      />
      {sqlMode && (
        <>
          <section aria-labelledby="integration-query-title">
            <h2 id="integration-query-title">
              {t('hospitalIntegration.sourceQuery')}
            </h2>
            <TextArea
              isRequired
              className="hospital-integration__query-field"
              hint={t('hospitalIntegration.sourceQueryHint')}
              id="integration-source-query"
              isDisabled={disabled}
              label={t('hospitalIntegration.sourceQuery')}
              maxLength={SQL_QUERY_MAX_BYTES}
              rows={7}
              textAreaClassName="hospital-integration__query"
              value={value.sourceQuery ?? ''}
              onChange={(sourceQuery) =>
                onChange({
                  ...value,
                  sourceQuery,
                  primaryKey: routeLocked ? value.primaryKey : '',
                  fieldMappings: routeLocked ? value.fieldMappings : [],
                })
              }
            />
            <div className="hospital-integration__actions">
              <Button
                color="secondary"
                isDisabled={
                  disabled ||
                  queryLoading ||
                  !value.sourceSchema ||
                  !value.sourceQuery?.trim()
                }
                isLoading={queryLoading}
                onClick={onReadQuerySchema}>
                {t('hospitalIntegration.readQueryColumns')}
              </Button>
              {source?.columns.length ? (
                <p role="status">
                  {t('hospitalIntegration.queryColumnsLoaded', {
                    count: source.columns.length,
                  })}
                </p>
              ) : null}
            </div>
            {queryErrorCode && (
              <IntegrationDrawerAlert focusKey={queryErrorCode}>
                <p>{t(integrationErrorKey(queryErrorCode))}</p>
              </IntegrationDrawerAlert>
            )}
            <p className="hospital-integration__hint">
              {t('hospitalIntegration.mergeHint')}
            </p>
          </section>
          <section aria-labelledby="integration-schedule-title">
            <h2 id="integration-schedule-title">{t('label.schedule')}</h2>
            <Checkbox
              className="hospital-integration__schedule-toggle"
              isDisabled={disabled || routeLocked || initializationUnavailable}
              isSelected={initialization.enabled}
              label={t('hospitalIntegration.initializeBeforeDaily')}
              onChange={(enabled) =>
                onChange({
                  ...value,
                  initialization: { ...initialization, enabled },
                  schedule: enabled ? { ...schedule, enabled: true } : schedule,
                })
              }
            />
            {initializationUnavailable && (
              <p className="hospital-integration__hint">
                {t('hospitalIntegration.initializationPendingDailyHint')}
              </p>
            )}
            {initialization.enabled && (
              <div className="hospital-integration__initialization-config">
                <div className="hospital-integration__field-grid">
                  <div className="hospital-integration__date-field">
                    <label htmlFor="integration-initialization-start">
                      {t('hospitalIntegration.initializationStartDate')}
                    </label>
                    <input
                      required
                      aria-describedby="integration-initialization-date-hint"
                      disabled={routeDisabled}
                      id="integration-initialization-start"
                      max={new Date(
                        new Date(integrationToday() + 'T00:00:00Z').getTime() -
                          86400000
                      )
                        .toISOString()
                        .slice(0, 10)}
                      min="2000-01-01"
                      type="date"
                      value={initialization.startDate}
                      onChange={(event) =>
                        onChange({
                          ...value,
                          initialization: {
                            ...initialization,
                            startDate: event.target.value,
                          },
                        })
                      }
                    />
                    <p id="integration-initialization-date-hint">
                      {t('hospitalIntegration.initializationDateHint')}
                    </p>
                  </div>
                  <Select
                    id="integration-initialization-unit"
                    isDisabled={routeDisabled}
                    items={[
                      {
                        id: 'DAY',
                        label: t('hospitalIntegration.initializationDay'),
                      },
                      {
                        id: 'MONTH',
                        label: t('hospitalIntegration.initializationMonth'),
                      },
                    ]}
                    label={t('hospitalIntegration.initializationBatchUnit')}
                    selectedKey={initialization.batchUnit}
                    onSelectionChange={(key) => {
                      if (key === 'DAY' || key === 'MONTH') {
                        onChange({
                          ...value,
                          initialization: { ...initialization, batchUnit: key },
                        });
                      }
                    }}>
                    {(item) => <Select.Item {...item} />}
                  </Select>
                </div>
                <p className="hospital-integration__hint">
                  {t(
                    initialization.batchUnit === 'MONTH'
                      ? 'hospitalIntegration.initializationMonthlyHint'
                      : 'hospitalIntegration.initializationDailyHint'
                  )}
                </p>
                {preview && (
                  <div data-testid="integration-initialization-preview">
                    <p>
                      {t('hospitalIntegration.initializationPreviewHint', {
                        cutoff: cutoffDate,
                        count: preview.totalBatches,
                      })}
                    </p>
                    <dl className="hospital-integration__facts">
                      <div>
                        <dt>
                          {t('hospitalIntegration.initializationFirstBatch')}
                        </dt>
                        <dd>
                          [{preview.firstStartDate}, {preview.firstEndDate})
                        </dd>
                      </div>
                      <div>
                        <dt>
                          {t('hospitalIntegration.initializationLastBatch')}
                        </dt>
                        <dd>
                          [{preview.lastStartDate}, {preview.lastEndDate})
                        </dd>
                      </div>
                    </dl>
                  </div>
                )}
              </div>
            )}
            <Checkbox
              className="hospital-integration__schedule-toggle"
              isDisabled={
                disabled ||
                (initialization.enabled &&
                  initializationState?.status !== 'COMPLETED')
              }
              isSelected={schedule.enabled}
              label={t('hospitalIntegration.enableSchedule')}
              onChange={(enabled) =>
                onChange({ ...value, schedule: { ...schedule, enabled } })
              }
            />
            <div className="hospital-integration__field-grid">
              <Input
                isRequired
                id="integration-daily-time"
                isDisabled={disabled}
                label={t('hospitalIntegration.dailyTime')}
                maxLength={5}
                placeholder="HH:mm"
                value={schedule.dailyTime}
                onChange={(dailyTime) =>
                  onChange({ ...value, schedule: { ...schedule, dailyTime } })
                }
              />
              <Input
                isReadOnly
                id="integration-time-zone"
                label={t('label.timezone')}
                value={schedule.timeZone}
              />
            </div>
            <p className="hospital-integration__hint">
              {t('hospitalIntegration.scheduleHint')}
            </p>
          </section>
        </>
      )}
      {targetConnection?.databaseType === 'Doris' &&
      !routeLocked &&
      source?.columns.length &&
      (!sqlMode || value.primaryKey) ? (
        <section aria-labelledby="integration-target-create-title">
          <h2 id="integration-target-create-title">
            {t('hospitalIntegration.targetTableSetup')}
          </h2>
          <IntegrationTargetTableForm
            disabled={
              disabled ||
              tablesLoading ||
              queryLoading ||
              Boolean(queryErrorCode)
            }
            key={JSON.stringify([
              value.sourceConnectionId,
              sqlMode ? [source.schema, source.name] : source,
              value.sourceQuery,
              value.primaryKey,
              targetConnection.id,
              targetConnection.database,
            ])}
            mode={value.mode}
            primaryKey={value.primaryKey}
            source={
              sqlMode
                ? {
                    ...source,
                    columns: source.columns.map((column) => ({
                      ...column,
                      primaryKey: column.name === value.primaryKey,
                    })),
                  }
                : source
            }
            sourceConnectionId={value.sourceConnectionId}
            sourceQuery={value.sourceQuery}
            targetConnection={targetConnection}
            onBusyChange={automaticBusy}
            onCreated={onTargetTableCreated}
            onDraftChange={automaticDirty}
          />
        </section>
      ) : null}
      <section
        aria-labelledby="integration-mapping-title"
        data-testid="integration-field-mappings">
        <h2 id="integration-mapping-title">
          {t('hospitalIntegration.fieldMappings')}
        </h2>
        <p>{t('hospitalIntegration.mappingHint')}</p>
        {!source?.columns.length || !target?.columns.length ? (
          <p className="hospital-integration__mapping-empty">
            {t('hospitalIntegration.chooseTablesFirst')}
          </p>
        ) : (
          <div className="hospital-integration__mapping">
            {source.columns.map((column) => (
              <div
                className="hospital-integration__mapping-row"
                key={column.name}>
                <div>
                  <strong>{column.name}</strong>
                  <small>
                    {column.dataType}
                    {column.primaryKey ? ` · ${t('label.primary-key')}` : ''}
                    {column.nullable
                      ? ` · ${t('hospitalIntegration.nullable')}`
                      : ''}
                  </small>
                </div>
                <Select
                  aria-label={t('hospitalIntegration.mappingFor', {
                    field: column.name,
                  })}
                  isDisabled={routeDisabled}
                  items={[
                    {
                      id: '__omit__',
                      label: t('hospitalIntegration.omitField'),
                    },
                    ...target.columns.map((item) => ({
                      id: item.name,
                      label: item.name,
                      supportingText: item.dataType,
                    })),
                  ]}
                  selectedKey={
                    value.fieldMappings.find(
                      (item) => item.source === column.name
                    )?.target ?? '__omit__'
                  }
                  onSelectionChange={(key) =>
                    onChange({
                      ...value,
                      fieldMappings: [
                        ...value.fieldMappings.filter(
                          (item) => item.source !== column.name
                        ),
                        ...(key && key !== '__omit__'
                          ? [{ source: column.name, target: String(key) }]
                          : []),
                      ],
                    })
                  }>
                  {(item) => <Select.Item {...item} />}
                </Select>
              </div>
            ))}
          </div>
        )}
      </section>
      {value.mode === 'CDC' && (
        <section aria-labelledby="integration-task-preflight-title">
          <div className="hospital-integration__section-heading">
            <h2 id="integration-task-preflight-title">
              {t('hospitalCdc.taskPreflight')}
            </h2>
            <Button
              color="secondary"
              isDisabled={disabled || tablesLoading || preflightLoading}
              isLoading={preflightLoading}
              onClick={onPreflight}>
              {t('hospitalCdc.check')}
            </Button>
          </div>
          <IntegrationCdcPreflight
            errorCode={preflightErrorCode}
            loading={preflightLoading}
            result={preflight}
          />
          <p className="hospital-integration__hint">
            {t('hospitalCdc.draftHint')}
          </p>
        </section>
      )}
      <FormDrawerActions>
        <div className="hospital-integration__actions">
          <Button
            color="secondary"
            isDisabled={
              disabled ||
              tablesLoading ||
              queryLoading ||
              Boolean(queryErrorCode)
            }
            isLoading={validating}
            onClick={onValidate}>
            {t('label.validate')}
          </Button>
          <Button
            form="integration-task-form"
            isDisabled={
              disabled ||
              tablesLoading ||
              queryLoading ||
              Boolean(queryErrorCode)
            }
            isLoading={saving}
            type="submit">
            {t('label.save')}
          </Button>
          <Button color="tertiary" isDisabled={disabled} onClick={onCancel}>
            {t('label.cancel')}
          </Button>
        </div>
      </FormDrawerActions>
      {designerOpen && targetConnection?.databaseType === 'Doris' && (
        <DorisTableDesigner
          connection={targetConnection}
          navigationBlocked={navigationBlocked}
          source={
            source?.columns.length && (!sqlMode || value.primaryKey)
              ? {
                  connectionId: value.sourceConnectionId,
                  table: {
                    ...source,
                    columns: source.columns.map((column) => ({
                      ...column,
                      primaryKey: sqlMode
                        ? column.name === value.primaryKey
                        : column.primaryKey,
                    })),
                  },
                  mode: value.mode,
                  sourceQuery: value.sourceQuery,
                  primaryKey: value.primaryKey,
                }
              : undefined
          }
          onApplied={(structure, mappings) => {
            onTargetTableCreated(structure.table, mappings);
            setDesignerOpen(false);
          }}
          onBusyChange={manualBusy}
          onClose={() => setDesignerOpen(false)}
          onDiscardNavigation={onDiscardNavigation}
          onDraftChange={manualDirty}
          onKeepNavigation={onKeepNavigation}
        />
      )}
    </form>
  );
};
