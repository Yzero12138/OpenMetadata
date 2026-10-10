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
import { Input, Select } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import {
  IntegrationConnectionInput,
  IntegrationDatabaseType,
} from '../../rest/hospitalIntegrationAPI';
import { IntegrationConnectionFormProps } from './HospitalIntegrationPage.interface';
import { INTEGRATION_DATABASE_DEFAULTS } from './HospitalIntegrationUtils';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';

export const IntegrationConnectionForm = ({
  value,
  schemasText,
  editing,
  disabled,
  errors,
  onChange,
  onSchemasChange,
  onSave,
}: IntegrationConnectionFormProps) => {
  const { t } = useTranslation();
  const changeType = (type: string) => {
    if (
      type !== 'Postgres' &&
      type !== 'Oracle' &&
      type !== 'Mysql' &&
      type !== 'Mssql'
    ) {
      return;
    }
    const { oracleConnectionType: _oracleConnectionType, ...current } = value;
    const defaults = INTEGRATION_DATABASE_DEFAULTS[type];
    const schemas =
      type === 'Mysql'
        ? value.database
          ? [value.database]
          : []
        : defaults.schemas;
    onChange({
      ...current,
      databaseType: type,
      databaseVersion: '',
      port: defaults.port,
      schemas,
      ...(type === 'Oracle' ? { oracleConnectionType: 'SERVICE_NAME' } : {}),
    });
    onSchemasChange(schemas.join(', '));
  };
  const update = (patch: Partial<IntegrationConnectionInput>) =>
    onChange({ ...value, ...patch });
  const versions: Record<IntegrationDatabaseType, string> = {
    Oracle: '11g / 19c',
    Mssql: '2016 / 2019',
    Mysql: '8',
    Postgres: '17.2',
  };

  return (
    <form
      noValidate
      className="hospital-integration__form"
      data-testid="integration-connection-form"
      id="integration-connection-form"
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}>
      {errors.length > 0 && (
        <IntegrationDrawerAlert focusKey={errors}>
          <p>{t('hospitalIntegration.checkForm')}</p>
          <ul>
            {errors.map((error) => (
              <li key={error}>{t('hospitalIntegration.' + error)}</li>
            ))}
          </ul>
        </IntegrationDrawerAlert>
      )}
      <section aria-labelledby="connection-definition-title">
        <h3 id="connection-definition-title">
          {t('hospitalIntegration.connectionDefinition')}
        </h3>
        <div className="hospital-integration__field-grid">
          <Input
            isRequired
            hint={t('hospitalIntegration.nameHint')}
            id="connection-name"
            inputDataTestId="connection-name"
            isDisabled={disabled}
            label={t('label.name')}
            maxLength={63}
            value={value.name}
            onChange={(name) => update({ name })}
          />
          <Input
            isRequired
            id="connection-displayName"
            inputDataTestId="connection-displayName"
            isDisabled={disabled}
            label={t('label.display-name')}
            maxLength={120}
            value={value.displayName}
            onChange={(displayName) => update({ displayName })}
          />
          <Select
            isRequired
            id="connection-role"
            isDisabled={disabled}
            items={[
              { id: 'SOURCE', label: t('label.source') },
              { id: 'TARGET', label: t('label.target') },
            ]}
            label={t('label.role')}
            placeholder={t('label.role')}
            selectedKey={value.role}
            onSelectionChange={(key) => {
              if (key === 'SOURCE' || key === 'TARGET') {
                update({ role: key });
              }
            }}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <Select
            isRequired
            id="connection-databaseType"
            isDisabled={disabled}
            items={(['Oracle', 'Mssql', 'Mysql', 'Postgres'] as const).map(
              (type) => ({
                id: type,
                label: t('hospitalIntegration.databaseTypes.' + type),
              })
            )}
            label={t('label.type')}
            placeholder={t('label.type')}
            selectedKey={value.databaseType}
            onSelectionChange={(key) => changeType(String(key))}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <Input
            isRequired
            hint={t('hospitalIntegration.databaseVersionHint')}
            id="connection-databaseVersion"
            inputDataTestId="connection-databaseVersion"
            isDisabled={disabled}
            label={t('label.database-version')}
            maxLength={30}
            placeholder={versions[value.databaseType]}
            value={value.databaseVersion}
            onChange={(databaseVersion) => update({ databaseVersion })}
          />
          <Select
            id="connection-enabled"
            isDisabled={disabled}
            items={[
              { id: 'enabled', label: t('label.enabled') },
              { id: 'disabled', label: t('label.disabled') },
            ]}
            label={t('label.status')}
            placeholder={t('label.status')}
            selectedKey={value.enabled ? 'enabled' : 'disabled'}
            onSelectionChange={(key) => update({ enabled: key === 'enabled' })}>
            {(item) => <Select.Item {...item} />}
          </Select>
        </div>
      </section>
      <section aria-labelledby="connection-endpoint-title">
        <h3 id="connection-endpoint-title">
          {t('hospitalIntegration.connectionEndpoint')}
        </h3>
        <div className="hospital-integration__field-grid">
          <Input
            isRequired
            id="connection-host"
            inputDataTestId="connection-host"
            isDisabled={disabled}
            label={t('hospitalIntegration.host')}
            maxLength={253}
            value={value.host}
            onChange={(host) => update({ host })}
          />
          <Input
            isRequired
            id="connection-port"
            inputDataTestId="connection-port"
            isDisabled={disabled}
            label={t('label.port')}
            type="number"
            value={
              Number.isFinite(value.port) && value.port > 0
                ? String(value.port)
                : ''
            }
            onChange={(port) => update({ port: Number(port) })}
          />
          <Input
            isRequired
            id="connection-database"
            inputDataTestId="connection-database"
            isDisabled={disabled}
            label={t('label.database')}
            maxLength={127}
            value={value.database}
            onChange={(database) => {
              update({
                database,
                ...(value.databaseType === 'Mysql'
                  ? { schemas: database ? [database] : [] }
                  : {}),
              });
              if (value.databaseType === 'Mysql') {
                onSchemasChange(database);
              }
            }}
          />
          {value.databaseType === 'Oracle' && (
            <Select
              isRequired
              id="connection-oracleConnectionType"
              isDisabled={disabled}
              items={[
                {
                  id: 'SERVICE_NAME',
                  label: t('hospitalIntegration.oracleServiceName'),
                },
                { id: 'SID', label: t('hospitalIntegration.oracleSid') },
              ]}
              label={t('hospitalIntegration.oracleConnectionType')}
              placeholder={t('hospitalIntegration.oracleConnectionType')}
              selectedKey={value.oracleConnectionType ?? 'SERVICE_NAME'}
              onSelectionChange={(key) => {
                if (key === 'SERVICE_NAME' || key === 'SID') {
                  update({ oracleConnectionType: key });
                }
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
          )}
          <Select
            isRequired
            id="connection-tlsMode"
            isDisabled={disabled}
            items={[
              { id: 'VERIFY', label: t('hospitalIntegration.tlsVerify') },
              { id: 'DISABLED', label: t('hospitalIntegration.tlsDisabled') },
            ]}
            label={t('hospitalIntegration.tlsMode')}
            placeholder={t('hospitalIntegration.tlsMode')}
            selectedKey={value.tlsMode}
            onSelectionChange={(key) => {
              if (key === 'VERIFY' || key === 'DISABLED') {
                update({ tlsMode: key });
              }
            }}>
            {(item) => <Select.Item {...item} />}
          </Select>
          <div className="hospital-integration__field-wide">
            <Input
              isRequired
              hint={t(
                value.databaseType === 'Mysql'
                  ? 'hospitalIntegration.mysqlScopeHint'
                  : 'hospitalIntegration.schemasHint'
              )}
              id="connection-schemas"
              inputDataTestId="connection-schemas"
              isDisabled={disabled || value.databaseType === 'Mysql'}
              label={t('label.schema-plural')}
              value={
                value.databaseType === 'Mysql' ? value.database : schemasText
              }
              onChange={onSchemasChange}
            />
          </div>
        </div>
      </section>
      <section aria-labelledby="connection-credentials-title">
        <h3 id="connection-credentials-title">
          {t('hospitalIntegration.connectionCredentials')}
        </h3>
        <div className="hospital-integration__field-grid">
          <Input
            isRequired
            autoComplete="off"
            id="connection-username"
            inputDataTestId="connection-username"
            isDisabled={disabled}
            label={t('label.username')}
            maxLength={128}
            value={value.username}
            onChange={(username) => update({ username })}
          />
          <Input
            autoComplete="new-password"
            hint={
              editing
                ? t('hospitalIntegration.passwordUnchangedHint')
                : undefined
            }
            id="connection-password"
            inputDataTestId="connection-password"
            isDisabled={disabled}
            isRequired={!editing}
            label={t('label.password')}
            maxLength={4096}
            type="password"
            value={value.password ?? ''}
            onChange={(password) => update({ password })}
          />
        </div>
      </section>
    </form>
  );
};
