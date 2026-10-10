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
import { Link } from 'react-router-dom';
import { IntegrationRun } from '../../rest/hospitalIntegrationAPI';
import { IntegrationTaskDetailProps } from './HospitalIntegrationPage.interface';
import {
  canResumeIntegrationTask,
  integrationErrorKey,
  isIntegrationRunBusy,
} from './HospitalIntegrationUtils';

export const IntegrationTaskDetail = ({
  task,
  sourceConnectionName,
  targetConnectionName,
  busy,
  uncertain,
  engineAvailable,
  loading,
  onEdit,
  onRefresh,
  onRun,
  onStop,
  onCatalog,
}: IntegrationTaskDetailProps) => {
  const { t, i18n } = useTranslation();
  const run = task.latestRun;
  const active = isIntegrationRunBusy(run) || uncertain;
  const resumable = canResumeIntegrationTask(task.mode, run);
  const timestamp = (value?: number) =>
    value === undefined
      ? t('hospitalIntegration.unavailable')
      : new Date(value).toLocaleString(i18n.language);
  const count = (value?: number) =>
    value === undefined || !Number.isFinite(value)
      ? t('hospitalIntegration.unavailable')
      : value.toLocaleString(i18n.language);
  const statusLabel = (item?: IntegrationRun) =>
    item
      ? t(`hospitalIntegration.runStatus.${item.status}`, {
          defaultValue: item.status,
        })
      : t('hospitalIntegration.notRun');

  return (
    <div className="hospital-integration__detail">
      <div className="hospital-integration__section-heading">
        <div>
          <h2>{task.displayName || task.name}</h2>
          <p>{task.name}</p>
        </div>
        <div className="hospital-integration__actions">
          <Button
            color="secondary"
            isDisabled={busy || active || resumable}
            onClick={onEdit}>
            {t('label.edit')}
          </Button>
          <Button
            color="secondary"
            isDisabled={busy}
            isLoading={loading.refresh}
            onClick={onRefresh}>
            {t('label.refresh')}
          </Button>
        </div>
      </div>
      <dl className="hospital-integration__route-summary">
        <div>
          <dt>{t('hospitalIntegration.sourceTable')}</dt>
          <dd>{`${task.sourceSchema}.${task.sourceTable}`}</dd>
          <small>
            {sourceConnectionName ?? t('hospitalIntegration.sourceConnection')}
          </small>
        </div>
        <div>
          <dt>{t('hospitalIntegration.targetTable')}</dt>
          <dd>{`${task.targetSchema}.${task.targetTable}`}</dd>
          <small>
            {targetConnectionName ?? t('hospitalIntegration.targetConnection')}
          </small>
        </div>
        <div>
          <dt>{t('label.mode')}</dt>
          <dd>
            {t(`hospitalIntegration.${task.mode === 'CDC' ? 'cdc' : 'full'}`)}
          </dd>
          <small>
            {task.primaryKey
              ? `${t('label.primary-key')}: ${task.primaryKey}`
              : ''}
          </small>
        </div>
      </dl>
      <section aria-labelledby="integration-run-title">
        <div className="hospital-integration__section-heading">
          <h2 id="integration-run-title">
            {t('hospitalIntegration.latestRun')}
          </h2>
          <span
            className="hospital-integration__status"
            data-status={run?.status}>
            {statusLabel(run)}
          </span>
        </div>
        {uncertain && (
          <div className="hospital-integration__alert" role="alert">
            {t('hospitalIntegration.errors.SUBMISSION_UNKNOWN')}
          </div>
        )}
        {run ? (
          <dl className="hospital-integration__facts">
            <div className="hospital-integration__job-id">
              <dt>{t('hospitalIntegration.jobId')}</dt>
              <dd data-testid="integration-job-id">{run.jobId}</dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.submittedAt')}</dt>
              <dd>{timestamp(run.submittedAt)}</dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.finishedAt')}</dt>
              <dd>{timestamp(run.finishedAt)}</dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.sourceCount')}</dt>
              <dd data-testid="integration-source-count">
                {count(run.sourceReceivedCount)}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.sinkCount')}</dt>
              <dd data-testid="integration-sink-count">
                {count(run.sinkWriteCount)}
              </dd>
            </div>
          </dl>
        ) : (
          <p className="hospital-integration__mapping-empty">
            {t('hospitalIntegration.runHint')}
          </p>
        )}
        {run && (
          <p className="hospital-integration__hint">
            {t('hospitalIntegration.counterHint')}
          </p>
        )}
        {run?.errorCode && (
          <div className="hospital-integration__alert" role="alert">
            <p>{t(integrationErrorKey(run.errorCode))}</p>
            <small>{run.errorCode}</small>
          </div>
        )}
        <div className="hospital-integration__actions">
          <Button
            isDisabled={busy || active || resumable || !engineAvailable}
            isLoading={loading.run}
            onClick={() => onRun(false)}>
            {t('label.run')}
          </Button>
          <Button
            color="secondary"
            isDisabled={
              busy || !active || uncertain || !run?.jobId || !engineAvailable
            }
            isLoading={loading.stop}
            onClick={onStop}>
            {t(
              task.mode === 'CDC'
                ? 'hospitalIntegration.stopWithSavepoint'
                : 'label.stop'
            )}
          </Button>
          <Button
            color="secondary"
            isDisabled={
              busy ||
              active ||
              !engineAvailable ||
              !canResumeIntegrationTask(task.mode, run)
            }
            isLoading={loading.resume}
            onClick={() => onRun(true)}>
            {t('label.resume')}
          </Button>
        </div>
        <p className="hospital-integration__hint">
          {t('hospitalIntegration.recoveryHint')}
        </p>
        {resumable && (
          <p className="hospital-integration__hint" role="status">
            {t('hospitalIntegration.pausedHint')}
          </p>
        )}
      </section>
      <section aria-labelledby="integration-catalog-title">
        <div className="hospital-integration__section-heading">
          <div>
            <h2 id="integration-catalog-title">
              {t('hospitalIntegration.catalogSync')}
            </h2>
            <p>{t('hospitalIntegration.catalogHint')}</p>
          </div>
          <span
            className="hospital-integration__status"
            data-status={task.catalog.status}>
            {t(`hospitalIntegration.catalogStatus.${task.catalog.status}`)}
          </span>
        </div>
        {task.catalog.status === 'FAILED' && (
          <div className="hospital-integration__alert" role="alert">
            <p>{t('hospitalIntegration.errors.CATALOG_SYNC_FAILED')}</p>
            {task.catalog.errorCode && <small>{task.catalog.errorCode}</small>}
          </div>
        )}
        <div className="hospital-integration__catalog-links">
          {task.catalog.sourceFqn && (
            <Link to={`/table/${encodeURIComponent(task.catalog.sourceFqn)}`}>
              {t('hospitalIntegration.sourceAsset')}
            </Link>
          )}
          {task.catalog.targetFqn && (
            <Link to={`/table/${encodeURIComponent(task.catalog.targetFqn)}`}>
              {t('hospitalIntegration.targetAsset')}
            </Link>
          )}
          {task.catalog.pipelineFqn && (
            <Link
              to={`/pipeline/${encodeURIComponent(task.catalog.pipelineFqn)}`}>
              {t('hospitalIntegration.pipelineAsset')}
            </Link>
          )}
        </div>
        {task.catalog.syncedAt !== undefined && (
          <p className="hospital-integration__hint">
            {timestamp(task.catalog.syncedAt)}
          </p>
        )}
        <Button
          color="secondary"
          isDisabled={busy}
          isLoading={loading.catalog}
          onClick={onCatalog}>
          {t(
            task.catalog.status === 'FAILED'
              ? 'hospitalIntegration.retryCatalog'
              : 'hospitalIntegration.syncCatalog'
          )}
        </Button>
      </section>
      <section aria-labelledby="integration-config-title">
        <h2 id="integration-config-title">
          {t('hospitalIntegration.fieldMappings')}
        </h2>
        <div className="hospital-integration__table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('label.source')}</th>
                <th scope="col">{t('label.target')}</th>
              </tr>
            </thead>
            <tbody>
              {task.fieldMappings.map((mapping) => (
                <tr key={mapping.source}>
                  <td>{mapping.source}</td>
                  <td>{mapping.target}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      {task.runs.length > 0 && (
        <section aria-labelledby="integration-history-title">
          <h2 id="integration-history-title">
            {t('hospitalIntegration.runHistory')}
          </h2>
          <div className="hospital-integration__table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('hospitalIntegration.jobId')}</th>
                  <th scope="col">{t('label.status')}</th>
                  <th scope="col">{t('hospitalIntegration.submittedAt')}</th>
                  <th scope="col">{t('hospitalIntegration.finishedAt')}</th>
                </tr>
              </thead>
              <tbody>
                {task.runs.map((item) => (
                  <tr key={item.jobId}>
                    <td>{item.jobId}</td>
                    <td>{statusLabel(item)}</td>
                    <td>{timestamp(item.submittedAt)}</td>
                    <td>{timestamp(item.finishedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      <dl className="hospital-integration__audit">
        <div>
          <dt>{t('label.version')}</dt>
          <dd>{task.version}</dd>
        </div>
        <div>
          <dt>{t('label.updated-at')}</dt>
          <dd>{timestamp(task.updatedAt)}</dd>
        </div>
        <div>
          <dt>{t('hospitalIntegration.updatedBy')}</dt>
          <dd>{task.updatedBy}</dd>
        </div>
      </dl>
    </div>
  );
};
