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
import { Button, Input } from '@openmetadata/ui-core-components';
import { ArrowLeft, ArrowRight, Plus, RefreshCw01 } from '@untitledui/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import { FormDrawerActions } from '../../components/common/atoms/drawer/FormDrawerActions';
import PageLayoutV1 from '../../components/PageLayoutV1/PageLayoutV1';
import { ROUTES } from '../../constants/constants';
import { useApplicationStore } from '../../hooks/useApplicationStore';
import {
  createIntegrationTask,
  getIntegrationConnections,
  getIntegrationFailure,
  getIntegrationStatus,
  getIntegrationTargetCount,
  getIntegrationTask,
  getIntegrationTasks,
  IntegrationCdcPreflightResult,
  IntegrationConnection,
  IntegrationEngineStatus,
  IntegrationInitializationAction,
  IntegrationRunOptions,
  IntegrationTargetCount,
  IntegrationTask,
  IntegrationTaskInput,
  preflightIntegrationTask,
  runIntegrationTask,
  stopIntegrationTask,
  syncIntegrationCatalog,
  updateIntegrationInitialization,
  updateIntegrationTask,
  validateIntegrationTask,
} from '../../rest/hospitalIntegrationAPI';
import { showSuccessToast } from '../../utils/ToastUtils';
import { readDorisTaskTarget } from '../HospitalTargetTablesPage/DorisTableDesignUtils';
import './hospital-integration.less';
import { HospitalIntegrationWorkspaceProps } from './HospitalIntegrationPage.interface';
import {
  getIntegrationCollectionSearch,
  getIntegrationTaskId,
  getIntegrationTaskPath,
} from './HospitalIntegrationRouteUtils';
import {
  canResumeIntegrationTask,
  EMPTY_INTEGRATION_TASK,
  integrationErrorKey,
  integrationModeKey,
  isIntegrationInitializing,
  isIntegrationRunBusy,
  isIntegrationRunUncertain,
  toIntegrationTaskInput,
  validateIntegrationInput,
} from './HospitalIntegrationUtils';
import { IntegrationCdcPreflight } from './IntegrationCdcPreflight';
import {
  cdcPreflightReady,
  integrationTaskPreflightKey,
} from './IntegrationCdcUtils';
import {
  IntegrationCollectionPagination,
  IntegrationCollectionTools,
  useIntegrationCollection,
} from './IntegrationCollection';
import { IntegrationDiscardConfirmation } from './IntegrationDiscardConfirmation';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';
import { IntegrationPageHeader } from './IntegrationPageHeader';
import { IntegrationTaskDetail } from './IntegrationTaskDetail';
import { IntegrationTaskForm } from './IntegrationTaskForm';
import { useIntegrationNavigationGuard } from './useIntegrationNavigationGuard';
import { useIntegrationQuerySchema } from './useIntegrationQuerySchema';
import { useIntegrationScopedRead } from './useIntegrationScopedRead';
import { useIntegrationTables } from './useIntegrationTables';

type IntegrationView = 'list' | 'create' | 'detail' | 'edit';

