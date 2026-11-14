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
  getIntegrationInitializationPreview,
  integrationErrorKey,
  integrationModeKey,
  isIntegrationInitializing,
  isIntegrationRunBusy,
} from './HospitalIntegrationUtils';

export const IntegrationTaskDetail = ({
  task,
  showTitle = true,
  sourceConnectionName,
  targetConnectionName,
  busy,
  uncertain,
  engineAvailable,
  loading,
  onEdit,
  onRefresh,
  onRun,
  onRetry,
  onStop,
  onCatalog,
  onInitialization,
  cdcReady = false,
  onPreflight,
  targetCount,
  targetCountLoading,
  targetCountErrorCode,
  onTargetCount,
}: IntegrationTaskDetailProps) => {
  const { t, i18n } = useTranslation();
  const run = task.latestRun;
  const active = isIntegrationRunBusy(run) || uncertain;
  const resumable = canResumeIntegrationTask(task.mode, run);
  const sqlMode = task.mode === 'SQL_T1';
  const initialization = task.initializationState;
  const initializing = isIntegrationInitializing(task);
  const nextBatch =
    initialization && initialization.status !== 'COMPLETED'
      ? getIntegrationInitializationPreview(
          {
            enabled: true,
            startDate: initialization.nextDate,
            batchUnit: initialization.batchUnit,
          },
          initialization.cutoffDate
        )
      : undefined;
  const pendingStart =
    initialization?.pendingStartDate ?? nextBatch?.firstStartDate;
  const pendingEnd = initialization?.pendingEndDate ?? nextBatch?.firstEndDate;
  const canRetry =
    sqlMode &&
    !initializing &&
    run?.trigger !== 'INITIALIZATION' &&
    Boolean(run?.businessDate) &&
    ['FAILED', 'STOPPED', 'CANCELED', 'CANCELLED'].includes(run?.status ?? '');
  const timestamp = (value?: number | null) =>
    typeof value !== 'number' || !Number.isFinite(value)
      ? t('hospitalIntegration.unavailable')
      : new Date(value).toLocaleString(i18n.language);
  const count = (value?: number | null) =>
    typeof value !== 'number' || !Number.isFinite(value)
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
          <h2>
            {showTitle ? task.displayName || task.name : t('label.overview')}
          </h2>
          {showTitle && <p>{task.name}</p>}
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
          <dd>
            {sqlMode
              ? `${task.sourceSchema} · ${t('hospitalIntegration.sqlResult')}`
              : `${task.sourceSchema}.${task.sourceTable}`}
          </dd>
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
          <dd>{t(integrationModeKey(task.mode))}</dd>
          <small>
            {task.primaryKey
              ? `${t('label.primary-key')}: ${task.primaryKey}`
              : ''}
          </small>
        </div>
      </dl>
      {sqlMode && initialization && (
        <section aria-labelledby="integration-initialization-detail-title">
          <div className="hospital-integration__section-heading">
            <h2 id="integration-initialization-detail-title">
              {t('hospitalIntegration.historicalInitialization')}
            </h2>
            <span
              className="hospital-integration__status"
              data-status={initialization.status}>
              {t(
                `hospitalIntegration.initializationStatus.${initialization.status}`
              )}
            </span>
          </div>
          <dl className="hospital-integration__facts">
            <div>
              <dt>{t('hospitalIntegration.initializationBatchProgress')}</dt>
              <dd data-testid="integration-initialization-progress">
                {count(initialization.completedBatches)} /{' '}
                {count(initialization.totalBatches)}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.initializationBatchUnit')}</dt>
              <dd>
                {t(
                  initialization.batchUnit === 'MONTH'
                    ? 'hospitalIntegration.initializationMonth'
                    : 'hospitalIntegration.initializationDay'
                )}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.initializationCoveredWindow')}</dt>
              <dd>
                {initialization.completedBatches > 0
                  ? `[${initialization.startDate}, ${initialization.nextDate})`
                  : t('hospitalIntegration.initializationNoneCovered')}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.initializationCutoffDate')}</dt>
              <dd>{initialization.cutoffDate}</dd>
            </div>
            {initialization.status !== 'COMPLETED' && (
              <div>
                <dt>{t('hospitalIntegration.initializationNextWindow')}</dt>
                <dd data-testid="integration-initialization-next-window">
                  {pendingStart && pendingEnd
                    ? `[${pendingStart}, ${pendingEnd})`
                    : t('hospitalIntegration.unavailable')}
                </dd>
              </div>
            )}
            {initialization.pendingJobId &&
              initialization.status !== 'COMPLETED' && (
                <div className="hospital-integration__job-id">
                  <dt>{t('hospitalIntegration.jobId')}</dt>
                  <dd>{initialization.pendingJobId}</dd>
                </div>
              )}
          </dl>
          {initialization.lastError &&
            initialization.status !== 'COMPLETED' && (
              <div className="hospital-integration__alert" role="alert">
                {t(integrationErrorKey(initialization.lastError))}
              </div>
            )}
          <p className="hospital-integration__hint">
            {t(
              initialization.status === 'COMPLETED'
                ? 'hospitalIntegration.initializationCompleteHint'
                : 'hospitalIntegration.initializationProgressHint'
            )}
          </p>
          {initializing && (
            <>
              <div className="hospital-integration__actions">
                {task.schedule?.enabled && (
                  <Button
                    color="secondary"
                    isDisabled={busy}
                    isLoading={loading.initializationPause}
                    onClick={() => onInitialization?.('PAUSE')}>
                    {t('hospitalIntegration.pauseInitialization')}
                  </Button>
                )}
                {(!task.schedule?.enabled ||
                  initialization.status === 'FAILED') && (
                  <Button
                    isDisabled={busy}
                    isLoading={loading.initializationContinue}
                    onClick={() => onInitialization?.('CONTINUE')}>
                    {t('hospitalIntegration.continueInitialization')}
                  </Button>
                )}
              </div>
              <p className="hospital-integration__hint">
                {t(
                  task.schedule?.enabled
                    ? 'hospitalIntegration.initializationPauseHint'
                    : 'hospitalIntegration.initializationPausedHint'
                )}
              </p>
            </>
          )}
        </section>
      )}
      {sqlMode && (
        <section aria-labelledby="integration-schedule-detail-title">
          <h2 id="integration-schedule-detail-title">{t('label.schedule')}</h2>
          <dl className="hospital-integration__facts">
            <div>
              <dt>{t('label.status')}</dt>
              <dd>
                {t(
                  task.schedule?.enabled
                    ? 'label.enabled'
                    : 'hospitalIntegration.scheduleDisabled'
                )}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.dailyTime')}</dt>
              <dd>
                {task.schedule?.dailyTime ??
                  t('hospitalIntegration.unavailable')}
              </dd>
            </div>
            <div>
              <dt>{t('label.timezone')}</dt>
              <dd>
                {task.schedule?.timeZone ??
                  t('hospitalIntegration.unavailable')}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.nextRunAt')}</dt>
              <dd>{timestamp(task.scheduleState?.nextRunAt)}</dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.lastBusinessDate')}</dt>
              <dd>
                {task.scheduleState?.lastBusinessDate ??
                  t('hospitalIntegration.unavailable')}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalIntegration.pendingBusinessDate')}</dt>
              <dd>
                {task.scheduleState?.pendingBusinessDate ??
                  t('hospitalIntegration.unavailable')}
              </dd>
            </div>
          </dl>
          {task.scheduleState?.lastError && (
            <div className="hospital-integration__alert" role="alert">
              {t(integrationErrorKey(task.scheduleState.lastError))}
            </div>
          )}
          <p className="hospital-integration__hint">
            {t('hospitalIntegration.mergeHint')}
          </p>
          <details>
            <summary>{t('hospitalIntegration.sourceQuery')}</summary>
            <pre className="hospital-integration__ddl">
              <code>{task.sourceQuery}</code>
            </pre>
          </details>
        </section>
      )}
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
            {sqlMode && (
              <>
                <div>
                  <dt>{t('hospitalIntegration.businessDate')}</dt>
                  <dd>
                    {run.businessDate ?? t('hospitalIntegration.unavailable')}
                  </dd>
                </div>
                <div>
                  <dt>{t('hospitalIntegration.windowStart')}</dt>
                  <dd>
                    {run.windowStart ?? t('hospitalIntegration.unavailable')}
                  </dd>
                </div>
                <div>
                  <dt>{t('hospitalIntegration.windowEnd')}</dt>
                  <dd>
                    {run.windowEnd ?? t('hospitalIntegration.unavailable')}
                  </dd>
                </div>
              </>
            )}
            <div>
              <dt>{t('hospitalCdc.sourceAttempts')}</dt>
              <dd data-testid="integration-source-count">
                {count(run.sourceReceivedCount)}
              </dd>
            </div>
            <div>
              <dt>{t('hospitalCdc.sinkAttempts')}</dt>
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
        {task.mode === 'CDC' && (
          <>
            <h3>{t('hospitalCdc.checkpoints')}</h3>
            <dl className="hospital-integration__facts">
              {(['triggered', 'completed', 'failed', 'restored'] as const).map(
                (key) => (
                  <div key={key}>
                    <dt>{t('hospitalCdc.' + key)}</dt>
                    <dd data-testid={'integration-checkpoint-' + key}>
                      {count(run?.checkpoints?.[key])}
                    </dd>
                  </div>
                )
              )}
              <div>
                <dt>{t('hospitalCdc.latestCompletedAt')}</dt>
                <dd>{timestamp(run?.checkpoints?.latestCompletedAt)}</dd>
              </div>
              <div>
                <dt>{t('hospitalCdc.latestSavepointAt')}</dt>
                <dd>{timestamp(run?.checkpoints?.latestSavepointAt)}</dd>
              </div>
            </dl>
            <p
              className="hospital-integration__hint"
              data-testid="integration-checkpoint-sample">
              {typeof run?.checkpointsCheckedAt === 'number'
                ? t('hospitalCdc.sampleAge', {
                    time: timestamp(run.checkpointsCheckedAt),
                    seconds: Math.max(
                      0,
                      Math.floor((Date.now() - run.checkpointsCheckedAt) / 1000)
                    ).toLocaleString(i18n.language),
                  })
                : t('hospitalCdc.sampleUnknown')}
            </p>
            <div className="hospital-integration__actions hospital-integration__table-tools">
              <Button color="secondary" isDisabled={busy} onClick={onPreflight}>
                {t('hospitalCdc.taskPreflight')}
              </Button>
              <p className="hospital-integration__hint">
                {t(
                  cdcReady ? 'hospitalCdc.ready' : 'hospitalCdc.checkRequired'
                )}
              </p>
            </div>
          </>
        )}
        {run?.errorCode && (
          <div className="hospital-integration__alert" role="alert">
            <p>{t(integrationErrorKey(run.errorCode))}</p>
            <small>{run.errorCode}</small>
          </div>
        )}
        <div className="hospital-integration__actions">
          {!initializing && (
            <Button
              isDisabled={
                busy ||
                active ||
                resumable ||
                !engineAvailable ||
                (task.mode === 'CDC' && !cdcReady)
              }
              isLoading={loading.run}
              onClick={() => onRun(false)}>
              {t(sqlMode ? 'hospitalIntegration.backfill' : 'label.run')}
            </Button>
          )}
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
                : initializing
                ? 'hospitalIntegration.stopCurrentBatch'
                : 'label.stop'
            )}
          </Button>
          {sqlMode ? (
            !initializing && (
              <Button
                color="secondary"
                isDisabled={busy || active || !engineAvailable || !canRetry}
                isLoading={loading.retry}
                onClick={onRetry}>
                {t('hospitalIntegration.retryBusinessDate')}
              </Button>
            )
          ) : (
            <Button
              color="secondary"
              isDisabled={
                busy ||
                active ||
                !engineAvailable ||
                (task.mode === 'CDC' && !cdcReady) ||
                !canResumeIntegrationTask(task.mode, run)
              }
              isLoading={loading.resume}
              onClick={() => onRun(true)}>
              {t('label.resume')}
            </Button>
          )}
        </div>
        {!sqlMode && (
          <p className="hospital-integration__hint">
            {t('hospitalIntegration.recoveryHint')}
          </p>
        )}
        {resumable && (
          <p className="hospital-integration__hint" role="status">
            {t('hospitalIntegration.pausedHint')}
          </p>
        )}
      </section>
      <section aria-labelledby="integration-target-count-title">
        <div className="hospital-integration__section-heading">
          <h2 id="integration-target-count-title">
            {t('hospitalCdc.targetRows')}
          </h2>
          <Button
            color="secondary"
            isDisabled={busy || targetCountLoading}
            isLoading={targetCountLoading}
            onClick={onTargetCount}>
            {t('hospitalCdc.countTarget')}
          </Button>
        </div>
        <p data-testid="integration-target-count" role="status">
          {count(targetCount?.count)}
        </p>
        <p className="hospital-integration__hint">
          {targetCount
            ? t('hospitalCdc.sampleAge', {
                time: timestamp(targetCount.checkedAt),
                seconds: Math.max(
                  0,
                  Math.floor((Date.now() - targetCount.checkedAt) / 1000)
                ).toLocaleString(i18n.language),
              })
            : t('hospitalCdc.manualCountHint')}
        </p>
        {targetCountErrorCode && (
          <p className="hospital-integration__inline-error" role="alert">
            {t(integrationErrorKey(targetCountErrorCode))}
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
          {!sqlMode && task.catalog.sourceFqn && (
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
                  {sqlMode && (
                    <th scope="col">{t('hospitalIntegration.businessDate')}</th>
                  )}
                </tr>
              </thead>
              <tbody>
                {task.runs.map((item) => (
                  <tr key={item.jobId}>
                    <td>{item.jobId}</td>
                    <td>{statusLabel(item)}</td>
                    <td>{timestamp(item.submittedAt)}</td>
                    <td>{timestamp(item.finishedAt)}</td>
                    {sqlMode && (
                      <td>
                        {item.businessDate ??
                          t('hospitalIntegration.unavailable')}
                      </td>
                    )}
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
