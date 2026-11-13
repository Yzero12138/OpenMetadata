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
import { Input } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { IntegrationOracleCdcOptionsProps } from './HospitalIntegrationPage.interface';

export const IntegrationOracleCdcOptions = ({
  value,
  disabled,
  onChange,
}: IntegrationOracleCdcOptionsProps) => {
  const { t } = useTranslation();

  return (
    <div className="hospital-integration__field-grid">
      <Input
        hint={t('hospitalCdc.databaseNameHint')}
        id="integration-cdc-database-name"
        isDisabled={disabled}
        label={t('hospitalCdc.databaseName')}
        value={value?.databaseName ?? ''}
        onChange={(databaseName) =>
          onChange({ ...value, databaseName: databaseName || undefined })
        }
      />
      <Input
        hint={t('hospitalCdc.timeZoneHint')}
        id="integration-cdc-server-time-zone"
        isDisabled={disabled}
        label={t('hospitalCdc.serverTimeZone')}
        placeholder="Asia/Shanghai"
        value={value?.serverTimeZone ?? ''}
        onChange={(serverTimeZone) =>
          onChange({ ...value, serverTimeZone: serverTimeZone || undefined })
        }
      />
      {(value?.pdbName || value?.rootServiceName) && (
        <>
          <Input
            isReadOnly
            id="integration-cdc-pdb"
            label={t('hospitalCdc.pdbName')}
            value={value.pdbName ?? ''}
          />
          <Input
            isReadOnly
            id="integration-cdc-root"
            label={t('hospitalCdc.rootServiceName')}
            value={value.rootServiceName ?? ''}
          />
          <p className="hospital-integration__field-wide hospital-integration__hint">
            {t('hospitalCdc.container')}
          </p>
        </>
      )}
    </div>
  );
};
