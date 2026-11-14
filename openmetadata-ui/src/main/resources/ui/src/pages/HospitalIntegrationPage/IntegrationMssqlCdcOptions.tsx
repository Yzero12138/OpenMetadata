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
import { IntegrationCdcOptionsProps } from './HospitalIntegrationPage.interface';

export const IntegrationMssqlCdcOptions = ({
  value,
  disabled,
  onChange,
}: IntegrationCdcOptionsProps) => {
  const { t } = useTranslation();

  return (
    <div className="hospital-integration__field-grid">
      <Input
        hint={t('hospitalCdc.mssqlDatabaseHint')}
        id="integration-cdc-database-name"
        isDisabled={disabled}
        label={t('hospitalCdc.databaseName')}
        value={value?.databaseName ?? ''}
        onChange={(databaseName) =>
          onChange({
            databaseName: databaseName || undefined,
            serverTimeZone: value?.serverTimeZone,
          })
        }
      />
      <Input
        isRequired
        hint={t('hospitalCdc.timeZoneHint')}
        id="integration-cdc-server-time-zone"
        isDisabled={disabled}
        label={t('hospitalCdc.serverTimeZone')}
        placeholder="Asia/Shanghai"
        value={value?.serverTimeZone ?? ''}
        onChange={(serverTimeZone) =>
          onChange({
            databaseName: value?.databaseName,
            serverTimeZone: serverTimeZone || undefined,
          })
        }
      />
    </div>
  );
};
