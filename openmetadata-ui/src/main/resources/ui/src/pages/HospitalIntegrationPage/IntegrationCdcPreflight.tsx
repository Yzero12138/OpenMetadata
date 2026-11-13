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
import {
  AlertCircle,
  CheckCircle,
  HelpCircle,
  MinusCircle,
} from '@untitledui/icons';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IntegrationCdcPreflightProps } from './HospitalIntegrationPage.interface';
import { integrationErrorKey } from './HospitalIntegrationUtils';
import {
  cdcCheckMessageKey,
  cdcPreflightReady,
  prioritizeCdcChecks,
} from './IntegrationCdcUtils';

const CHECK_ICONS = {
  PASS: CheckCircle,
  FAIL: AlertCircle,
  UNKNOWN: HelpCircle,
  NOT_APPLICABLE: MinusCircle,
};

export const IntegrationCdcPreflight = ({
  result,
  loading,
  errorCode,
  sourceOnly = false,
}: IntegrationCdcPreflightProps) => {
  const { t } = useTranslation();
  const summary = useRef<HTMLParagraphElement>(null);
  const pending = useRef(false);
  useEffect(() => {
    if (loading) {
      pending.current = true;

      return;
    }
    if (pending.current) {
      pending.current = false;
      if (result || errorCode) {
        summary.current?.focus({ preventScroll: true });
        summary.current?.scrollIntoView?.({
          block: 'nearest',
          inline: 'nearest',
        });
      }
    }
  }, [loading, result, errorCode]);

  return (
    <div
      aria-busy={loading}
      className="hospital-integration__preflight"
      data-testid="integration-cdc-preflight">
      <p className="hospital-integration__hint">
        {t(sourceOnly ? 'hospitalCdc.sourceOnly' : 'hospitalCdc.taskScope')}
      </p>
      {loading && <p role="status">{t('hospitalIntegration.loading')}</p>}
      {errorCode && (
        <p
          className="hospital-integration__inline-error"
          ref={summary}
          role="alert"
          tabIndex={-1}>
          {t(integrationErrorKey(errorCode))}
        </p>
      )}
      {!loading && !result && !errorCode && (
        <p role="status">{t('hospitalCdc.checkRequired')}</p>
      )}
      {result && (
        <>
          <p
            className="hospital-integration__preflight-summary"
            data-status={cdcPreflightReady(result) ? 'PASS' : 'FAIL'}
            ref={summary}
            role="status"
            tabIndex={-1}>
            {t(
              !result.implemented
                ? 'hospitalCdc.notImplemented'
                : cdcPreflightReady(result)
                ? 'hospitalCdc.ready'
                : 'hospitalCdc.requiredBlocked'
            )}
          </p>
          {(result.databaseName || result.databaseVersion) && (
            <dl className="hospital-integration__facts">
              {result.databaseName && (
                <div>
                  <dt>{t('hospitalCdc.discoveredDatabase')}</dt>
                  <dd>{result.databaseName}</dd>
                </div>
              )}
              {result.databaseVersion && (
                <div>
                  <dt>{t('label.version')}</dt>
                  <dd>{result.databaseVersion}</dd>
                </div>
              )}
            </dl>
          )}
          <ul className="hospital-integration__preflight-checks">
            {prioritizeCdcChecks(result.checks).map((check) => {
              const Icon = CHECK_ICONS[check.status] ?? HelpCircle;

              return (
                <li data-status={check.status} key={check.id}>
                  <Icon
                    aria-hidden="true"
                    className="hospital-integration__preflight-icon"
                  />
                  <div>
                    <strong>{t('hospitalCdc.' + check.status)}</strong>
                    <p>{t(cdcCheckMessageKey(check.code))}</p>
                    <small>
                      {t(
                        check.required
                          ? 'hospitalCdc.required'
                          : 'hospitalCdc.optional'
                      )}
                    </small>
                  </div>
                </li>
              );
            })}
          </ul>
        </>
      )}
      <p className="hospital-integration__hint">
        {t('hospitalCdc.acceptanceHint')}
      </p>
    </div>
  );
};
