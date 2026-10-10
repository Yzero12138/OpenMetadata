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
import { Plus, RefreshCw01 } from '@untitledui/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import PageLayoutV1 from '../../components/PageLayoutV1/PageLayoutV1';
import { useApplicationStore } from '../../hooks/useApplicationStore';
import {
  getIntegrationConnections,
  getIntegrationFailure,
  IntegrationConnection,
} from '../../rest/hospitalIntegrationAPI';
import './hospital-integration.less';
import { HospitalIntegrationWorkspaceProps } from './HospitalIntegrationPage.interface';
import { integrationErrorKey } from './HospitalIntegrationUtils';
import {
  IntegrationCollectionPagination,
  IntegrationCollectionTools,
  useIntegrationCollection,
} from './IntegrationCollection';
import { IntegrationConnections } from './IntegrationConnections';
import { IntegrationPageHeader } from './IntegrationPageHeader';

export const HospitalDataSourcesWorkspace = ({
  isAdmin,
}: HospitalIntegrationWorkspaceProps) => {
  const { t } = useTranslation();
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [loading, setLoading] = useState(isAdmin);
  const [errorCode, setErrorCode] = useState<string>();
  const [reload, setReload] = useState(0);
  const [createRequest, setCreateRequest] = useState(0);
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  const mutation = useRef(false);
  const { params, update, reset, page } = useIntegrationCollection();
  const onBusyChange = useCallback((pending: boolean) => {
    generation.current += 1;
    mutation.current = pending;
    setBusy(pending);
  }, []);
  useEffect(() => {
    if (!isAdmin) {
      setLoading(false);

      return;
    }
    const controller = new AbortController();
    const requestGeneration = ++generation.current;
    setLoading(true);
    setErrorCode(undefined);
    void getIntegrationConnections(controller.signal)
      .then((items) => {
        if (
          !controller.signal.aborted &&
          requestGeneration === generation.current &&
          !mutation.current
        ) {
          setConnections(items);
        }
      })
      .catch((error: unknown) => {
        if (
          !controller.signal.aborted &&
          requestGeneration === generation.current
        ) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, [isAdmin, reload]);
  const query = (params.get('q') ?? '').trim().toLocaleLowerCase();
  const filtered = connections.filter(
    (item) =>
      (!query ||
        [item.name, item.displayName, item.host, item.database, ...item.schemas]
          .join(' ')
          .toLocaleLowerCase()
          .includes(query)) &&
      (!params.get('type') || item.databaseType === params.get('type')) &&
      (!params.get('role') || item.role === params.get('role')) &&
      (!params.get('enabled') || String(item.enabled) === params.get('enabled'))
  );
  const safePage = Math.min(page, Math.max(1, Math.ceil(filtered.length / 25)));

  return (
    <main className="hospital-integration" data-testid="hospital-data-sources">
      <IntegrationPageHeader
        action={
          isAdmin && (
            <Button
              iconLeading={Plus}
              isDisabled={busy || loading || Boolean(errorCode)}
              onClick={() => setCreateRequest((value) => value + 1)}>
              {t('hospitalIntegration.createConnection')}
            </Button>
          )
        }
        description={t('hospitalIntegration.sourcesDescription')}
        title={t('hospitalNavigation.sources')}
      />
      {!isAdmin ? (
        <section className="hospital-integration__empty" role="status">
          <h2>{t('hospitalIntegration.adminOnly')}</h2>
          <p>{t('hospitalIntegration.adminOnlyHint')}</p>
        </section>
      ) : (
        <>
          <div className="hospital-integration__list-toolbar">
            <IntegrationCollectionTools
              filters={[
                {
                  key: 'type',
                  label: t('label.type'),
                  options: ['Oracle', 'Mssql', 'Mysql', 'Postgres'].map(
                    (id) => ({
                      id,
                      label: t('hospitalIntegration.databaseTypes.' + id),
                    })
                  ),
                },
                {
                  key: 'role',
                  label: t('label.role'),
                  options: [
                    { id: 'SOURCE', label: t('label.source') },
                    { id: 'TARGET', label: t('label.target') },
                  ],
                },
                {
                  key: 'enabled',
                  label: t('label.status'),
                  options: [
                    { id: 'true', label: t('label.enabled') },
                    { id: 'false', label: t('label.disabled') },
                  ],
                },
              ]}
              params={params}
              onChange={update}
              onReset={reset}
            />
            <Button
              color="link-gray"
              iconLeading={RefreshCw01}
              isDisabled={busy || loading}
              onClick={() => setReload((value) => value + 1)}>
              {t('label.refresh')}
            </Button>
          </div>
          {errorCode && (
            <div className="hospital-integration__alert" role="alert">
              <p>{t(integrationErrorKey(errorCode))}</p>
            </div>
          )}
          {!errorCode && (
            <IntegrationConnections
              connections={connections}
              createRequest={createRequest}
              loading={loading}
              showHeading={false}
              visibleConnections={filtered.slice(
                (safePage - 1) * 25,
                safePage * 25
              )}
              onBusyChange={onBusyChange}
              onChange={setConnections}
            />
          )}
          {!loading && !errorCode && (
            <IntegrationCollectionPagination
              page={safePage}
              total={filtered.length}
              onPageChange={(next) => update('page', String(next))}
            />
          )}
        </>
      )}
    </main>
  );
};

const HospitalDataSourcesPage = () => {
  const { t } = useTranslation();
  const isAdmin = useApplicationStore(
    (state) => state.currentUser?.isAdmin === true
  );

  return (
    <PageLayoutV1 pageTitle={t('hospitalNavigation.sources')}>
      <HospitalDataSourcesWorkspace isAdmin={isAdmin} />
    </PageLayoutV1>
  );
};

export default HospitalDataSourcesPage;
