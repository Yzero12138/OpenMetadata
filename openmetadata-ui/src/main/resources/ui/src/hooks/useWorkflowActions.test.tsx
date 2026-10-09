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

import { act, renderHook } from '@testing-library/react';
import { AxiosError } from 'axios';
import { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { WorkflowModeProvider } from '../contexts/WorkflowModeContext';
import { NodeSubType } from '../generated/governance/workflows/elements/nodeSubType';
import { NodeType } from '../generated/governance/workflows/elements/nodeType';
import { Type } from '../generated/governance/workflows/workflowDefinition';
import { UseWorkflowActionsProps } from '../interface/workflow-builder-components.interface';
import {
  createWorkflowDefinition,
  updateWorkflowDefinition,
} from '../rest/workflowDefinitionsAPI';
import { getWorkflowDraft } from '../utils/WorkflowDraftUtils';
import { useWorkflowActions } from './useWorkflowActions';

jest.mock('../rest/workflowDefinitionsAPI', () => ({
  createWorkflowDefinition: jest.fn(),
  updateWorkflowDefinition: jest.fn(),
}));
jest.mock('../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
  showSuccessToast: jest.fn(),
}));

const wrapper = ({ children }: { children: ReactNode }) => (
  <MemoryRouter>
    <WorkflowModeProvider>{children}</WorkflowModeProvider>
  </MemoryRouter>
);

const createProps = (isNewWorkflow = true): UseWorkflowActionsProps => ({
  nodes: [
    {
      id: 'start',
      type: NodeType.StartEvent,
      position: { x: 0, y: 0 },
      data: {
        subType: NodeSubType.StartEvent,
        userModified: true,
        dataAssets: ['table'],
        eventType: ['Created'],
        triggerType: Type.EventBasedEntity,
      },
    },
    {
      id: 'end',
      type: NodeType.EndEvent,
      position: { x: 300, y: 0 },
      data: { subType: NodeSubType.EndEvent, name: 'end' },
    },
  ],
  edges: [{ id: 'start-end', source: 'start', target: 'end' }],
  workflowDefinition:
    getWorkflowDraft({
      workflowDraft: {
        name: 'qa_review',
        displayName: '数据质量复核',
        description: '合成流程',
      },
    }) ?? null,
  workflowMetadata: {
    name: 'qa_review',
    displayName: '数据质量复核',
    description: '合成流程',
    isNewWorkflow,
  },
  setWorkflowDefinition: jest.fn(),
  setWorkflowMetadata: jest.fn(),
  editingEdge: null,
  setNodes: jest.fn(),
  setEdges: jest.fn(),
  setSelectedNode: jest.fn(),
  setIsConfigSidebarOpen: jest.fn(),
  setIsWorkflowFormDrawerOpen: jest.fn(),
  setIsConnectionModalOpen: jest.fn(),
  setPendingConnection: jest.fn(),
  setFocusedConnection: jest.fn(),
  setEditingEdge: jest.fn(),
  setModalPosition: jest.fn(),
  syncWithStore: jest.fn(),
});

beforeEach(() => {
  jest.clearAllMocks();
  (createWorkflowDefinition as jest.Mock).mockImplementation(
    async (payload) => ({ ...payload, id: 'synthetic-id' })
  );
  (updateWorkflowDefinition as jest.Mock).mockImplementation(
    async (payload) => ({ ...payload, id: 'synthetic-id' })
  );
});

it('creates a configured draft with stable node names, edges and a Chinese display name', async () => {
  const props = createProps();
  const { result } = renderHook(() => useWorkflowActions(props), { wrapper });

  await act(async () =>
    expect(await result.current.handleSaveWorkflow()).toBe(true)
  );

  expect(createWorkflowDefinition).toHaveBeenCalledWith(
    expect.objectContaining({
      name: 'qa_review',
      displayName: '数据质量复核',
      trigger: expect.objectContaining({
        type: Type.EventBasedEntity,
        config: { entityTypes: ['table'], events: ['Created'] },
      }),
      nodes: expect.arrayContaining([
        expect.objectContaining({ name: 'start', type: NodeType.StartEvent }),
        expect.objectContaining({ name: 'end', type: NodeType.EndEvent }),
      ]),
      edges: [{ from: 'start', to: 'end' }],
    })
  );
  expect(updateWorkflowDefinition).not.toHaveBeenCalled();
  expect(props.setWorkflowMetadata).toHaveBeenCalledWith(
    expect.objectContaining({ isNewWorkflow: false })
  );
});

it('updates an existing workflow instead of creating a duplicate', async () => {
  const { result } = renderHook(() => useWorkflowActions(createProps(false)), {
    wrapper,
  });

  await act(async () =>
    expect(await result.current.handleSaveWorkflow()).toBe(true)
  );

  expect(updateWorkflowDefinition).toHaveBeenCalledTimes(1);
  expect(createWorkflowDefinition).not.toHaveBeenCalled();
});

it('preserves the draft when a duplicate name is rejected and never falls back to overwrite', async () => {
  (createWorkflowDefinition as jest.Mock).mockRejectedValue(
    new AxiosError('duplicate', '409')
  );
  const props = createProps();
  const { result } = renderHook(() => useWorkflowActions(props), { wrapper });

  await act(async () =>
    expect(await result.current.handleSaveWorkflow()).toBe(false)
  );

  expect(updateWorkflowDefinition).not.toHaveBeenCalled();
  expect(props.setWorkflowDefinition).not.toHaveBeenCalled();
  expect(props.setWorkflowMetadata).not.toHaveBeenCalled();
});

it('blocks an unconfigured draft before any server write', async () => {
  const props = createProps();
  const { result } = renderHook(
    () => useWorkflowActions({ ...props, nodes: [], edges: [] }),
    { wrapper }
  );

  await act(async () =>
    expect(await result.current.handleSaveWorkflow()).toBe(false)
  );

  expect(createWorkflowDefinition).not.toHaveBeenCalled();
  expect(updateWorkflowDefinition).not.toHaveBeenCalled();
});

it('ignores a second save while the first request is pending', async () => {
  let finishSave: (value: unknown) => void = () => undefined;
  (createWorkflowDefinition as jest.Mock).mockImplementation(
    () =>
      new Promise((resolve) => {
        finishSave = resolve;
      })
  );
  const { result } = renderHook(() => useWorkflowActions(createProps()), {
    wrapper,
  });

  await act(async () => {
    const firstSave = result.current.handleSaveWorkflow();
    const secondSave = result.current.handleSaveWorkflow();
    await Promise.resolve();

    expect(createWorkflowDefinition).toHaveBeenCalledTimes(1);

    finishSave({ name: 'qa_review', id: 'synthetic-id' });

    expect(await firstSave).toBe(true);
    expect(await secondSave).toBe(false);
  });
});
