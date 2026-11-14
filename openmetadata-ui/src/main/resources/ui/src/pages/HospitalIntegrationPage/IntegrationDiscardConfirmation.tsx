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
import { Button } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { IntegrationDiscardConfirmationProps } from './HospitalIntegrationPage.interface';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';

export const IntegrationDiscardConfirmation = ({
  onContinue,
  onDiscard,
}: IntegrationDiscardConfirmationProps) => {
  const { t } = useTranslation();

  return (
    <IntegrationDrawerAlert focusKey="discard-changes">
      <h3>{t('message.discard-your-changes')}</h3>
      <p>{t('message.unsaved-form-data')}</p>
      <div className="hospital-integration__actions">
        <Button color="secondary" onClick={onContinue}>
          {t('label.continue-editing')}
        </Button>
        <Button color="primary-destructive" onClick={onDiscard}>
          {t('label.discard')}
        </Button>
      </div>
    </IntegrationDrawerAlert>
  );
};
