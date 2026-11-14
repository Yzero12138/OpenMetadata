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
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { ROUTES } from '../../constants/constants';
import { IntegrationPageHeaderProps } from './HospitalIntegrationPage.interface';
import { getIntegrationCollectionSearch } from './HospitalIntegrationRouteUtils';

export const IntegrationPageHeader = ({
  title,
  description,
  action,
  detail,
}: IntegrationPageHeaderProps) => {
  const { t } = useTranslation();
  const { search } = useLocation();

  return (
    <>
      <nav
        aria-label={t('hospitalIntegration.title')}
        className="hospital-integration__breadcrumb">
        <Link to={ROUTES.MY_DATA}>{t('hospitalNavigation.workbench')}</Link>
        <span aria-hidden="true">/</span>
        <span>{t('hospitalIntegration.title')}</span>
        <span aria-hidden="true">/</span>
        {detail ? (
          <>
            <Link
              to={
                ROUTES.HOSPITAL_INTEGRATION_TASKS +
                getIntegrationCollectionSearch(search)
              }>
              {t('hospitalIntegration.tasks')}
            </Link>
            <span aria-hidden="true">/</span>
          </>
        ) : null}
        <span aria-current="page">{title}</span>
      </nav>
      <header className="hospital-integration__header">
        <div>
          <h1>{title}</h1>
          {description && <p>{description}</p>}
        </div>
        {action}
      </header>
    </>
  );
};
