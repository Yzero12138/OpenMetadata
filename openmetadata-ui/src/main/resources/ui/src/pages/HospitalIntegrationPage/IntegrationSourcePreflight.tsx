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
import { Button, Select } from '@openmetadata/ui-core-components';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import {
  IntegrationCdcOptions,
  IntegrationCdcPreflightResult,
  preflightIntegrationConnection,
} from '../../rest/hospitalIntegrationAPI';
import { IntegrationSourcePreflightProps } from './HospitalIntegrationPage.interface';
import { integrationErrorKey } from './HospitalIntegrationUtils';
import { IntegrationCdcPreflight } from './IntegrationCdcPreflight';
import { IntegrationOracleCdcOptions } from './IntegrationOracleCdcOptions';
import { useIntegrationScopedRead } from './useIntegrationScopedRead';
import { useIntegrationTables } from './useIntegrationTables';

export const IntegrationSourcePreflight = ({
  connection,
  scope,
  onClose,
}: IntegrationSourcePreflightProps) => {
  const { t } = useTranslation();
  const [schema, setSchema] = useState(connection.schemas[0] ?? '');
  const [table, setTable] = useState('');
  const [cdc, setCdc] = useState<IntegrationCdcOptions>();
  const tables = useIntegrationTables(
    connection.id,
    schema,
    table,
    true,
    connection.version,
    scope
  );
  const key = JSON.stringify([
    'source',
    scope,
    connection.id,
    connection.version,
    schema,
    table,
    cdc,
  ]);
  const preflight =
    useIntegrationScopedRead<IntegrationCdcPreflightResult>(key);

  return (
    <FormDrawer
      destroyOnClose
      isOpen
      className="hospital-integration hospital-integration--drawer"
      data-testid="integration-source-preflight-drawer"
      footer={
        <Button
          color="secondary"
          isDisabled={!schema || !table || tables.loading || preflight.loading}
          isLoading={preflight.loading}
          onClick={() =>
            void preflight.execute((signal) =>
              preflightIntegrationConnection(
                connection.id,
                { schema, table, ...(cdc ? { cdc } : {}) },
                signal
              )
            )
          }>
          {t('hospitalCdc.check')}
        </Button>
      }
      title={t('hospitalCdc.sourcePreflight')}
      width={720}
      onClose={onClose}>
      <div className="hospital-integration__form">
        <section>
          <h2>{connection.displayName}</h2>
          <div className="hospital-integration__field-grid">
            <Select
              isRequired
              id="integration-preflight-source-schema"
              items={connection.schemas.map((item) => ({
                id: item,
                label: item,
              }))}
              label={t('hospitalIntegration.sourceSchema')}
              selectedKey={schema || null}
              onSelectionChange={(value) => {
                setSchema(String(value ?? ''));
                setTable('');
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
            <Select
              isRequired
              id="integration-preflight-source-table"
              isDisabled={tables.loading}
              items={tables.tables
                .filter((item) => item.schema === schema)
                .map((item) => ({ id: item.name, label: item.name }))}
              label={t('hospitalIntegration.sourceTable')}
              selectedKey={table || null}
              onSelectionChange={(value) => setTable(String(value ?? ''))}>
              {(item) => <Select.Item {...item} />}
            </Select>
          </div>
          {tables.errorCode && (
            <p className="hospital-integration__inline-error" role="alert">
              {t(integrationErrorKey(tables.errorCode))}
            </p>
          )}
        </section>
        {connection.databaseType === 'Oracle' && (
          <section>
            <h2>{t('hospitalCdc.oracleSettings')}</h2>
            <IntegrationOracleCdcOptions
              disabled={preflight.loading}
              value={cdc}
              onChange={setCdc}
            />
          </section>
        )}
        <section>
          <IntegrationCdcPreflight
            sourceOnly
            errorCode={preflight.errorCode}
            loading={preflight.loading}
            result={preflight.data}
          />
        </section>
      </div>
    </FormDrawer>
  );
};
