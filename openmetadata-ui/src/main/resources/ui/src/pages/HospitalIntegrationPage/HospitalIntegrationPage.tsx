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
import { Button, Tabs } from '@openmetadata/ui-core-components';
import { ArrowLeft, ArrowRight, Plus, RefreshCw01 } from '@untitledui/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import PageLayoutV1 from '../../components/PageLayoutV1/PageLayoutV1';
import { useApplicationStore } from '../../hooks/useApplicationStore';
import {
  createIntegrationTask,
  getIntegrationConnections,
  getIntegrationFailure,
  getIntegrationStatus,
  getIntegrationTask,
  getIntegrationTasks,
  IntegrationConnection,
  IntegrationEngineStatus,
  IntegrationTask,
  IntegrationTaskInput,
  runIntegrationTask,
  stopIntegrationTask,
  syncIntegrationCatalog,
  updateIntegrationTask,
  validateIntegrationTask,
} from '../../rest/hospitalIntegrationAPI';
import { showSuccessToast } from '../../utils/ToastUtils';
import './hospital-integration.less';
import { HospitalIntegrationWorkspaceProps } from './HospitalIntegrationPage.interface';
import {
  canResumeIntegrationTask,
  EMPTY_INTEGRATION_TASK,
  integrationErrorKey,
  isIntegrationRunBusy,
  isIntegrationRunUncertain,
  toIntegrationTaskInput,
  validateIntegrationInput,
} from './HospitalIntegrationUtils';
import { IntegrationConnections } from './IntegrationConnections';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';
import { IntegrationTaskDetail } from './IntegrationTaskDetail';
import { IntegrationTaskForm } from './IntegrationTaskForm';
import { useIntegrationTables } from './useIntegrationTables';

type IntegrationView = 'list' | 'create' | 'detail' | 'edit';