export const HospitalIntegrationWorkspace = ({
  isAdmin,
}: HospitalIntegrationWorkspaceProps) => {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const routeTaskId = getIntegrationTaskId(location.pathname);
  const { params, update, reset, page } = useIntegrationCollection();
  const [tasks, setTasks] = useState<IntegrationTask[]>([]);
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [engine, setEngine] = useState<IntegrationEngineStatus>();
  const [tablesReload, setTablesReload] = useState(0);
  const [targetTablesReload, setTargetTablesReload] = useState(0);
  const [targetTableBusy, setTargetTableBusy] = useState(false);
  const [targetTableDirty, setTargetTableDirty] = useState(false);
  const targetTablePending = useRef(false);
  const targetTableDraft = useRef(false);
  const targetTableBusyChange = useCallback((pending: boolean) => {
    targetTablePending.current = pending;
    setTargetTableBusy(pending);
  }, []);
  const targetTableDraftChange = useCallback((dirty: boolean) => {
    targetTableDraft.current = dirty;
    setTargetTableDirty(dirty);
  }, []);
  const [selected, setSelected] = useState<IntegrationTask>();
  const [view, setView] = useState<IntegrationView>(
    routeTaskId ? 'detail' : 'list'
  );
  const [form, setForm] = useState<IntegrationTaskInput>({
    ...EMPTY_INTEGRATION_TASK,
  });
  const initialForm = useRef('');
  const consumedTargetIntent = useRef<string>();
  const [discardRequested, setDiscardRequested] = useState(false);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [runtimeInvalid, setRuntimeInvalid] = useState(false);
  const [runtimeDirty, setRuntimeDirty] = useState(false);
  const runtimeDraft = useRef(false);
  const runtimeDraftChange = useCallback((dirty: boolean) => {
    runtimeDraft.current = dirty;
    setRuntimeDirty(dirty);
  }, []);
  const [editorSession, setEditorSession] = useState(0);
  const [preflightOpen, setPreflightOpen] = useState(false);
  const [errorCode, setErrorCode] = useState<string>();
  const [connectionErrorCode, setConnectionErrorCode] = useState<string>();
  const [tasksErrorCode, setTasksErrorCode] = useState<string>();
  const [conflict, setConflict] = useState(false);
  const [editingVersion, setEditingVersion] = useState<number>();
  const [backfillOpen, setBackfillOpen] = useState(false);
  const [businessDate, setBusinessDate] = useState('');
  const initialBusinessDate = useRef('');
  const [businessDateError, setBusinessDateError] = useState(false);
  const [loading, setLoading] = useState<Record<string, boolean>>({
    initial: true,
  });
  const [uncertainTasks, setUncertainTasks] = useState<Record<string, boolean>>(
    {}
  );
  const [reload, setReload] = useState(0);
  const epoch = useRef(0);
  const mutation = useRef(false);
  const mounted = useRef(true);
  const selectedRequest = useRef<AbortController>();
  const taskEditorOpen = view === 'create' || view === 'edit';
  const sourceData = useIntegrationTables(
    form.sourceConnectionId,
    form.sourceSchema,
    form.sourceTable,
    isAdmin && taskEditorOpen && form.mode !== 'SQL_T1',
    tablesReload,
    JSON.stringify([
      isAdmin,
      location.key,
      location.pathname,
      view,
      editorSession,
      connections.find(
        (connection) => connection.id === form.sourceConnectionId
      )?.version,
    ])
  );
  const targetData = useIntegrationTables(
    form.targetConnectionId,
    form.targetSchema,
    form.targetTable,
    isAdmin && taskEditorOpen,
    tablesReload + targetTablesReload,
    JSON.stringify([
      isAdmin,
      location.key,
      location.pathname,
      view,
      editorSession,
      connections.find(
        (connection) => connection.id === form.targetConnectionId
      )?.version,
    ])
  );
  const queryData = useIntegrationQuerySchema(
    {
      connectionId: form.sourceConnectionId,
      schema: form.sourceSchema,
      query: form.sourceQuery ?? '',
    },
    isAdmin && taskEditorOpen && form.mode === 'SQL_T1'
  );
  const sourceDetail =
    form.mode === 'SQL_T1' ? queryData.table : sourceData.detail;
  const sourceTables =
    form.mode === 'SQL_T1'
      ? queryData.table
        ? [queryData.table]
        : []
      : sourceData.tables;
  const targetTables = targetData.tables;
  const tablesLoading = sourceData.loading || targetData.loading;
  const autoMappedRoute = useRef<string>();
  const inspectionInput = taskEditorOpen
    ? form
    : selected
    ? toIntegrationTaskInput(selected)
    : undefined;
  const inspectionKey =
    isAdmin &&
    inspectionInput?.mode === 'CDC' &&
    (taskEditorOpen || view === 'detail')
      ? integrationTaskPreflightKey(
          inspectionInput,
          connections,
          JSON.stringify([
            location.key,
            location.pathname,
            view,
            editorSession,
            runtimeInvalid,
          ]),
          tablesReload + targetTablesReload + reload
        )
      : undefined;
  const currentInspectionKey = useRef(inspectionKey);
  currentInspectionKey.current = inspectionKey;
  const authorized = useRef(isAdmin);
  authorized.current = isAdmin;
  const taskPreflight =
    useIntegrationScopedRead<IntegrationCdcPreflightResult>(inspectionKey);
  const countKey =
    isAdmin && selected && view === 'detail'
      ? JSON.stringify([
          location.key,
          location.pathname,
          selected.id,
          selected.targetConnectionId,
          selected.targetSchema,
          selected.targetTable,
          selected.latestRun?.jobId,
          connections.find((item) => item.id === selected.targetConnectionId)
            ?.version,
        ])
      : undefined;
  const targetCount =
    useIntegrationScopedRead<IntegrationTargetCount>(countKey);
  const busy = targetTableBusy || Object.values(loading).some(Boolean);
  const engineAvailable = engine?.enabled === true && engine.reachable;
  const executionState = useRef({
    engineAvailable,
    selected,
    uncertain: Boolean(selected && uncertainTasks[selected.id]),
  });
  executionState.current = {
    engineAvailable,
    selected,
    uncertain: Boolean(selected && uncertainTasks[selected.id]),
  };
  const navigationGuard = useIntegrationNavigationGuard({
    enabled:
      (taskEditorOpen &&
        (busy ||
          targetTableDirty ||
          runtimeDirty ||
          JSON.stringify(form) !== initialForm.current)) ||
      (backfillOpen && (busy || businessDate !== initialBusinessDate.current)),
    locked: busy,
  });
  const confirmDiscard = discardRequested || navigationGuard.blocked;

  const acceptTask = useCallback((task: IntegrationTask) => {
    setSelected(task);
    setTasks((current) =>
      current.some((item) => item.id === task.id)
        ? current.map((item) => (item.id === task.id ? task : item))
        : [task, ...current]
    );
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
    setLoading((current) => ({ ...current, initial: true }));
    setTasksErrorCode(undefined);
    setConnectionErrorCode(undefined);
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
      setConnections(
        connectionResult.status === 'fulfilled' ? connectionResult.value : []
      );
      if (connectionResult.status === 'rejected') {
        setConnectionErrorCode(
          getIntegrationFailure(connectionResult.reason).code
        );
      }
      if (taskResult.status === 'fulfilled') {
        setTasks(taskResult.value);
      } else {
        setTasksErrorCode(getIntegrationFailure(taskResult.reason).code);
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
      (task) =>
        isIntegrationRunBusy(task.latestRun) ||
        uncertainTasks[task.id] ||
        (isIntegrationInitializing(task) &&
          task.schedule?.enabled &&
          task.initializationState?.status !== 'FAILED')
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
    const source = sourceDetail;
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
      form.sourceQuery,
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
        (current.mode === 'SQL_T1'
          ? ''
          : source.columns.find((column) => column.primaryKey)?.name) ||
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
    sourceDetail,
    targetData.detail,
    form.sourceConnectionId,
    form.sourceSchema,
    form.sourceTable,
    form.sourceQuery,
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
    setDiscardRequested(false);
    setView(next);
    setPreflightOpen(false);
    setRuntimeInvalid(false);
    runtimeDraftChange(false);
    setLoading((current) => ({ ...current, detail: false }));
  };

  const setEditorForm = (input: IntegrationTaskInput) => {
    initialForm.current = JSON.stringify(input);
    setForm(input);
    setRuntimeInvalid(false);
    runtimeDraftChange(false);
    setEditorSession((current) => current + 1);
  };
  const closeEditor = () => {
    setForm({ ...EMPTY_INTEGRATION_TASK, fieldMappings: [] });
    changeView(selected ? 'detail' : 'list');
  };
  const requestCloseEditor = () => {
    if (mutation.current || targetTablePending.current) {
      return;
    }
    if (
      targetTableDraft.current ||
      runtimeDraft.current ||
      JSON.stringify(form) !== initialForm.current
    ) {
      setDiscardRequested(true);

      return;
    }
    closeEditor();
  };
  const closeBackfill = () => {
    setBackfillOpen(false);
    setBusinessDateError(false);
    setErrorCode(undefined);
    setDiscardRequested(false);
  };
  const requestCloseBackfill = () => {
    if (mutation.current) {
      return;
    }
    if (businessDate !== initialBusinessDate.current) {
      setDiscardRequested(true);

      return;
    }
    closeBackfill();
  };
  const openBackfill = () => {
    if (!selected || isIntegrationInitializing(selected) || mutation.current) {
      return;
    }
    const date =
      selected?.latestRun?.businessDate ??
      selected?.scheduleState?.pendingBusinessDate ??
      '';
    initialBusinessDate.current = date;
    setBusinessDate(date);
    setBusinessDateError(false);
    setErrorCode(undefined);
    setBackfillOpen(true);
  };

  const loadTask = useCallback(
    async (id: string) => {
      const requestEpoch = ++epoch.current;
      selectedRequest.current?.abort();
      const controller = new AbortController();
      selectedRequest.current = controller;
      setLoading((current) => ({ ...current, detail: true }));
      setErrorCode(undefined);
      try {
        const detail = await getIntegrationTask(id, controller.signal);
        if (!controller.signal.aborted && requestEpoch === epoch.current) {
          acceptTask(detail);
        }
      } catch (error) {
        if (!controller.signal.aborted && requestEpoch === epoch.current) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      } finally {
        if (!controller.signal.aborted && requestEpoch === epoch.current) {
          setLoading((current) => ({ ...current, detail: false }));
        }
      }
    },
    [acceptTask]
  );

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    epoch.current += 1;
    selectedRequest.current?.abort();
    setSelected(undefined);
    setBackfillOpen(false);
    setConflict(false);
    setFormErrors([]);
    setErrorCode(undefined);
    setLoading((current) => ({ ...current, detail: false }));
    if (routeTaskId) {
      setView('detail');
      void loadTask(routeTaskId);
    } else {
      setView('list');
    }

    return () => {
      selectedRequest.current?.abort();
    };
  }, [isAdmin, routeTaskId, loadTask]);

  const openTask = (task: IntegrationTask) => {
    navigate({
      pathname: getIntegrationTaskPath(task.id),
      search: getIntegrationCollectionSearch(location.search),
    });
  };

  useEffect(() => {
    if (
      !isAdmin ||
      routeTaskId ||
      loading.initial ||
      consumedTargetIntent.current === location.key
    ) {
      return;
    }
    const target = readDorisTaskTarget(location.state);
    if (!target) {
      return;
    }
    const connection = connections.find(
      (item) =>
        item.id === target.connectionId &&
        item.enabled &&
        item.role === 'TARGET' &&
        item.databaseType === 'Doris' &&
        item.database === target.schema
    );
    if (!connection) {
      return;
    }
    consumedTargetIntent.current = location.key;
    const next: IntegrationTaskInput = {
      ...EMPTY_INTEGRATION_TASK,
      fieldMappings: [],
      targetConnectionId: connection.id,
      targetSchema: target.schema,
      targetTable: target.table,
    };
    initialForm.current = JSON.stringify(next);
    setForm(next);
    setFormErrors([]);
    setSelected(undefined);
    setView('create');
  }, [
    connections,
    isAdmin,
    loading.initial,
    location.key,
    location.state,
    routeTaskId,
  ]);

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
    if (
      tablesLoading ||
      queryData.loading ||
      Boolean(queryData.errorCode) ||
      mutation.current ||
      targetTablePending.current ||
      confirmDiscard
    ) {
      return;
    }
    const errors = validateIntegrationInput(form, sourceTables, targetTables);
    if (runtimeInvalid) {
      errors.push('runtimeRangeInvalid');
    }
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
          connection.role === 'TARGET' &&
          (form.mode !== 'SQL_T1' || connection.databaseType === 'Doris')
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
          navigationGuard.release();
          acceptTask(task);
          changeView('detail');
          navigate({
            pathname: getIntegrationTaskPath(task.id),
            search: getIntegrationCollectionSearch(location.search),
          });
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

  const checkTaskPreflight = async () => {
    if (
      !isAdmin ||
      !inspectionInput ||
      mutation.current ||
      taskPreflight.loading ||
      targetTablePending.current
    ) {
      return;
    }
    if (taskEditorOpen) {
      const errors = validateIntegrationInput(form, sourceTables, targetTables);
      if (runtimeInvalid) {
        errors.push('runtimeRangeInvalid');
      }
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
      if (tablesLoading || errors.length) {
        return;
      }
    } else {
      setPreflightOpen(true);
    }
    await taskPreflight.execute((signal) =>
      preflightIntegrationTask(inspectionInput, signal)
    );
  };

  const taskAction = async (
    key:
      | 'run'
      | 'resume'
      | 'stop'
      | 'catalog'
      | 'refresh'
      | 'backfill'
      | 'retry',
    options?: IntegrationRunOptions
  ) => {
    if (!selected) {
      return;
    }
    const task = selected;
    const active =
      isIntegrationRunBusy(task.latestRun) || uncertainTasks[task.id];
    const submits =
      key === 'run' ||
      key === 'resume' ||
      key === 'backfill' ||
      key === 'retry';
    if (
      submits &&
      (active || !engineAvailable || isIntegrationInitializing(task))
    ) {
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
    if (key === 'retry' && task.latestRun?.trigger === 'INITIALIZATION') {
      return;
    }
    const requestEpoch = epoch.current;
    const capturedInspectionKey = inspectionKey;
    await perform(key, async () => {
      let submissionStarted = false;
      try {
        if (submits && task.mode === 'CDC') {
          if (
            !capturedInspectionKey ||
            !cdcPreflightReady(taskPreflight.data)
          ) {
            return;
          }
          const fresh = await taskPreflight.execute((signal) =>
            preflightIntegrationTask(toIntegrationTaskInput(task), signal)
          );
          if (
            !authorized.current ||
            requestEpoch !== epoch.current ||
            capturedInspectionKey !== currentInspectionKey.current
          ) {
            return;
          }
          if (!cdcPreflightReady(fresh)) {
            setPreflightOpen(true);

            return;
          }
        }
        if (!authorized.current || requestEpoch !== epoch.current) {
          return;
        }
        if (submits) {
          const latest = executionState.current;
          if (
            !latest.engineAvailable ||
            latest.selected?.id !== task.id ||
            latest.uncertain ||
            isIntegrationRunBusy(latest.selected.latestRun) ||
            isIntegrationInitializing(latest.selected) ||
            (key === 'resume' &&
              !canResumeIntegrationTask(
                latest.selected.mode,
                latest.selected.latestRun
              )) ||
            (key === 'run' &&
              canResumeIntegrationTask(
                latest.selected.mode,
                latest.selected.latestRun
              ))
          ) {
            return;
          }
        }
        submissionStarted = submits;
        const result =
          key === 'refresh'
            ? await getIntegrationTask(task.id)
            : key === 'catalog'
            ? await syncIntegrationCatalog(task.id)
            : key === 'stop'
            ? await stopIntegrationTask(task.id, task.mode === 'CDC')
            : options
            ? await runIntegrationTask(task.id, key === 'resume', options)
            : await runIntegrationTask(task.id, key === 'resume');
        if (requestEpoch === epoch.current) {
          acceptTask(result);
          if (key === 'backfill') {
            navigationGuard.release();
            closeBackfill();
          }
        }
      } catch (error) {
        const failure = getIntegrationFailure(error);
        if (mounted.current && submissionStarted && failure.uncertain) {
          setUncertainTasks((current) => ({ ...current, [task.id]: true }));
        }
        if (requestEpoch === epoch.current) {
          setErrorCode(failure.code);
          if (
            [
              'CDC_PREFLIGHT_FAILED',
              'CDC_CONFIGURATION_CHANGED',
              'CDC_LEGACY_RESUME_UNSAFE',
              'CDC_TRUSTED_INSPECTION_REQUIRED',
              'TARGET_WRITER_ACTIVE',
              'ORACLE_CDC_OPTIONS_INVALID',
              'ORACLE_CDC_TYPE_UNSUPPORTED',
              'ORACLE_CDC_CONTAINER_RUNTIME_UNVERIFIED',
              'ORACLE_CDC_LOB_UNVERIFIED',
              'ORACLE_CDC_STRING_BYTES_UNVERIFIED',
              'ORACLE_CDC_EXTENDED_STRING_UNVERIFIED',
              'ORACLE_CDC_IDENTIFIER_UNSUPPORTED',
            ].includes(failure.code)
          ) {
            taskPreflight.invalidate();
          }
        }
      }
    });
  };
  const initializationAction = async (
    action: IntegrationInitializationAction
  ) => {
    if (
      !selected ||
      !isIntegrationInitializing(selected) ||
      !selected.initializationState ||
      confirmDiscard
    ) {
      return;
    }
    const task = selected;
    const requestEpoch = epoch.current;
    await perform(
      action === 'PAUSE' ? 'initializationPause' : 'initializationContinue',
      async () => {
        try {
          const result = await updateIntegrationInitialization(
            task.id,
            action,
            task.version
          );
          if (requestEpoch === epoch.current) {
            acceptTask(result);
            setConflict(false);
          }
        } catch (error) {
          if (requestEpoch === epoch.current) {
            const failure = getIntegrationFailure(error);
            setErrorCode(failure.code);
            setConflict(failure.code === 'VERSION_CONFLICT');
          }
        }
      }
    );
  };
  const submitBackfill = () => {
    const date = new Date(businessDate + 'T00:00:00Z');
    const valid =
      /^\d{4}-\d{2}-\d{2}$/.test(businessDate) &&
      Number.isFinite(date.getTime()) &&
      date.toISOString().slice(0, 10) === businessDate;
    setBusinessDateError(!valid);
    if (valid && !confirmDiscard) {
      void taskAction('backfill', { businessDate });
    }
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
          setEditorForm(toIntegrationTaskInput(current));
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

  const query = (params.get('q') ?? '').trim().toLocaleLowerCase();
  const filteredTasks = tasks.filter(
    (task) =>
      (!query ||
        [task.name, task.displayName, task.sourceTable, task.targetTable]
          .join(' ')
          .toLocaleLowerCase()
          .includes(query)) &&
      (!params.get('source') ||
        task.sourceConnectionId === params.get('source')) &&
      (!params.get('target') ||
        task.targetConnectionId === params.get('target')) &&
      (!params.get('mode') || task.mode === params.get('mode')) &&
      (!params.get('status') ||
        (task.latestRun?.status ?? 'NOT_RUN') === params.get('status'))
  );
  const safePage = Math.min(
    page,
    Math.max(1, Math.ceil(filteredTasks.length / 25))
  );
  const visibleTasks = filteredTasks.slice((safePage - 1) * 25, safePage * 25);
  const isDetail = view === 'detail' || view === 'edit';

  return (
    <main className="hospital-integration" data-testid="hospital-integration">
      <IntegrationPageHeader
        action={
          isAdmin &&
          !isDetail && (
            <Button
              data-testid="integration-create-action"
              iconLeading={Plus}
              isDisabled={busy || Boolean(connectionErrorCode)}
              onClick={() => {
                setSelected(undefined);
                setEditorForm({
                  ...EMPTY_INTEGRATION_TASK,
                  fieldMappings: [],
                  sourceConnectionId:
                    connections.find(
                      (item) => item.enabled && item.role === 'SOURCE'
                    )?.id ?? '',
                  targetConnectionId:
                    connections.find(
                      (item) => item.enabled && item.role === 'TARGET'
                    )?.id ?? '',
                });
                changeView('create');
              }}>
              {t('hospitalIntegration.createTask')}
            </Button>
          )
        }
        description={
          isDetail && selected
            ? selected.name
            : t('hospitalIntegration.tasksDescription')
        }
        detail={isDetail && Boolean(selected)}
        title={
          isDetail && selected
            ? selected.displayName || selected.name
            : t('hospitalIntegration.tasks')
        }
      />
      {!isAdmin ? (
        <section className="hospital-integration__empty" role="status">
          <h2>{t('hospitalIntegration.adminOnly')}</h2>
          <p>{t('hospitalIntegration.adminOnlyHint')}</p>
        </section>
      ) : (
        <>
          {isDetail && (
            <Button
              className="hospital-integration__back"
              color="link-gray"
              iconLeading={ArrowLeft}
              isDisabled={mutation.current}
              onClick={() =>
                navigate({
                  pathname: ROUTES.HOSPITAL_INTEGRATION_TASKS,
                  search: getIntegrationCollectionSearch(location.search),
                })
              }>
              {t('hospitalIntegration.backToTasks')}
            </Button>
          )}
          {errorCode && !taskEditorOpen && !backfillOpen && (
            <div className="hospital-integration__alert" role="alert">
              <p>{t(integrationErrorKey(errorCode))}</p>
              {conflict && (
                <p>{t('hospitalIntegration.initializationConflictHint')}</p>
              )}
              {routeTaskId && !selected && (
                <Button
                  color="link-gray"
                  isDisabled={busy}
                  onClick={() => void loadTask(routeTaskId)}>
                  {t('label.refresh')}
                </Button>
              )}
            </div>
          )}
          {connectionErrorCode && !taskEditorOpen && (
            <div className="hospital-integration__alert" role="alert">
              <p>{t('hospitalIntegration.connectionsRequired')}</p>
              <p>{t(integrationErrorKey(connectionErrorCode))}</p>
            </div>
          )}
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
                      'hospitalIntegration.' +
                        (engineAvailable
                          ? 'reachable'
                          : engine?.enabled === false
                          ? 'disabled'
                          : 'unreachable')
                    )}
              </span>
              {engine?.engineVersion && <small>{engine.engineVersion}</small>}
            </div>
            <Button
              aria-label={t('hospitalIntegration.refreshConnections')}
              color="link-gray"
              iconLeading={RefreshCw01}
              isDisabled={busy}
              onClick={() => {
                setErrorCode(undefined);
                setReload((value) => value + 1);
              }}>
              {t('label.refresh')}
            </Button>
          </section>
          <div data-testid="integration-tasks-list" hidden={isDetail}>
            <IntegrationCollectionTools
              filters={[
                {
                  key: 'source',
                  label: t('label.source'),
                  options: connections
                    .filter((item) => item.role === 'SOURCE')
                    .map((item) => ({ id: item.id, label: item.displayName })),
                },
                {
                  key: 'target',
                  label: t('label.target'),
                  options: connections
                    .filter((item) => item.role === 'TARGET')
                    .map((item) => ({ id: item.id, label: item.displayName })),
                },
                {
                  key: 'mode',
                  label: t('label.mode'),
                  options: [
                    { id: 'FULL', label: t('hospitalIntegration.full') },
                    { id: 'CDC', label: t('hospitalIntegration.cdc') },
                    { id: 'SQL_T1', label: t('hospitalIntegration.sqlT1') },
                  ],
                },
                {
                  key: 'status',
                  label: t('label.status'),
                  options: Array.from(
                    new Set(
                      tasks.map((task) => task.latestRun?.status ?? 'NOT_RUN')
                    )
                  ).map((id) => ({
                    id,
                    label:
                      id === 'NOT_RUN'
                        ? t('hospitalIntegration.notRun')
                        : t('hospitalIntegration.runStatus.' + id, {
                            defaultValue: id,
                          }),
                  })),
                },
              ]}
              params={params}
              onChange={update}
              onReset={reset}
            />
            <section
              aria-busy={loading.initial}
              aria-label={t('hospitalIntegration.tasks')}>
              {loading.initial ? (
                <p role="status">{t('hospitalIntegration.loading')}</p>
              ) : tasksErrorCode ? (
                <div className="hospital-integration__alert" role="alert">
                  <p>{t(integrationErrorKey(tasksErrorCode))}</p>
                  <Button
                    color="link-gray"
                    onClick={() => setReload((value) => value + 1)}>
                    {t('label.refresh')}
                  </Button>
                </div>
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
                        <th scope="col">
                          {t('hospitalIntegration.latestRun')}
                        </th>
                        <th scope="col">{t('label.updated-at')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visibleTasks.map((task) => (
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
                              {task.sourceSchema}.
                              {task.mode === 'SQL_T1'
                                ? t('hospitalIntegration.sqlResult')
                                : task.sourceTable}
                            </span>
                            <ArrowRight
                              aria-hidden="true"
                              className="hospital-integration__route-arrow"
                            />
                            <span>
                              {task.targetSchema}.{task.targetTable}
                            </span>
                          </td>
                          <td>
                            {t(integrationModeKey(task.mode))}
                            {task.mode === 'SQL_T1' && (
                              <small>
                                {t(
                                  task.schedule?.enabled
                                    ? 'label.enabled'
                                    : 'hospitalIntegration.scheduleDisabled'
                                )}
                                {task.schedule
                                  ? ` · ${task.schedule.dailyTime} · ${task.schedule.timeZone}`
                                  : ''}
                              </small>
                            )}
                          </td>
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
                              {task.latestRun?.businessDate && (
                                <small>
                                  {t('hospitalIntegration.businessDate')}:{' '}
                                  {task.latestRun.businessDate}
                                </small>
                              )}
                            </span>
                          </td>
                          <td>
                            {t(
                              `hospitalIntegration.catalogStatus.${task.catalog.status}`
                            )}
                          </td>
                          <td>
                            {task.latestRun
                              ? Number.isFinite(task.latestRun.submittedAt) &&
                                task.latestRun.submittedAt > 0
                                ? new Date(
                                    task.latestRun.submittedAt
                                  ).toLocaleString(i18n.language)
                                : t('hospitalIntegration.unavailable')
                              : t('hospitalIntegration.notRun')}
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
            {!loading.initial &&
              !tasksErrorCode &&
              filteredTasks.length === 0 &&
              tasks.length > 0 && (
                <p className="hospital-integration__filter-empty" role="status">
                  {t('label.no-data-found')}
                </p>
              )}
            {!loading.initial && !tasksErrorCode && (
              <IntegrationCollectionPagination
                page={safePage}
                total={filteredTasks.length}
                onPageChange={(next) => update('page', String(next))}
              />
            )}
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
            onClose={requestCloseEditor}>
            {confirmDiscard && (
              <IntegrationDiscardConfirmation
                onContinue={() => {
                  setDiscardRequested(false);
                  navigationGuard.stay();
                }}
                onDiscard={() => navigationGuard.proceed(closeEditor)}
              />
            )}
            {errorCode && !confirmDiscard && (
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
              disabled={busy || confirmDiscard}
              errors={formErrors}
              initializationState={
                view === 'edit' ? selected?.initializationState : undefined
              }
              initializationUnavailable={
                view === 'edit' &&
                !selected?.initialization?.enabled &&
                Boolean(selected?.scheduleState?.pendingBusinessDate)
              }
              key={editorSession}
              navigationBlocked={navigationGuard.blocked}
              preflight={taskPreflight.data}
              preflightErrorCode={taskPreflight.errorCode}
              preflightLoading={taskPreflight.loading}
              queryErrorCode={queryData.errorCode}
              queryLoading={queryData.loading}
              saving={loading.save}
              sourceTables={sourceTables}
              tablesLoading={tablesLoading}
              tablesUnavailable={Boolean(
                sourceData.errorCode || targetData.errorCode
              )}
              targetTables={targetTables}
              validating={loading.validate}
              value={form}
              onCancel={requestCloseEditor}
              onChange={(value) => {
                setForm(value);
                setFormErrors([]);
              }}
              onDiscardNavigation={() => navigationGuard.proceed(closeEditor)}
              onKeepNavigation={navigationGuard.stay}
              onPreflight={() => void checkTaskPreflight()}
              onReadQuerySchema={() => void queryData.read()}
              onReloadTables={() => setTablesReload((current) => current + 1)}
              onRuntimeDraftChange={runtimeDraftChange}
              onRuntimeValidityChange={setRuntimeInvalid}
              onSave={() => void validateOrSave(true)}
              onTargetTableBusyChange={targetTableBusyChange}
              onTargetTableCreated={(table, fieldMappings) => {
                targetData.acceptTable(table);
                setForm((current) => ({
                  ...current,
                  targetSchema: table.schema,
                  targetTable: table.name,
                  fieldMappings,
                  primaryKey:
                    current.primaryKey ||
                    sourceDetail?.columns.find((column) => column.primaryKey)
                      ?.name ||
                    '',
                }));
                setTargetTablesReload((current) => current + 1);
                setFormErrors([]);
              }}
              onTargetTableDraftChange={targetTableDraftChange}
              onValidate={() => void validateOrSave(false)}
            />
          </FormDrawer>
          <FormDrawer
            destroyOnClose
            className="hospital-integration hospital-integration--drawer"
            data-testid="integration-task-preflight-drawer"
            footer={
              <>
                <Button
                  color="secondary"
                  isDisabled={taskPreflight.loading || busy}
                  isLoading={taskPreflight.loading}
                  onClick={() => void checkTaskPreflight()}>
                  {t('hospitalCdc.check')}
                </Button>
              </>
            }
            isOpen={preflightOpen && view === 'detail'}
            title={t('hospitalCdc.taskPreflight')}
            width={720}
            onClose={() => {
              taskPreflight.cancel();
              setPreflightOpen(false);
            }}>
            <IntegrationCdcPreflight
              errorCode={taskPreflight.errorCode}
              loading={taskPreflight.loading}
              result={taskPreflight.data}
            />
          </FormDrawer>
          <FormDrawer
            destroyOnClose
            className="hospital-integration hospital-integration--drawer"
            data-testid="integration-backfill-drawer"
            isOpen={backfillOpen}
            isSubmitting={busy}
            title={t('hospitalIntegration.backfill')}
            width={720}
            onClose={requestCloseBackfill}>
            {confirmDiscard && (
              <IntegrationDiscardConfirmation
                onContinue={() => {
                  setDiscardRequested(false);
                  navigationGuard.stay();
                }}
                onDiscard={() => navigationGuard.proceed(closeBackfill)}
              />
            )}
            <form
              noValidate
              className="hospital-integration__form"
              id="integration-backfill-form"
              onSubmit={(event) => {
                event.preventDefault();
                submitBackfill();
              }}>
              <p>{t('hospitalIntegration.backfillHint')}</p>
              {(businessDateError || errorCode) && (
                <IntegrationDrawerAlert
                  focusKey={
                    businessDateError
                      ? 'businessDateInvalid'
                      : errorCode ?? 'REQUEST_FAILED'
                  }>
                  <p>
                    {t(
                      businessDateError
                        ? 'hospitalIntegration.businessDateInvalid'
                        : integrationErrorKey(errorCode)
                    )}
                  </p>
                </IntegrationDrawerAlert>
              )}
              <Input
                isRequired
                id="integration-business-date"
                isDisabled={busy || confirmDiscard}
                label={t('hospitalIntegration.businessDate')}
                maxLength={10}
                placeholder="yyyy-MM-dd"
                value={businessDate}
                onChange={(value) => {
                  setBusinessDate(value);
                  setBusinessDateError(false);
                  setErrorCode(undefined);
                }}
              />
              <p className="hospital-integration__hint">
                {t('hospitalIntegration.mergeHint')}
              </p>
              <FormDrawerActions>
                <div className="hospital-integration__actions">
                  <Button
                    form="integration-backfill-form"
                    isDisabled={
                      busy ||
                      confirmDiscard ||
                      !engineAvailable ||
                      Boolean(
                        selected &&
                          (isIntegrationInitializing(selected) ||
                            isIntegrationRunBusy(selected.latestRun) ||
                            uncertainTasks[selected.id])
                      )
                    }
                    isLoading={loading.backfill}
                    type="submit">
                    {t('label.run')}
                  </Button>
                  <Button
                    color="tertiary"
                    isDisabled={busy}
                    onClick={requestCloseBackfill}>
                    {t('label.cancel')}
                  </Button>
                </div>
              </FormDrawerActions>
            </form>
          </FormDrawer>
          {routeTaskId && !selected && loading.detail && (
            <p role="status">{t('hospitalIntegration.loading')}</p>
          )}
          {selected && (
            <div
              data-testid="integration-task-detail"
              hidden={view !== 'detail' && view !== 'edit'}>
              <IntegrationTaskDetail
                busy={busy}
                cdcReady={cdcPreflightReady(taskPreflight.data)}
                engineAvailable={engineAvailable}
                loading={loading}
                showTitle={false}
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
                targetCount={targetCount.data}
                targetCountErrorCode={targetCount.errorCode}
                targetCountLoading={targetCount.loading}
                task={selected}
                uncertain={
                  Boolean(uncertainTasks[selected.id]) ||
                  isIntegrationRunUncertain(selected.latestRun)
                }
                onCatalog={() => void taskAction('catalog')}
                onEdit={() => {
                  setEditorForm(toIntegrationTaskInput(selected));
                  setEditingVersion(selected.version);
                  changeView('edit');
                }}
                onInitialization={(action) => void initializationAction(action)}
                onPreflight={() => void checkTaskPreflight()}
                onRefresh={() => void taskAction('refresh')}
                onRetry={() => {
                  if (selected.latestRun?.businessDate) {
                    void taskAction('retry', {
                      businessDate: selected.latestRun.businessDate,
                      retry: true,
                    });
                  }
                }}
                onRun={(resume) =>
                  selected.mode === 'SQL_T1'
                    ? openBackfill()
                    : void taskAction(resume ? 'resume' : 'run')
                }
                onStop={() => void taskAction('stop')}
                onTargetCount={() => {
                  if (isAdmin && selected && view === 'detail') {
                    void targetCount.execute((signal) =>
                      getIntegrationTargetCount(selected.id, signal)
                    );
                  }
                }}
              />
            </div>
          )}
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
    <PageLayoutV1 pageTitle={t('hospitalIntegration.tasks')}>
      <HospitalIntegrationWorkspace isAdmin={isAdmin} />
    </PageLayoutV1>
  );
};

export default HospitalIntegrationPage;
