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

import axios, { AxiosError } from 'axios';
import { t } from 'i18next';
import { useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Node } from 'reactflow';
import { useWorkflowModeContext } from '../contexts/WorkflowModeContext';
import { NodeSubType } from '../generated/governance/workflows/elements/nodeSubType';
import { UseWorkflowActionsProps } from '../interface/workflow-builder-components.interface';
import {
  createWorkflowDefinition,
  deleteWorkflowByFQN,
  updateWorkflowDefinition,
} from '../rest/workflowDefinitionsAPI';
import {
  buildWorkflowForSave,
  testWorkflow,
} from '../services/WorkflowValidationService';
import { shouldShowForm, shouldUseConfigSidebar } from '../utils/NodeUtils';
import { showErrorToast, showSuccessToast } from '../utils/ToastUtils';
import {
  getWorkflowDefinitionDetailPath,
  getWorkflowDefinitionsListPath,
} from '../utils/WorkflowRouterUtils';
import { useWorkflowEdgeManagement } from './useWorkflowEdgeManagement';

export const useWorkflowActions = ({
  nodes,
  edges,
  workflowDefinition,
  workflowMetadata,
  setWorkflowDefinition,
  setWorkflowMetadata,
  editingEdge,
  setNodes,
  setEdges,
  setSelectedNode,
  setIsConfigSidebarOpen,
  setIsWorkflowFormDrawerOpen,
  setIsConnectionModalOpen,
  setPendingConnection,
  setFocusedConnection,
  setEditingEdge,
  setModalPosition,
  syncWithStore,
}: UseWorkflowActionsProps) => {
  const { isViewMode, enterViewMode } = useWorkflowModeContext();
  const navigate = useNavigate();
  const saveInFlight = useRef(false);

  const edgeManagement = useWorkflowEdgeManagement({
    nodes,
    edges,
    setEdges,
    editingEdge,
    setEditingEdge,
    setIsConnectionModalOpen,
    setPendingConnection,
    setFocusedConnection,
    setModalPosition,
    isViewMode,
  });

  // Node configuration save handler
  const handleNodeConfigSave = useCallback(
    (nodeId: string, config: Record<string, unknown>) => {
      // Check if this is a Data Completeness node being updated
      const isDataCompletenessUpdate =
        config.subType === NodeSubType.DataCompletenessTask &&
        config.qualityBands;

      setNodes((nds) =>
        nds.map((node) => {
          if (node.id === nodeId) {
            return {
              ...node,
              data: {
                ...node.data,
                ...config,
                lastSaved: new Date().toISOString(),
              },
            };
          }

          return node;
        })
      );

      if (isDataCompletenessUpdate) {
        edgeManagement.fixInvalidEdgeConditions(
          nodeId,
          config.qualityBands as unknown[]
        );
      }

      setSelectedNode(null);
      setIsConfigSidebarOpen(false);
    },
    [setNodes, setSelectedNode, setIsConfigSidebarOpen, edgeManagement]
  );

  // Config sidebar close handler
  const handleConfigSidebarClose = useCallback(() => {
    setIsConfigSidebarOpen(false);
    setSelectedNode(null);
  }, [setIsConfigSidebarOpen, setSelectedNode]);

  // Workflow form drawer close handler
  const handleWorkflowFormDrawerClose = useCallback(() => {
    setIsWorkflowFormDrawerOpen(false);
  }, [setIsWorkflowFormDrawerOpen]);

  // Test workflow handler
  const handleTestWorkflow = useCallback(async () => {
    try {
      return await testWorkflow(
        nodes,
        edges,
        workflowDefinition,
        workflowMetadata
      );
    } catch {
      // testWorkflow / buildWorkflowForSave already show a toast on validation failure
    }
  }, [nodes, edges, workflowDefinition, workflowMetadata]);

  // Node click handler
  const handleNodeClick = useCallback(
    (event: React.MouseEvent, node: Node) => {
      event.stopPropagation();
      setSelectedNode(node);

      const nodeType = node.type || '';

      if (!shouldShowForm(nodeType)) {
        setIsConfigSidebarOpen(false);
        setIsWorkflowFormDrawerOpen(false);

        return;
      }

      if (shouldUseConfigSidebar(nodeType)) {
        setIsConfigSidebarOpen(true);
        setIsWorkflowFormDrawerOpen(false);
      } else {
        syncWithStore();
        setIsWorkflowFormDrawerOpen(true);
        setIsConfigSidebarOpen(false);
      }
    },
    [
      setSelectedNode,
      setIsConfigSidebarOpen,
      setIsWorkflowFormDrawerOpen,
      syncWithStore,
    ]
  );

  const handleSaveWorkflow = useCallback(async (): Promise<boolean> => {
    if (saveInFlight.current) {
      return false;
    }
    saveInFlight.current = true;
    try {
      if (!workflowDefinition || !workflowMetadata) {
        showErrorToast(t('message.no-data-available'));

        return false;
      }

      const workflowData = await buildWorkflowForSave(
        nodes,
        edges,
        workflowDefinition,
        workflowMetadata
      );

      const savedWorkflow = workflowMetadata.isNewWorkflow
        ? await createWorkflowDefinition(workflowData)
        : await updateWorkflowDefinition(workflowData);
      showSuccessToast(
        t('message.entity-saved-successfully', { entity: t('label.workflow') })
      );

      setWorkflowDefinition(savedWorkflow);
      setWorkflowMetadata({
        ...workflowMetadata,
        name: savedWorkflow.name,
        displayName: savedWorkflow.displayName || '',
        description: savedWorkflow.description,
        id: savedWorkflow.id,
        isNewWorkflow: false,
      });

      if (workflowMetadata.isNewWorkflow) {
        navigate(
          `${getWorkflowDefinitionDetailPath(savedWorkflow.name)}?mode=view`,
          { replace: true }
        );
      } else {
        enterViewMode();
      }

      return true;
    } catch (error) {
      if (axios.isAxiosError(error)) {
        showErrorToast(error);
      } else if (error instanceof Error) {
        showErrorToast(error.message);
      } else {
        showErrorToast(String(error));
      }

      return false;
    } finally {
      saveInFlight.current = false;
    }
  }, [
    workflowDefinition,
    workflowMetadata,
    nodes,
    edges,
    setWorkflowDefinition,
    setWorkflowMetadata,
    enterViewMode,
    navigate,
  ]);

  const handleWorkflowMetadataUpdate = useCallback(
    (metadata: {
      displayName: string;
      description: string;
      triggerType?: string;
    }) => {
      setWorkflowMetadata({
        ...workflowMetadata,
        name: workflowMetadata?.name || '',
        displayName: metadata.displayName,
        description: metadata.description,
        ...(metadata.triggerType && {
          triggerType: metadata.triggerType,
        }),
      });
    },
    [setWorkflowMetadata, workflowMetadata]
  );

  const performDeleteWorkflow = useCallback(async () => {
    if (!workflowMetadata?.name) {
      showErrorToast(t('label.workflow-name-is-required'));

      return;
    }

    try {
      await deleteWorkflowByFQN(workflowMetadata.name, true);
      showSuccessToast(
        t('message.entity-deleted-successfully', {
          entity: t('label.workflow'),
        })
      );

      navigate(getWorkflowDefinitionsListPath());
    } catch (error) {
      showErrorToast(error as AxiosError);
    }
  }, [workflowMetadata?.name, navigate]);

  return {
    handleNodeConfigSave,
    handleConfigSidebarClose,
    handleWorkflowFormDrawerClose,
    handleTestWorkflow,
    handleNodeClick,
    handleSaveWorkflow,
    performDeleteWorkflow,
    handleConnectionSave: edgeManagement.handleConnectionSave,
    handleConnectionCancel: edgeManagement.handleConnectionCancel,
    handleEdgeClick: edgeManagement.handleEdgeClick,
    handleEdgeDelete: edgeManagement.handleEdgeDelete,
    handleWorkflowMetadataUpdate,
    fixMissingEdgeLabels: edgeManagement.fixMissingEdgeLabels,
    fixInvalidEdgeConditions: edgeManagement.fixInvalidEdgeConditions,
  };
};