export const HospitalIntegrationWorkspace = ({
  isAdmin,
}: HospitalIntegrationWorkspaceProps) => {
  const { t, i18n } = useTranslation();
  const [tasks, setTasks] = useState<IntegrationTask[]>([]);
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [engine, setEngine] = useState<IntegrationEngineStatus>();
  const [activeTab, setActiveTab] = useState<'tasks' | 'connections'>('tasks');
  const [connectionCreateRequest, setConnectionCreateRequest] = useState(0);
  const [connectionBusy, setConnectionBusy] = useState(false);
  const [tablesReload, setTablesReload] = useState(0);
  const [selected, setSelected] = useState<IntegrationTask>();
  const [view, setView] = useState<IntegrationView>('list');
  const [form, setForm] = useState<IntegrationTaskInput>({
    ...EMPTY_INTEGRATION_TASK,
  });
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [errorCode, setErrorCode] = useState<string>();
  const [conflict, setConflict] = useState(false);
  const [editingVersion, setEditingVersion] = useState<number>();
  const [loading, setLoading] = useState<Record<string, boolean>>({
    initial: true,
  });
  const [uncertainTasks, setUncertainTasks] = useState<Record<string, boolean>>(
    {}
  );
  const [reload, setReload] = useState(0);
  const epoch = useRef(0);
  const mutation = useRef(false);
  const connectionEpoch = useRef(0);
  const connectionMutation = useRef(false);
  const mounted = useRef(true);
  const selectedRequest = useRef<AbortController>();
  const taskEditorOpen = view === 'create' || view === 'edit';
  const sourceData = useIntegrationTables(
    form.sourceConnectionId,
    form.sourceSchema,
    form.sourceTable,
    taskEditorOpen,
    tablesReload
  );
  const targetData = useIntegrationTables(
    form.targetConnectionId,
    form.targetSchema,
    form.targetTable,
    taskEditorOpen,
    tablesReload
  );
  const sourceTables = sourceData.tables;
  const targetTables = targetData.tables;
  const tablesLoading = sourceData.loading || targetData.loading;
  const autoMappedRoute = useRef<string>();
  const busy = Object.values(loading).some(Boolean);
  const engineAvailable = engine?.enabled === true && engine.reachable;

  const handleConnectionBusyChange = useCallback((busy: boolean) => {
    connectionEpoch.current += 1;
    connectionMutation.current = busy;
    if (mounted.current) {
      setConnectionBusy(busy);
    }
  }, []);

  const acceptTask = useCallback((task: IntegrationTask) => {
    setSelected(task);
    setTasks((current) => [
      task,
      ...current.filter((item) => item.id !== task.id),
    ]);
    if (task.latestRun && !isIntegrationRunUncertain(task.latestRun)) {
      setUncertainTasks((current) => ({ ...current, [task.id]: false }));
    }
  }, []);

  useEffect(() => {
    if (!isAdmin) {
      setLoading({});

      return;
    }
    const controller = new AbortController();
    const requestConnectionEpoch = connectionEpoch.current;
    setLoading((current) => ({ ...current, initial: true }));
    const load = async () => {
      const results = await Promise.allSettled([
        getIntegrationStatus(controller.signal),
        getIntegrationConnections(controller.signal),
        getIntegrationTasks(controller.signal),
      ]);
      if (controller.signal.aborted) {
        return;
      }
      const [engineResult, connectionResult, taskResult] = results;
      setEngine(
        engineResult.status === 'fulfilled' ? engineResult.value : undefined
      );
      if (
        !connectionMutation.current &&
        requestConnectionEpoch === connectionEpoch.current
      ) {
        setConnections(
          connectionResult.status === 'fulfilled' ? connectionResult.value : []
        );
      }
      if (taskResult.status === 'fulfilled') {
        setTasks(taskResult.value);
      } else {
        setErrorCode(getIntegrationFailure(taskResult.reason).code);
      }
      setLoading((current) => ({ ...current, initial: false }));
    };
    void load();

    return () => controller.abort();
  }, [isAdmin, reload]);

  useEffect(() => {
    mounted.current = true;

    return () => {
      mounted.current = false;
      epoch.current += 1;
      selectedRequest.current?.abort();
    };
  }, []);

  useEffect(() => {
    const needsPolling = tasks.some(
      (task) => isIntegrationRunBusy(task.latestRun) || uncertainTasks[task.id]
    );
    if (!isAdmin || !needsPolling) {
      return;
    }
    const controller = new AbortController();
    const capturedEpoch = epoch.current;
    const timer = window.setTimeout(async () => {
      const results = await Promise.allSettled([
        getIntegrationTasks(controller.signal),
        ...(selected
          ? [getIntegrationTask(selected.id, controller.signal)]
          : []),
      ]);
      if (controller.signal.aborted || capturedEpoch !== epoch.current) {
        return;
      }
      const [list, detail] = results;
      if (list.status === 'fulfilled' && Array.isArray(list.value)) {
        setTasks(list.value);
      }
      if (detail?.status === 'fulfilled' && !Array.isArray(detail.value)) {
        acceptTask(detail.value);
      }
      if (list.status === 'rejected') {
        setErrorCode(getIntegrationFailure(list.reason).code);
        setReload((current) => current + 1);
      }
    }, 5000);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [acceptTask, isAdmin, selected, tasks, uncertainTasks]);

  useEffect(() => {
    if (!taskEditorOpen) {
      autoMappedRoute.current = undefined;

      return;
    }
    const source = sourceData.detail;
    const target = targetData.detail;
    if (
      !source ||
      !target ||
      source.schema !== form.sourceSchema ||
      source.name !== form.sourceTable ||
      target.schema !== form.targetSchema ||
      target.name !== form.targetTable
    ) {
      return;
    }
    const route = JSON.stringify([
      form.sourceConnectionId,
      form.sourceSchema,
      form.sourceTable,
      form.targetConnectionId,
      form.targetSchema,
      form.targetTable,
    ]);
    if (autoMappedRoute.current === route) {
      return;
    }
    autoMappedRoute.current = route;
    setForm((current) => ({
      ...current,
      primaryKey:
        current.primaryKey ||
        source.columns.find((column) => column.primaryKey)?.name ||
        '',
      fieldMappings: current.fieldMappings.length
        ? current.fieldMappings
        : source.columns
            .filter((column) =>
              target.columns.some((item) => item.name === column.name)
            )
            .map((column) => ({ source: column.name, target: column.name })),
    }));
  }, [
    taskEditorOpen,
    sourceData.detail,
    targetData.detail,
    form.sourceConnectionId,
    form.sourceSchema,
    form.sourceTable,
    form.targetConnectionId,
    form.targetSchema,
    form.targetTable,
  ]);

  const changeView = (next: IntegrationView) => {
    epoch.current += 1;
    selectedRequest.current?.abort();
    setErrorCode(undefined);
    setConflict(false);
    setFormErrors([]);
    setView(next);
    setLoading((current) => ({ ...current, detail: false }));
  };

  const openTask = async (task: IntegrationTask) => {
    changeView('detail');
    setSelected(task);
    const requestEpoch = epoch.current;
    const controller = new AbortController();
    selectedRequest.current = controller;
    setLoading((current) => ({ ...current, detail: true }));
    try {
      const detail = await getIntegrationTask(task.id, controller.signal);
      if (!controller.signal.aborted && requestEpoch === epoch.current) {
        acceptTask(detail);
      }
    } catch (error) {
      if (!controller.signal.aborted && requestEpoch === epoch.current) {
        setErrorCode(getIntegrationFailure(error).code);
      }
    } finally {
      if (requestEpoch === epoch.current) {
        setLoading((current) => ({ ...current, detail: false }));
      }
    }
  };

  const perform = async (key: string, action: () => Promise<void>) => {
    if (mutation.current || !isAdmin) {
      return;
    }
    mutation.current = true;
    setLoading((current) => ({ ...current, [key]: true }));
    setErrorCode(undefined);
    try {
      await action();
    } finally {
      mutation.current = false;
      if (mounted.current) {
        setLoading((current) => ({ ...current, [key]: false }));
      }
    }
  };

  const validateOrSave = async (save: boolean) => {
    if (tablesLoading || mutation.current) {
      return;
    }
    const errors = validateIntegrationInput(form, sourceTables, targetTables);
    if (
      !connections.some(
        (connection) =>
          connection.id === form.sourceConnectionId &&
          connection.enabled &&
          connection.role === 'SOURCE'
      ) ||
      !connections.some(
        (connection) =>
          connection.id === form.targetConnectionId &&
          connection.enabled &&
          connection.role === 'TARGET'
      )
    ) {
      errors.push('connectionsRequired');
    }
    setFormErrors(errors);
    if (errors.length > 0) {
      return;
    }
    const requestEpoch = epoch.current;
    await perform(save ? 'save' : 'validate', async () => {
      try {
        const validation = await validateIntegrationTask(form);
        if (requestEpoch !== epoch.current) {
          return;
        }
        if (!validation.valid) {
          setFormErrors(['serverValidationFailed']);

          return;
        }
        if (!save) {
          showSuccessToast(t('hospitalIntegration.validationPassed'));

          return;
        }
        const task =
          view === 'edit' && selected
            ? await updateIntegrationTask(
                selected.id,
                form,
                editingVersion ?? selected.version
              )
            : await createIntegrationTask(form);
        if (requestEpoch === epoch.current) {
          acceptTask(task);
          changeView('detail');
          showSuccessToast(t('hospitalIntegration.saved'));
        }
      } catch (error) {
        if (requestEpoch === epoch.current) {
          const failure = getIntegrationFailure(error);
          setErrorCode(failure.code);
          setConflict(failure.conflict && failure.code === 'VERSION_CONFLICT');
        }
      }
    });
  };

  const taskAction = async (
    key: 'run' | 'resume' | 'stop' | 'catalog' | 'refresh'
  ) => {
    if (!selected) {
      return;
    }
    const task = selected;
    const active =
      isIntegrationRunBusy(task.latestRun) || uncertainTasks[task.id];
    if ((key === 'run' || key === 'resume') && (active || !engineAvailable)) {
      return;
    }
    if (key === 'run' && canResumeIntegrationTask(task.mode, task.latestRun)) {
      return;
    }
    if (
      key === 'resume' &&
      !canResumeIntegrationTask(task.mode, task.latestRun)
    ) {
      return;
    }
    const requestEpoch = epoch.current;
    await perform(key, async () => {
      try {
        const result =
          key === 'refresh'
            ? await getIntegrationTask(task.id)
            : key === 'catalog'
            ? await syncIntegrationCatalog(task.id)
            : key === 'stop'
            ? await stopIntegrationTask(task.id, task.mode === 'CDC')
            : await runIntegrationTask(task.id, key === 'resume');
        if (requestEpoch === epoch.current) {
          acceptTask(result);
        }
      } catch (error) {
        const failure = getIntegrationFailure(error);
        if (
          mounted.current &&
          (key === 'run' || key === 'resume') &&
          failure.uncertain
        ) {
          setUncertainTasks((current) => ({ ...current, [task.id]: true }));
        }
        if (requestEpoch === epoch.current) {
          setErrorCode(failure.code);
        }
      }
    });
  };

  const reloadCurrentTask = async () => {
    if (!selected) {
      return;
    }
    const requestEpoch = epoch.current;
    const controller = new AbortController();
    selectedRequest.current?.abort();
    selectedRequest.current = controller;
    await perform('reloadTask', async () => {
      try {
        const current = await getIntegrationTask(
          selected.id,
          controller.signal
        );
        if (!controller.signal.aborted && requestEpoch === epoch.current) {
          acceptTask(current);
          setForm(toIntegrationTaskInput(current));
          setEditingVersion(current.version);
          setErrorCode(undefined);
          setConflict(false);
          setFormErrors([]);
        }
      } catch (error) {
        if (!controller.signal.aborted && requestEpoch === epoch.current) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      }
    });
  };

  return (
    <main className="hospital-integration" data-testid="hospital-integration">
      <header className="hospital-integration__header">
        <div>
          <h1>{t('hospitalIntegration.title')}</h1>
          <p>{t('hospitalIntegration.description')}</p>
        </div>
        {isAdmin &&
          (activeTab === 'connections' ||
            view === 'list' ||
            view === 'create') && (
            <Button
              data-testid="integration-create-action"
              iconLeading={Plus}
              isDisabled={busy || connectionBusy}
              onClick={() => {
                if (activeTab === 'connections') {
                  setConnectionCreateRequest((current) => current + 1);

                  return;
                }
                setSelected(undefined);
                setForm({
                  ...EMPTY_INTEGRATION_TASK,
                  fieldMappings: [],
                  sourceConnectionId:
                    connections.find(
                      (connection) =>
                        connection.enabled && connection.role === 'SOURCE'
                    )?.id ?? '',
                  targetConnectionId:
                    connections.find(
                      (connection) =>
                        connection.enabled && connection.role === 'TARGET'
                    )?.id ?? '',
                });
                changeView('create');
              }}>
              {t(
                activeTab === 'connections'
                  ? 'hospitalIntegration.createConnection'
                  : 'hospitalIntegration.createTask'
              )}
            </Button>
          )}
      </header>
      {!isAdmin ? (
        <section className="hospital-integration__empty" role="status">
          <h2>{t('hospitalIntegration.adminOnly')}</h2>
          <p>{t('hospitalIntegration.adminOnlyHint')}</p>
        </section>
      ) : (
        <>
          {activeTab === 'tasks' && (view === 'detail' || view === 'edit') && (
            <Button
              className="hospital-integration__back"
              color="link-gray"
              iconLeading={ArrowLeft}
              isDisabled={mutation.current}
              onClick={() => changeView('list')}>
              {t('hospitalIntegration.backToTasks')}
            </Button>
          )}
          {errorCode && !taskEditorOpen && (
            <div className="hospital-integration__alert" role="alert">
              <p>{t(integrationErrorKey(errorCode))}</p>
              {conflict && <p>{t('hospitalIntegration.conflictHint')}</p>}
            </div>
          )}
          <Tabs
            className="hospital-integration__tabs"
            selectedKey={activeTab}
            onSelectionChange={(key) => {
              if (key === 'tasks' || key === 'connections') {
                setActiveTab(key);
              }
            }}>
            <Tabs.List
              aria-label={t('hospitalIntegration.title')}
              type="underline">
              <Tabs.Item
                data-testid="integration-tasks-tab"
                id="tasks"
                isDisabled={busy || connectionBusy}
                label={t('hospitalIntegration.tasks')}
              />
              <Tabs.Item
                data-testid="integration-connections-tab"
                id="connections"
                isDisabled={busy || connectionBusy}
                label={t('hospitalIntegration.dataSources')}
              />
            </Tabs.List>
            <section
              aria-label={t('hospitalIntegration.engine')}
              className="hospital-integration__engine">
              <div>
                <strong>{t('hospitalIntegration.engine')}</strong>
                <span
                  className="hospital-integration__status"
                  data-status={engineAvailable ? 'SYNCED' : 'UNKNOWN'}>
                  {loading.initial
                    ? t('hospitalIntegration.loading')
                    : t(
                        `hospitalIntegration.${
                          engineAvailable
                            ? 'reachable'
                            : engine?.enabled === false
                            ? 'disabled'
                            : 'unreachable'
                        }`
                      )}
                </span>
                {engine?.engineVersion && <small>{engine.engineVersion}</small>}
              </div>
              <Button
                aria-label={t('hospitalIntegration.refreshConnections')}
                color="link-gray"
                iconLeading={RefreshCw01}
                isDisabled={busy || connectionBusy}
                onClick={() => {
                  setErrorCode(undefined);
                  setReload((current) => current + 1);
                }}>
                {t('label.refresh')}
              </Button>
            </section>
            <Tabs.Panel
              shouldForceMount
              hidden={activeTab !== 'connections'}
              id="connections">
              <IntegrationConnections
                connections={connections}
                createRequest={connectionCreateRequest}
                loading={loading.initial}
                onBusyChange={handleConnectionBusyChange}
                onChange={setConnections}
              />
            </Tabs.Panel>
            <Tabs.Panel
              shouldForceMount
              hidden={activeTab !== 'tasks'}
              id="tasks">
              <div
                data-testid="integration-tasks-list"
                hidden={
                  activeTab !== 'tasks' ||
                  (view !== 'list' && view !== 'create')
                }>
                <section
                  aria-busy={loading.initial}
                  aria-labelledby="integration-tasks-title">
                  <div className="hospital-integration__section-heading">
                    <h2 id="integration-tasks-title">
                      {t('hospitalIntegration.tasks')}
                    </h2>
                  </div>
                  {loading.initial ? (
                    <p role="status">{t('hospitalIntegration.loading')}</p>
                  ) : tasks.length === 0 ? (
                    <div className="hospital-integration__empty">
                      <h3>{t('hospitalIntegration.noTasks')}</h3>
                      <p>{t('hospitalIntegration.noTasksHint')}</p>
                    </div>
                  ) : (
                    <div className="hospital-integration__table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">{t('label.name')}</th>
                            <th scope="col">
                              {t('hospitalIntegration.transferRoute')}
                            </th>
                            <th scope="col">{t('label.mode')}</th>
                            <th scope="col">{t('label.status')}</th>
                            <th scope="col">
                              {t('hospitalIntegration.catalogSync')}
                            </th>
                            <th scope="col">{t('label.updated-at')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {tasks.map((task) => (
                            <tr key={task.id}>
                              <td>
                                <Button
                                  color="link-color"
                                  onClick={() => void openTask(task)}>
                                  {task.displayName || task.name}
                                </Button>
                                <small>{task.name}</small>
                              </td>
                              <td>
                                <span>
                                  {task.sourceSchema}.{task.sourceTable}
                                </span>
                                <ArrowRight
                                  aria-hidden="true"
                                  className="hospital-integration__route-arrow"
                                />
                                <span>
                                  {task.targetSchema}.{task.targetTable}
                                </span>
                              </td>
                              <td>{task.mode}</td>
                              <td>
                                <span
                                  className="hospital-integration__status"
                                  data-status={task.latestRun?.status}>
                                  {task.latestRun
                                    ? t(
                                        `hospitalIntegration.runStatus.${task.latestRun.status}`,
                                        { defaultValue: task.latestRun.status }
                                      )
                                    : t('hospitalIntegration.notRun')}
                                </span>
                              </td>
                              <td>
                                {t(
                                  `hospitalIntegration.catalogStatus.${task.catalog.status}`
                                )}
                              </td>
                              <td>
                                {new Date(task.updatedAt).toLocaleString(
                                  i18n.language
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </section>
              </div>

              <FormDrawer
                destroyOnClose
                className="hospital-integration hospital-integration--drawer"
                data-testid="integration-task-drawer"
                isOpen={taskEditorOpen}
                isSubmitting={busy}
                title={t(
                  view === 'edit'
                    ? 'hospitalIntegration.editTask'
                    : 'hospitalIntegration.createTask'
                )}
                width={view === 'edit' ? 960 : 880}
                onClose={() => {
                  if (!mutation.current) {
                    changeView(selected ? 'detail' : 'list');
                  }
                }}>
                {errorCode && (
                  <IntegrationDrawerAlert focusKey={errorCode}>
                    <p>{t(integrationErrorKey(errorCode))}</p>
                    {conflict && (
                      <>
                        <p>{t('hospitalIntegration.conflictHint')}</p>
                        <Button
                          color="link-color"
                          data-testid="integration-task-reload"
                          isDisabled={busy}
                          isLoading={loading.reloadTask}
                          onClick={() => void reloadCurrentTask()}>
                          {t('hospitalIntegration.reloadCurrent')}
                        </Button>
                      </>
                    )}
                  </IntegrationDrawerAlert>
                )}
                {(sourceData.errorCode || targetData.errorCode) && (
                  <div className="hospital-integration__alert" role="alert">
                    {t(
                      integrationErrorKey(
                        sourceData.errorCode ?? targetData.errorCode
                      )
                    )}
                  </div>
                )}
                <IntegrationTaskForm
                  connections={connections}
                  disabled={busy}
                  errors={formErrors}
                  saving={loading.save}
                  sourceTables={sourceTables}
                  tablesLoading={tablesLoading}
                  tablesUnavailable={Boolean(
                    sourceData.errorCode || targetData.errorCode
                  )}
                  targetTables={targetTables}
                  validating={loading.validate}
                  value={form}
                  onCancel={() => {
                    if (!mutation.current) {
                      changeView(selected ? 'detail' : 'list');
                    }
                  }}
                  onChange={(value) => {
                    setForm(value);
                    setFormErrors([]);
                  }}
                  onReloadTables={() =>
                    setTablesReload((current) => current + 1)
                  }
                  onSave={() => void validateOrSave(true)}
                  onValidate={() => void validateOrSave(false)}
                />
              </FormDrawer>
              {selected && (
                <div
                  data-testid="integration-task-detail"
                  hidden={
                    activeTab !== 'tasks' ||
                    (view !== 'detail' && view !== 'edit')
                  }>
                  <IntegrationTaskDetail
                    busy={busy}
                    engineAvailable={engineAvailable}
                    loading={loading}
                    sourceConnectionName={
                      connections.find(
                        (connection) =>
                          connection.id === selected.sourceConnectionId
                      )?.displayName
                    }
                    targetConnectionName={
                      connections.find(
                        (connection) =>
                          connection.id === selected.targetConnectionId
                      )?.displayName
                    }
                    task={selected}
                    uncertain={
                      Boolean(uncertainTasks[selected.id]) ||
                      isIntegrationRunUncertain(selected.latestRun)
                    }
                    onCatalog={() => void taskAction('catalog')}
                    onEdit={() => {
                      setForm(toIntegrationTaskInput(selected));
                      setEditingVersion(selected.version);
                      changeView('edit');
                    }}
                    onRefresh={() => void taskAction('refresh')}
                    onRun={(resume) =>
                      void taskAction(resume ? 'resume' : 'run')
                    }
                    onStop={() => void taskAction('stop')}
                  />
                </div>
              )}
            </Tabs.Panel>
          </Tabs>
        </>
      )}
    </main>
  );
};

const HospitalIntegrationPage = () => {
  const { t } = useTranslation();
  const isAdmin = useApplicationStore(
    (state) => state.currentUser?.isAdmin === true
  );

  return (
    <PageLayoutV1 pageTitle={t('hospitalIntegration.title')}>
      <HospitalIntegrationWorkspace isAdmin={isAdmin} />
    </PageLayoutV1>
  );
};

export default HospitalIntegrationPage;
