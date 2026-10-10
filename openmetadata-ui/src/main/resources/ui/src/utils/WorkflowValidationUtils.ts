/*
 *  Copyright 2024 Collate.
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

import { t } from './i18next/LocalUtil';

export const WORKFLOW_NAME_REGEX = /^[a-zA-Z0-9_-]+$/;

export const WORKFLOW_NAME_MIN_LENGTH = 3;
export const WORKFLOW_NAME_MAX_LENGTH = 50;

export const getWorkflowNameError = (value: string): string => {
  if (!value) {
    return t('label.workflow-name-is-required');
  }
  if (!WORKFLOW_NAME_REGEX.test(value)) {
    return t('message.workflow-name-invalid-characters');
  }
  if (value.length < WORKFLOW_NAME_MIN_LENGTH) {
    return t('message.workflow-name-min-length', {
      count: WORKFLOW_NAME_MIN_LENGTH,
    });
  }
  if (value.length > WORKFLOW_NAME_MAX_LENGTH) {
    return t('message.workflow-name-max-length', {
      count: WORKFLOW_NAME_MAX_LENGTH,
    });
  }

  return '';
};

/**
 * Gets validation rules for Ant Design Form
 * @returns Array of validation rules
 */
export const getWorkflowNameValidationRules = () => [
  {
    required: true,
    message: t('label.workflow-name-is-required'),
  },
  {
    pattern: WORKFLOW_NAME_REGEX,
    message: t('message.workflow-name-invalid-characters'),
  },
  {
    min: WORKFLOW_NAME_MIN_LENGTH,
    message: t('message.workflow-name-min-length', {
      count: WORKFLOW_NAME_MIN_LENGTH,
    }),
  },
  {
    max: WORKFLOW_NAME_MAX_LENGTH,
    message: t('message.workflow-name-max-length', {
      count: WORKFLOW_NAME_MAX_LENGTH,
    }),
  },
];
