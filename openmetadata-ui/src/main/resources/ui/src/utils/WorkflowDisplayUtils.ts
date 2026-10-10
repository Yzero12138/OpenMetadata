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

import { TFunction } from 'i18next';
import { BUILTIN_WORKFLOW_DEFAULTS } from '../constants/BuiltinWorkflow.constants';
import { WorkflowDefinition } from '../generated/governance/workflows/workflowDefinition';

export const getLocalizedWorkflow = (
  workflow: Pick<WorkflowDefinition, 'name' | 'displayName' | 'description'>,
  t: TFunction
): Pick<WorkflowDefinition, 'displayName' | 'description'> => {
  const defaults = Object.prototype.hasOwnProperty.call(
    BUILTIN_WORKFLOW_DEFAULTS,
    workflow.name
  )
    ? BUILTIN_WORKFLOW_DEFAULTS[workflow.name]
    : undefined;

  return {
    displayName:
      defaults && workflow.displayName === defaults.displayName
        ? t(`builtinWorkflow.${workflow.name}.displayName`, {
            defaultValue: defaults.displayName,
          })
        : workflow.displayName,
    description:
      defaults && workflow.description === defaults.description
        ? t(`builtinWorkflow.${workflow.name}.description`, {
            defaultValue: defaults.description,
          })
        : workflow.description,
  };
};
