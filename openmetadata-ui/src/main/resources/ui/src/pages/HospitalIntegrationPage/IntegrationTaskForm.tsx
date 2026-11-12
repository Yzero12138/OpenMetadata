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
import { useTranslation } from 'react-i18next';
import { FormDrawerActions } from '../../components/common/atoms/drawer/FormDrawerActions';
import { IntegrationTable } from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import {
  DEFAULT_INTEGRATION_SCHEDULE,
  findIntegrationTable,
  integrationErrorKey,
  integrationModeKey,
  integrationTableKey,
  SQL_QUERY_MAX_BYTES,
} from './HospitalIntegrationUtils';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';
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
  onTargetTableBusyChange,
  onTargetTableDraftChange,
  onTargetTableCreated,
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
}: IntegrationTaskFormProps) => {
  const { t } = useTranslation();
  const sqlMode = value.mode === 'SQL_T1';
  const schedule = value.schedule ?? DEFAULT_INTEGRATION_SCHEDULE;
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
  const supportedModes = sourceConnection?.supportedModes ?? [];
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
              <li key={error}>{t(`hospitalIntegration.${error}`)}</li>
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
              isDisabled={disabled}
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
                  connection.supportedModes.includes(value.mode) ||
                  (value.mode === 'SQL_T1' &&
                    connection.supportedModes.includes('FULL'))
                    ? value.mode
                    : 'FULL';
                const nextSql = mode === 'SQL_T1';
                onChange({
                  ...value,
                  sourceConnectionId: connection.id,
                  sourceSchema: nextSql ? connection.schemas[0] ?? '' : '',
                  sourceTable: nextSql ? 'sql_result' : '',
                  targetSchema: '',
                  targetTable: '',
                  primaryKey: '',
                  fieldMappings: [],
                  mode,
                  sourceQuery: nextSql ? value.sourceQuery : undefined,
                  schedule: nextSql ? value.schedule : undefined,
                });
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
            {sqlMode ? (
              <Select
                isRequired
                id="integration-source-schema"
                isDisabled={disabled || !sourceConnection}
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
                isDisabled={disabled || sourceTables.length === 0}
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
              isDisabled={disabled}
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
              isDisabled={disabled || targetTables.length === 0}
              items={tableItems(targetTables)}
              label={t('hospitalIntegration.targetTable')}
              placeholder={t('hospitalIntegration.chooseTable')}
              selectedKey={target ? integrationTableKey(target) : null}
              onSelectionChange={(key) => selectTable('target', String(key))}>
              {(item) => <Select.Item {...item} />}
            </Select>
          </div>
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
            isDisabled={disabled || !sourceConnection}
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
                  onChange({ ...value, mode: key });

                  return;
                }
                const targetAllowed =
                  !nextSql || targetConnection?.databaseType === 'Doris';
                onChange({
                  ...value,
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
            isDisabled={disabled || !source}
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
                  primaryKey: '',
                  fieldMappings: [],
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
              isDisabled={disabled}
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
            onBusyChange={onTargetTableBusyChange}
            onCreated={onTargetTableCreated}
            onDraftChange={onTargetTableDraftChange}
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
                  isDisabled={disabled}
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
    </form>
  );
};
