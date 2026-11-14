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
import { Input } from '@openmetadata/ui-core-components';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IntegrationRuntimeOptions } from '../../rest/hospitalIntegrationAPI';
import { IntegrationExecutionSettingsProps } from './HospitalIntegrationPage.interface';
import {
  parseRuntimeInteger,
  runtimeDefaults,
  RUNTIME_FIELDS,
  validateIntegrationRuntime,
} from './IntegrationCdcUtils';

export const IntegrationExecutionSettings = ({
  value,
  disabled,
  onChange,
  onValidityChange,
  onDraftChange,
}: IntegrationExecutionSettingsProps) => {
  const { t } = useTranslation();
  const [raw, setRaw] = useState<
    Partial<Record<keyof IntegrationRuntimeOptions, string>>
  >({});
  const rawDraft = useRef(raw);
  const invalid = Object.values(raw).some(
    (text) => !parseRuntimeInteger(text ?? '').valid
  );
  const defaults = runtimeDefaults(value.mode);
  const errors = validateIntegrationRuntime(value);
  useEffect(() => {
    onValidityChange?.(invalid);
  }, [invalid, onValidityChange]);
  useEffect(() => () => onDraftChange?.(false), [onDraftChange]);

  return (
    <section aria-labelledby="integration-runtime-title">
      <details>
        <summary id="integration-runtime-title">
          {t('hospitalCdc.runtimeSettings')}
        </summary>
        <p className="hospital-integration__hint">
          {t('hospitalCdc.runtimeHint')}
        </p>
        <div className="hospital-integration__field-grid">
          {(Object.keys(RUNTIME_FIELDS) as (keyof IntegrationRuntimeOptions)[])
            .filter((key) => key !== 'splitSize' || value.mode !== 'SQL_T1')
            .map((key) => (
              <Input
                hint={t('hospitalCdc.integerRange', {
                  min: RUNTIME_FIELDS[key][0],
                  max: RUNTIME_FIELDS[key][1],
                })}
                id={'integration-runtime-' + key}
                isDisabled={disabled}
                isInvalid={
                  raw[key] !== undefined &&
                  !parseRuntimeInteger(raw[key] ?? '').valid
                }
                key={key}
                label={t('hospitalCdc.' + key)}
                placeholder={String(defaults[key])}
                value={raw[key] ?? String(value.runtime?.[key] ?? '')}
                onChange={(text) => {
                  const parsed = parseRuntimeInteger(text);
                  const next = { ...rawDraft.current };
                  if (parsed.valid) {
                    delete next[key];
                  } else {
                    next[key] = text;
                  }
                  rawDraft.current = next;
                  setRaw(next);
                  onDraftChange?.(Object.keys(next).length > 0);
                  if (parsed.valid) {
                    const runtime = { ...value.runtime };
                    if (parsed.value === undefined) {
                      delete runtime[key];
                    } else {
                      runtime[key] = parsed.value;
                    }
                    onChange({
                      ...value,
                      runtime: Object.keys(runtime).length
                        ? runtime
                        : undefined,
                    });
                  }
                }}
              />
            ))}
        </div>
        {(invalid || errors.length > 0) && (
          <p className="hospital-integration__inline-error" role="alert">
            {t(
              invalid || errors.includes('runtimeRangeInvalid')
                ? 'hospitalCdc.runtimeRangeInvalid'
                : 'hospitalCdc.runtimeTimeoutInvalid'
            )}
          </p>
        )}
      </details>
    </section>
  );
};
