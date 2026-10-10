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
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import FormDrawer from '../../components/common/atoms/drawer/FormDrawer';
import {
  createIntegrationConnection,
  deleteIntegrationConnection,
  getIntegrationConnection,
  getIntegrationFailure,
  IntegrationConnection,
  testIntegrationConnection,
  updateIntegrationConnection,
} from '../../rest/hospitalIntegrationAPI';
import { showSuccessToast } from '../../utils/ToastUtils';
import { IntegrationConnectionsProps } from './HospitalIntegrationPage.interface';
import {
  emptyIntegrationConnection,
  integrationErrorKey,
  toIntegrationConnectionInput,
  validateIntegrationConnectionInput,
} from './HospitalIntegrationUtils';
import { IntegrationConnectionForm } from './IntegrationConnectionForm';
import { IntegrationDrawerAlert } from './IntegrationDrawerAlert';

type ConnectionTestState = {
  version: number;
  status: 'pending' | 'success' | 'error';
  errorCode?: string;
};

export const IntegrationConnections = ({
  connections,
  loading,
  createRequest,
  onChange,
  onBusyChange,
}: IntegrationConnectionsProps) => {
  const { t } = useTranslation();
  const [editor, setEditor] = useState<IntegrationConnection | 'create'>();
  const [draft, setDraft] = useState(emptyIntegrationConnection);
  const [schemasText, setSchemasText] = useState('public');
  const [errors, setErrors] = useState<string[]>([]);
  const [errorCode, setErrorCode] = useState<string>();
  const [pending, setPending] = useState<string>();
  const [tests, setTests] = useState<Record<string, ConnectionTestState>>({});
  const [deleteId, setDeleteId] = useState<string>();
  const [deleteErrors, setDeleteErrors] = useState<Record<string, string>>({});
  const mutation = useRef(false);
  const mounted = useRef(true);
  const epoch = useRef(0);
  const reloadRequest = useRef<AbortController>();
  const lastCreateRequest = useRef(createRequest);
  const editing = editor !== undefined && editor !== 'create';
  const busy = pending !== undefined;

  useEffect(() => {
    mounted.current = true;

    return () => {
      mounted.current = false;
      epoch.current += 1;
      reloadRequest.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (createRequest === lastCreateRequest.current) {
      return;
    }
    lastCreateRequest.current = createRequest;
    epoch.current += 1;
    setEditor('create');
    setDraft(emptyIntegrationConnection());
    setSchemasText('public');
    setErrors([]);
    setErrorCode(undefined);
    setDeleteId(undefined);
  }, [createRequest]);

  const close = () => {
    if (mutation.current) {
      return;
    }
    epoch.current += 1;
    reloadRequest.current?.abort();
    setEditor(undefined);
    setDraft(emptyIntegrationConnection());
    setSchemasText('public');
    setErrors([]);
    setErrorCode(undefined);
  };
  const open = (connection: IntegrationConnection) => {
    if (mutation.current || connection.managed) {
      return;
    }
    epoch.current += 1;
    setEditor(connection);
    setDraft(toIntegrationConnectionInput(connection));
    setSchemasText(connection.schemas.join(', '));
    setErrors([]);
    setErrorCode(undefined);
    setDeleteId(undefined);
  };
  const perform = async (key: string, action: () => Promise<void>) => {
    if (mutation.current) {
      return;
    }
    mutation.current = true;
    onBusyChange(true);
    setPending(key);
    try {
      await action();
    } finally {
      mutation.current = false;
      onBusyChange(false);
      if (mounted.current) {
        setPending(undefined);
      }
    }
  };
  const save = async () => {
    if (!editor || mutation.current) {
      return;
    }
    const { password, ...definition } = draft;
    const input = {
      ...definition,
      schemas:
        draft.databaseType === 'Mysql'
          ? [draft.database]
          : schemasText.split(',').map((schema) => schema.trim()),
      ...(password ? { password } : {}),
    };
    const nextErrors = validateIntegrationConnectionInput(input, editing);
    setErrors(nextErrors);
    if (nextErrors.length > 0) {
      return;
    }
    const requestEpoch = epoch.current;
    await perform('save', async () => {
      setErrorCode(undefined);
      try {
        const saved =
          editor === 'create'
            ? await createIntegrationConnection(input)
            : await updateIntegrationConnection(
                editor.id,
                input,
                editor.version
              );
        if (!mounted.current || requestEpoch !== epoch.current) {
          return;
        }
        onChange([
          ...connections.filter((item) => item.id !== saved.id),
          saved,
        ]);
        setTests((current) => {
          const next = { ...current };
          delete next[saved.id];

          return next;
        });
        setEditor(undefined);
        setDraft(emptyIntegrationConnection());
        setSchemasText('public');
        setErrors([]);
        epoch.current += 1;
        showSuccessToast(t('hospitalIntegration.connectionSaved'));
      } catch (error) {
        if (mounted.current && requestEpoch === epoch.current) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      }
    });
  };
  const reloadCurrent = async () => {
    if (!editor || editor === 'create' || mutation.current) {
      return;
    }
    const requestEpoch = epoch.current;
    const controller = new AbortController();
    reloadRequest.current?.abort();
    reloadRequest.current = controller;
    await perform('reload', async () => {
      try {
        const current = await getIntegrationConnection(
          editor.id,
          controller.signal
        );
        if (
          controller.signal.aborted ||
          !mounted.current ||
          requestEpoch !== epoch.current
        ) {
          return;
        }
        setEditor(current);
        setDraft(toIntegrationConnectionInput(current));
        setSchemasText(current.schemas.join(', '));
        setErrors([]);
        setErrorCode(undefined);
        onChange(
          connections.map((item) => (item.id === current.id ? current : item))
        );
      } catch (error) {
        if (
          !controller.signal.aborted &&
          mounted.current &&
          requestEpoch === epoch.current
        ) {
          setErrorCode(getIntegrationFailure(error).code);
        }
      }
    });
  };
  const test = async (connection: IntegrationConnection) => {
    await perform('test:' + connection.id, async () => {
      setTests((current) => ({
        ...current,
        [connection.id]: { version: connection.version, status: 'pending' },
      }));
      try {
        const result = await testIntegrationConnection(connection.id);
        if (mounted.current) {
          setTests((current) => ({
            ...current,
            [connection.id]: {
              version: connection.version,
              status: result.connected ? 'success' : 'error',
              errorCode: result.connected
                ? undefined
                : result.errorCode ?? 'CONNECTION_UNAVAILABLE',
            },
          }));
        }
      } catch (error) {
        if (mounted.current) {
          setTests((current) => ({
            ...current,
            [connection.id]: {
              version: connection.version,
              status: 'error',
              errorCode: getIntegrationFailure(error).code,
            },
          }));
        }
      }
    });
  };
  const remove = async (connection: IntegrationConnection) => {
    if (connection.managed || deleteId !== connection.id) {
      return;
    }
    await perform('delete:' + connection.id, async () => {
      setDeleteErrors((current) => ({ ...current, [connection.id]: '' }));
      try {
        await deleteIntegrationConnection(connection.id, connection.version);
        if (mounted.current) {
          onChange(connections.filter((item) => item.id !== connection.id));
          setDeleteId(undefined);
        }
      } catch (error) {
        if (mounted.current) {
          setDeleteErrors((current) => ({
            ...current,
            [connection.id]: getIntegrationFailure(error).code,
          }));
        }
      }
    });
  };
  const databaseLabel = (connection: IntegrationConnection) =>
    t('hospitalIntegration.databaseTypes.' + connection.databaseType);

  return (
    <>
      <section
        aria-busy={loading}
        aria-labelledby="integration-connections-title">
        <div className="hospital-integration__section-heading">
          <div>
            <h2 id="integration-connections-title">
              {t('hospitalIntegration.dataSources')}
            </h2>
            <p>{t('hospitalIntegration.connectionSavedHint')}</p>
          </div>
        </div>
        {loading ? (
          <p role="status">{t('hospitalIntegration.loading')}</p>
        ) : connections.length === 0 ? (
          <div className="hospital-integration__empty">
            <h3>{t('hospitalIntegration.noConnections')}</h3>
            <p>{t('hospitalIntegration.noConnectionsHint')}</p>
          </div>
        ) : (
          <div className="hospital-integration__table-scroll">
            <table data-testid="integration-connections-table">
              <thead>
                <tr>
                  <th scope="col">{t('label.name')}</th>
                  <th scope="col">
                    {t('label.type')} / {t('label.database-version')}
                  </th>
                  <th scope="col">{t('label.role')}</th>
                  <th scope="col">
                    {t('hospitalIntegration.host')} / {t('label.database')}
                  </th>
                  <th scope="col">{t('label.scope')}</th>
                  <th scope="col">{t('label.status')}</th>
                  <th scope="col">{t('label.action-plural')}</th>
                </tr>
              </thead>
              <tbody>
                {connections.map((connection) => {
                  const result =
                    tests[connection.id]?.version === connection.version
                      ? tests[connection.id]
                      : undefined;
                  const testLabel =
                    result?.status === 'pending'
                      ? 'connectionTesting'
                      : result?.status === 'success'
                      ? 'connectionPassed'
                      : result?.status === 'error'
                      ? 'connectionFailed'
                      : 'connectionUntested';

                  return (
                    <tr
                      data-testid={
                        'integration-connection-row-' + connection.id
                      }
                      key={connection.id}>
                      <td>
                        <strong>{connection.displayName}</strong>
                        <small>{connection.name}</small>
                        {connection.synthetic && (
                          <small>{t('hospitalIntegration.synthetic')}</small>
                        )}
                      </td>
                      <td>
                        {databaseLabel(connection)}
                        <small>{connection.databaseVersion}</small>
                      </td>
                      <td>
                        {t(
                          connection.role === 'SOURCE'
                            ? 'label.source'
                            : 'label.target'
                        )}
                      </td>
                      <td>
                        <span>
                          {connection.host}:{connection.port}
                        </span>
                        <small>{connection.database}</small>
                      </td>
                      <td>
                        {connection.schemas.join(', ')}
                        {connection.managed && (
                          <small>
                            {t('hospitalIntegration.environmentManaged')}
                          </small>
                        )}
                      </td>
                      <td>
                        <span>
                          {t(
                            connection.enabled
                              ? 'label.enabled'
                              : 'label.disabled'
                          )}
                        </span>
                        <small
                          className="hospital-integration__status"
                          data-status={
                            result?.status === 'success'
                              ? 'SYNCED'
                              : result?.status === 'error'
                              ? 'FAILED'
                              : undefined
                          }
                          role="status">
                          {t('hospitalIntegration.' + testLabel)}
                        </small>
                        {result?.errorCode && (
                          <p
                            className="hospital-integration__inline-error"
                            role="alert">
                            {t(integrationErrorKey(result.errorCode))}
                          </p>
                        )}
                      </td>
                      <td>
                        <div className="hospital-integration__connection-actions">
                          <Button
                            color="link-color"
                            isDisabled={busy || loading || !connection.enabled}
                            isLoading={pending === 'test:' + connection.id}
                            onClick={() => void test(connection)}>
                            {t('hospitalIntegration.testConnection')}
                          </Button>
                          {!connection.managed && (
                            <>
                              <Button
                                color="link-gray"
                                isDisabled={busy || loading}
                                onClick={() => open(connection)}>
                                {t('label.edit')}
                              </Button>
                              <Button
                                color="link-gray"
                                isDisabled={busy || loading}
                                onClick={() => {
                                  setDeleteId(connection.id);
                                  setDeleteErrors((current) => ({
                                    ...current,
                                    [connection.id]: '',
                                  }));
                                }}>
                                {t('label.delete')}
                              </Button>
                            </>
                          )}
                        </div>
                        {deleteId === connection.id && (
                          <div className="hospital-integration__delete-confirm">
                            <p>
                              {t('hospitalIntegration.deleteConnectionHint', {
                                name: connection.displayName,
                              })}
                            </p>
                            <div className="hospital-integration__actions">
                              <Button
                                color="primary-destructive"
                                isDisabled={busy}
                                isLoading={
                                  pending === 'delete:' + connection.id
                                }
                                onClick={() => void remove(connection)}>
                                {t(
                                  'hospitalIntegration.confirmDeleteConnection'
                                )}
                              </Button>
                              <Button
                                color="tertiary"
                                isDisabled={busy}
                                onClick={() => setDeleteId(undefined)}>
                                {t('label.cancel')}
                              </Button>
                            </div>
                            {deleteErrors[connection.id] && (
                              <p
                                className="hospital-integration__inline-error"
                                role="alert">
                                {t(
                                  integrationErrorKey(
                                    deleteErrors[connection.id]
                                  )
                                )}
                              </p>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <FormDrawer
        destroyOnClose
        className="hospital-integration hospital-integration--drawer"
        data-testid="integration-connection-drawer"
        footer={
          <>
            <Button color="secondary" isDisabled={busy} onClick={close}>
              {t('label.cancel')}
            </Button>
            <Button
              form="integration-connection-form"
              isDisabled={
                busy || (typeof editor === 'object' && editor.managed)
              }
              isLoading={pending === 'save'}
              type="submit">
              {t('label.save')}
            </Button>
          </>
        }
        isOpen={editor !== undefined}
        isSubmitting={busy}
        title={t(
          editing
            ? 'hospitalIntegration.editConnection'
            : 'hospitalIntegration.createConnection'
        )}
        width={720}
        onClose={close}>
        {errorCode && (
          <IntegrationDrawerAlert focusKey={errorCode}>
            {errorCode !== 'VERSION_CONFLICT' && (
              <p>{t(integrationErrorKey(errorCode))}</p>
            )}
            {errorCode === 'VERSION_CONFLICT' && (
              <>
                <p>{t('hospitalIntegration.connectionConflictHint')}</p>
                <Button
                  color="link-color"
                  data-testid="integration-connection-reload"
                  isDisabled={busy}
                  isLoading={pending === 'reload'}
                  onClick={() => void reloadCurrent()}>
                  {t('hospitalIntegration.reloadCurrent')}
                </Button>
              </>
            )}
          </IntegrationDrawerAlert>
        )}
        <IntegrationConnectionForm
          disabled={busy}
          editing={editing}
          errors={errors}
          schemasText={schemasText}
          value={draft}
          onChange={(value) => {
            setDraft(value);
            setErrors([]);
          }}
          onSave={() => void save()}
          onSchemasChange={(value) => {
            setSchemasText(value);
            setErrors([]);
          }}
        />
      </FormDrawer>
    </>
  );
};
