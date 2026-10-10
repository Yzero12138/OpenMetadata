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
  Type,
  WorkflowDefinition,
} from '../generated/governance/workflows/workflowDefinition';
import {
  WORKFLOW_NAME_MAX_LENGTH,
  WORKFLOW_NAME_MIN_LENGTH,
  WORKFLOW_NAME_REGEX,
} from './WorkflowValidationUtils';

export const getWorkflowDraft = (
  state: unknown
): WorkflowDefinition | undefined => {
  if (!state || typeof state !== 'object' || !('workflowDraft' in state)) {
    return undefined;
  }
  const draft = state.workflowDraft;
  if (
    !draft ||
    typeof draft !== 'object' ||
    !('name' in draft) ||
    !('displayName' in draft) ||
    !('description' in draft)
  ) {
    return undefined;
  }
  const { name, displayName, description } = draft;
  if (
    typeof name !== 'string' ||
    typeof displayName !== 'string' ||
    typeof description !== 'string' ||
    !WORKFLOW_NAME_REGEX.test(name) ||
    name.length < WORKFLOW_NAME_MIN_LENGTH ||
    name.length > WORKFLOW_NAME_MAX_LENGTH
  ) {
    return undefined;
  }

  return {
    name,
    displayName: displayName.trim() || name,
    description,
    nodes: [],
    edges: [],
    trigger: { type: Type.EventBasedEntity, config: {}, output: [] },
  };
};
