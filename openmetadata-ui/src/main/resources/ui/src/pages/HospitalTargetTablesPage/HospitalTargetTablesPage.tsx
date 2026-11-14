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
import { Plus, RefreshCw01 } from '@untitledui/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import PageLayoutV1 from '../../components/PageLayoutV1/PageLayoutV1';
import { ROUTES } from '../../constants/constants';
import { useApplicationStore } from '../../hooks/useApplicationStore';
import {
  DorisTableStructure,
  getDorisTableStructure,
  getIntegrationConnections,
  getIntegrationFailure,
  getIntegrationTableOptions,
  IntegrationConnection,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';
import { showSuccessToast } from '../../utils/ToastUtils';
import '../HospitalIntegrationPage/hospital-integration.less';
import { HospitalIntegrationWorkspaceProps } from '../HospitalIntegrationPage/HospitalIntegrationPage.interface';
import {
  IntegrationCollectionPagination,
  useIntegrationCollection,
} from '../HospitalIntegrationPage/IntegrationCollection';
import { IntegrationDrawerAlert } from '../HospitalIntegrationPage/IntegrationDrawerAlert';
import { IntegrationPageHeader } from '../HospitalIntegrationPage/IntegrationPageHeader';
import { useIntegrationNavigationGuard } from '../HospitalIntegrationPage/useIntegrationNavigationGuard';
import { DorisTableDesigner } from './DorisTableDesigner';
import {
  dorisChangeActive,
  dorisDesignErrorKey,
} from './DorisTableDesignUtils';
import { DorisTableStructureView } from './DorisTableStructureView';
import './hospital-table-design.less';

export const HospitalTargetTablesWorkspace = ({
  isAdmin,
}: HospitalIntegrationWorkspaceProps) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { params, update, page } = useIntegrationCollection();
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [tables, setTables] = useState<IntegrationTable[]>([]);
  const [loading, setLoading] = useState<Record<string, boolean>>({
    connections: isAdmin,
  });
  const [errorCode, setErrorCode] = useState<string>();
  const [reload, setReload] = useState(0);
  const [selection, setSelection] = useState<{
    table: IntegrationTable;
    action: 'view' | 'add';
  }>();
  const [structure, setStructure] = useState<DorisTableStructure>();
  const [designer, setDesigner] = useState<{
    existing?: DorisTableStructure;
  }>();
  const [designerBusy, setDesignerBusy] = useState(false);
  const [designerDirty, setDesignerDirty] = useState(false);
  const tableGeneration = useRef(0);
  const dorisConnections = connections.filter(
    (item) =>
      item.enabled && item.role === 'TARGET' && item.databaseType === 'Doris'
  );
  const connection =
    dorisConnections.find((item) => item.id === params.get('target')) ??
    dorisConnections[0];
  const busy = designerBusy || Object.values(loading).some(Boolean);
  const guard = useIntegrationNavigationGuard({
    enabled: Boolean(designer && (designerBusy || designerDirty)),
    locked: designerBusy,
  });
  const onBusyChange = useCallback(
    (value: boolean) => setDesignerBusy(value),
    []
  );
  const onDraftChange = useCallback(
    (value: boolean) => setDesignerDirty(value),
    []
  );

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    const controller = new AbortController();
    setLoading((value) => ({ ...value, connections: true }));
    setErrorCode(undefined);
    void getIntegrationConnections(controller.signal)
      .then((items) => {
        if (!controller.signal.aborted) {
          setConnections(items);
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading((value) => ({ ...value, connections: false }));
        }
      });

    return () => controller.abort();
  }, [isAdmin, reload]);

  useEffect(() => {
    setTables([]);
    setSelection(undefined);
    setStructure(undefined);
    setLoading((value) => ({ ...value, structure: false, tables: false }));
    if (!isAdmin || !connection) {
      return;
    }
    const controller = new AbortController();
    const generation = ++tableGeneration.current;
    setLoading((value) => ({ ...value, tables: true }));
    void getIntegrationTableOptions(connection.id, controller.signal)
      .then((items) => {
        if (
          !controller.signal.aborted &&
          generation === tableGeneration.current
        ) {
          setTables(items);
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading((value) => ({ ...value, tables: false }));
        }
      });

    return () => controller.abort();
  }, [isAdmin, connection?.id, reload]);

  useEffect(() => {
    if (!selection || !connection) {
      return;
    }
    const controller = new AbortController();
    setStructure(undefined);
    setErrorCode(undefined);
    setLoading((value) => ({ ...value, structure: true }));
    void getDorisTableStructure(
      connection.id,
      selection.table.schema,
      selection.table.name,
      controller.signal
    )
      .then((result) => {
        if (controller.signal.aborted) {
          return;
        }
        if (selection.action === 'add') {
          setLoading((value) => ({ ...value, structure: false }));
          if (result.model !== 'UNIQUE') {
            setErrorCode('TARGET_TABLE_MODEL_UNSUPPORTED');
          } else if (dorisChangeActive(result.change?.state)) {
            setErrorCode('TARGET_SCHEMA_CHANGE_ACTIVE');
          } else if (result.columns.length >= 128) {
            setErrorCode('TABLE_COLUMN_LIMIT_EXCEEDED');
          } else {
            setDesigner({ existing: result });
          }
          setSelection(undefined);
        } else {
          setStructure(result);
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading((value) => ({ ...value, structure: false }));
        }
      });

    return () => controller.abort();
  }, [selection, connection?.id]);

  const closeDesigner = () => {
    setDesigner(undefined);
    setDesignerDirty(false);
    setDesignerBusy(false);
  };
  const newTask = (table: IntegrationTable) => {
    if (!connection || busy) {
      return;
    }
    navigate(ROUTES.HOSPITAL_INTEGRATION_TASKS, {
      state: {
        dorisTarget: {
          connectionId: connection.id,
          schema: table.schema,
          table: table.name,
        },
      },
    });
  };
  const query = (params.get('q') ?? '').trim().toLocaleLowerCase();
  const filtered = tables.filter((table) =>
    table.name.toLocaleLowerCase().includes(query)
  );
  const safePage = Math.min(page, Math.max(1, Math.ceil(filtered.length / 25)));

  return (
    <main
      className="hospital-integration hospital-table-design"
      data-testid="hospital-target-tables">
      <IntegrationPageHeader
        action={
          isAdmin && (
            <Button
              iconLeading={Plus}
              isDisabled={!connection || busy || Boolean(designer)}
              onClick={() => setDesigner({})}>
              {t('hospitalTableDesign.create')}
            </Button>
          )
        }
        description={t('hospitalTableDesign.description')}
        title={t('hospitalTableDesign.title')}
      />
      {!isAdmin ? (
        <section className="hospital-integration__empty" role="status">
          <h2>{t('hospitalIntegration.adminOnly')}</h2>
          <p>{t('hospitalIntegration.adminOnlyHint')}</p>
        </section>
      ) : (
        <>
          <div className="hospital-table-design__toolbar">
            <Select
              id="doris-management-connection"
              isDisabled={Boolean(designer) || loading.connections}
              items={dorisConnections.map((item) => ({
                id: item.id,
                label: item.displayName,
              }))}
              label={t('hospitalIntegration.targetConnection')}
              placeholder={t('hospitalIntegration.chooseConnection')}
              selectedKey={connection?.id ?? null}
              onSelectionChange={(key) => {
                if (key) {
                  update('target', String(key));
                }
              }}>
              {(item) => <Select.Item {...item} />}
            </Select>
            <Input
              id="doris-management-search"
              label={t('label.search')}
              value={params.get('q') ?? ''}
              onChange={(value) => update('q', value)}
            />
            <Button
              color="secondary"
              iconLeading={RefreshCw01}
              isDisabled={busy || Boolean(designer)}
              onClick={() => setReload((value) => value + 1)}>
              {t('label.refresh')}
            </Button>
          </div>
          {errorCode && !selection && (
            <IntegrationDrawerAlert focusKey={errorCode}>
              <p>{t(dorisDesignErrorKey(errorCode))}</p>
            </IntegrationDrawerAlert>
          )}
          {loading.connections || loading.tables ? (
            <p role="status">{t('label.loading')}</p>
          ) : !connection ? (
            <section className="hospital-integration__empty">
              <h2>{t('hospitalTableDesign.noTarget')}</h2>
              <Button
                color="secondary"
                onClick={() => navigate(ROUTES.HOSPITAL_INTEGRATION_SOURCES)}>
                {t('hospitalNavigation.sources')}
              </Button>
            </section>
          ) : !filtered.length ? (
            <section className="hospital-integration__empty" role="status">
              <h2>{t('hospitalTableDesign.noTables')}</h2>
              <p>{t('hospitalTableDesign.emptyHint')}</p>
            </section>
          ) : (
            <>
              <div className="hospital-table-design__table-scroll">
                <table className="hospital-table-design__structure-table hospital-table-design__list-table">
                  <thead>
                    <tr>
                      <th scope="col">
                        {t('hospitalIntegration.targetTable')}
                      </th>
                      <th scope="col">{t('label.database')}</th>
                      <th scope="col">{t('label.action-plural')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filtered
                      .slice((safePage - 1) * 25, safePage * 25)
                      .map((table) => (
                        <tr key={table.schema + '.' + table.name}>
                          <th
                            className="hospital-table-design__table-name"
                            scope="row">
                            {table.name}
                          </th>
                          <td>{table.schema}</td>
                          <td>
                            <div className="hospital-integration__actions">
                              <Button
                                color="link-color"
                                isDisabled={busy || Boolean(designer)}
                                onClick={() =>
                                  setSelection({ table, action: 'view' })
                                }>
                                {t('hospitalTableDesign.viewStructure')}
                              </Button>
                              <Button
                                color="link-color"
                                isDisabled={busy || Boolean(designer)}
                                onClick={() =>
                                  setSelection({ table, action: 'add' })
                                }>
                                {t('hospitalTableDesign.addColumns')}
                              </Button>
                              <Button
                                color="link-color"
                                isDisabled={busy || Boolean(designer)}
                                onClick={() => newTask(table)}>
                                {t('hospitalTableDesign.useInTask')}
                              </Button>
                            </div>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              <IntegrationCollectionPagination
                page={safePage}
                total={filtered.length}
                onPageChange={(value) => update('page', String(value))}
              />
            </>
          )}
          {loading.structure && selection?.action === 'add' && (
            <p role="status">{t('label.loading')}</p>
          )}
          <FormDrawer
            destroyOnClose
            className="hospital-integration__drawer"
            data-testid="doris-structure-drawer"
            footer={
              <>
                <Button
                  color="secondary"
                  onClick={() => setSelection(undefined)}>
                  {t('label.close')}
                </Button>
                <Button
                  color="secondary"
                  isDisabled={
                    !structure ||
                    busy ||
                    structure.model !== 'UNIQUE' ||
                    dorisChangeActive(structure.change?.state)
                  }
                  onClick={() => {
                    if (structure) {
                      setDesigner({ existing: structure });
                      setSelection(undefined);
                    }
                  }}>
                  {t('hospitalTableDesign.addColumns')}
                </Button>
                <Button
                  isDisabled={!structure || busy}
                  onClick={() => {
                    if (structure) {
                      newTask(structure.table);
                    }
                  }}>
                  {t('hospitalTableDesign.useInTask')}
                </Button>
              </>
            }
            isOpen={selection?.action === 'view'}
            isSubmitting={Boolean(loading.structure)}
            title={t('hospitalTableDesign.viewStructure')}
            width={1000}
            onClose={() => setSelection(undefined)}>
            {errorCode && (
              <IntegrationDrawerAlert focusKey={errorCode}>
                <p>{t(dorisDesignErrorKey(errorCode))}</p>
              </IntegrationDrawerAlert>
            )}
            {loading.structure ? (
              <p role="status">{t('label.loading')}</p>
            ) : (
              structure && <DorisTableStructureView structure={structure} />
            )}
          </FormDrawer>
          {designer && connection && (
            <DorisTableDesigner
              connection={connection}
              existing={designer.existing}
              navigationBlocked={guard.blocked}
              onApplied={() => {
                showSuccessToast(
                  t(
                    designer.existing
                      ? 'hospitalTableDesign.fieldsAdded'
                      : 'hospitalIntegration.targetTableCreated'
                  )
                );
                closeDesigner();
                setReload((value) => value + 1);
              }}
              onBusyChange={onBusyChange}
              onClose={closeDesigner}
              onDiscardNavigation={() => guard.proceed(closeDesigner)}
              onDraftChange={onDraftChange}
              onKeepNavigation={guard.stay}
            />
          )}
        </>
      )}
    </main>
  );
};

const HospitalTargetTablesPage = () => {
  const { t } = useTranslation();
  const isAdmin = useApplicationStore(
    (state) => state.currentUser?.isAdmin === true
  );

  return (
    <PageLayoutV1 pageTitle={t('hospitalTableDesign.title')}>
      <HospitalTargetTablesWorkspace isAdmin={isAdmin} />
    </PageLayoutV1>
  );
};

export default HospitalTargetTablesPage;
