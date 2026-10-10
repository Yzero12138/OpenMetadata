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
import { Button, Input, Select } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { FormDrawerActions } from '../../components/common/atoms/drawer/FormDrawerActions';
import { IntegrationTable } from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import {
  findIntegrationTable,
  integrationTableKey,
} from './HospitalIntegrationUtils';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';

export const IntegrationTaskForm = ({
  value,
  sourceTables,
  connections,
  tablesLoading,
  onReloadTables,
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
  const modes = sourceConnection?.supportedModes ?? [];

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
                    connection.enabled && connection.role === 'SOURCE'
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
                onChange({
                  ...value,
                  sourceConnectionId: connection.id,
                  sourceSchema: '',
                  sourceTable: '',
                  targetSchema: '',
                  targetTable: '',
                  primaryKey: '',
                  fieldMappings: [],
                  mode: connection.supportedModes.includes(value.mode)
                    ? value.mode
                    : 'FULL',
                });
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
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
                    connection.enabled && connection.role === 'TARGET'
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
                value.mode === 'CDC' ? 'cdcHint' : 'fullHint'
              }`
            )}
            id="integration-mode"
            isDisabled={disabled || !sourceConnection}
            items={modes.map((mode) => ({
              id: mode,
              label: t(
                mode === 'CDC'
                  ? 'hospitalIntegration.cdc'
                  : 'hospitalIntegration.full'
              ),
            }))}
            label={t('label.mode')}
            placeholder={t('label.mode')}
            selectedKey={modes.includes(value.mode) ? value.mode : null}
            onSelectionChange={(key) => {
              if ((key === 'FULL' || key === 'CDC') && modes.includes(key)) {
                onChange({ ...value, mode: key });
              }
            }}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <Select
            hint={t('hospitalIntegration.primaryKeyHint')}
            id="integration-primary-key"
            isDisabled={disabled || !source}
            isRequired={value.mode === 'CDC'}
            items={(
              source?.columns.filter((column) => column.primaryKey) ?? []
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
            isDisabled={disabled || tablesLoading}
            isLoading={validating}
            onClick={onValidate}>
            {t('label.validate')}
          </Button>
          <Button
            form="integration-task-form"
            isDisabled={disabled || tablesLoading}
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
