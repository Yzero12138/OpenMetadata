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
import { IntegrationTable } from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskFormProps } from './HospitalIntegrationPage.interface';
import {
  findIntegrationTable,
  integrationTableKey,
} from './HospitalIntegrationUtils';

export const IntegrationTaskForm = ({
  value,
  sourceTables,
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
    const nextSource = role === 'source' ? table : source;
    const nextTarget = role === 'target' ? table : target;
    onChange({
      ...value,
      [`${role}Schema`]: table.schema,
      [`${role}Table`]: table.name,
      primaryKey:
        role === 'source'
          ? table.columns.find((column) => column.primaryKey)?.name ?? ''
          : value.primaryKey,
      fieldMappings:
        nextSource?.columns
          .filter((column) =>
            nextTarget?.columns.some((item) => item.name === column.name)
          )
          .map((column) => ({ source: column.name, target: column.name })) ??
        [],
    });
  };

  return (
    <form
      noValidate
      className="hospital-integration__form"
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}>
      {errors.length > 0 && (
        <div className="hospital-integration__alert" role="alert">
          <p>{t('hospitalIntegration.checkForm')}</p>
          <ul>
            {errors.map((error) => (
              <li key={error}>{t(`hospitalIntegration.${error}`)}</li>
            ))}
          </ul>
        </div>
      )}
      <section aria-labelledby="integration-basic-title">
        <h2 id="integration-basic-title">
          {t('hospitalIntegration.taskSetup')}
        </h2>
        <div className="hospital-integration__field-grid">
          <Input
            isRequired
            hint={t('hospitalIntegration.nameHint')}
            isDisabled={disabled}
            label={t('label.name')}
            value={value.name}
            onChange={(name) => onChange({ ...value, name })}
          />
          <Input
            isRequired
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
        {tablesUnavailable && (
          <p className="hospital-integration__inline-error" role="alert">
            {t('hospitalIntegration.tablesUnavailable')}
          </p>
        )}
        <div className="hospital-integration__field-grid">
          <div>
            <p className="hospital-integration__connection-label">
              {t('hospitalIntegration.sourceConnection')}
            </p>
            <Select
              isRequired
              emptyState={t('hospitalIntegration.noTables')}
              isDisabled={disabled || sourceTables.length === 0}
              items={tableItems(sourceTables)}
              label={t('hospitalIntegration.sourceTable')}
              placeholder={t('hospitalIntegration.chooseTable')}
              selectedKey={source ? integrationTableKey(source) : null}
              onSelectionChange={(key) => selectTable('source', String(key))}>
              {(item) => <Select.Item {...item} />}
            </Select>
          </div>
          <div>
            <p className="hospital-integration__connection-label">
              {t('hospitalIntegration.targetConnection')}
            </p>
            <Select
              isRequired
              emptyState={t('hospitalIntegration.noTables')}
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
            isDisabled={disabled}
            items={[
              { id: 'FULL', label: t('hospitalIntegration.full') },
              { id: 'CDC', label: t('hospitalIntegration.cdc') },
            ]}
            label={t('label.mode')}
            selectedKey={value.mode}
            onSelectionChange={(key) => {
              if (key === 'FULL' || key === 'CDC') {
                onChange({ ...value, mode: key });
              }
            }}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <Select
            hint={t('hospitalIntegration.primaryKeyHint')}
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
      <section aria-labelledby="integration-mapping-title">
        <h2 id="integration-mapping-title">
          {t('hospitalIntegration.fieldMappings')}
        </h2>
        <p>{t('hospitalIntegration.mappingHint')}</p>
        {!source || !target ? (
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
      <div className="hospital-integration__form-actions">
        <Button
          color="secondary"
          isDisabled={disabled}
          isLoading={validating}
          onClick={onValidate}>
          {t('label.validate')}
        </Button>
        <Button isDisabled={disabled} isLoading={saving} type="submit">
          {t('label.save')}
        </Button>
        <Button color="tertiary" isDisabled={disabled} onClick={onCancel}>
          {t('label.cancel')}
        </Button>
      </div>
    </form>
  );
};
