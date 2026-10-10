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

export const BUILTIN_WORKFLOW_DEFAULTS: Readonly<
  Record<string, Readonly<{ displayName: string; description: string }>>
> = {
  AIAssetApprovalWorkflow: {
    displayName: 'AI Asset Approval Workflow',
    description:
      'When an AI asset (AI Application, LLM Model, or MCP Server) is submitted for review, route it to the Risk Council reviewers for approval, mirroring the Glossary Term approval process.',
  },
  AutoPilotWorkflow: {
    displayName: 'AutoPilot Workflow',
    description:
      'Whenever a new Service is created, the relevant pipelines will automatically be created, deployed and run.',
  },
  CustomTaskWorkflow: {
    displayName: 'Custom Task Workflow',
    description: 'Default workflow-driven lifecycle for custom tasks.',
  },
  DataQualityReviewTaskWorkflow: {
    displayName: 'Data Quality Review Task Workflow',
    description:
      'Default workflow-driven lifecycle for data quality review tasks.',
  },
  DescriptionUpdateTaskWorkflow: {
    displayName: 'Description Update Task Workflow',
    description:
      'Default workflow-driven lifecycle for description update tasks.',
  },
  DomainUpdateTaskWorkflow: {
    displayName: 'Domain Update Task Workflow',
    description: 'Default workflow-driven lifecycle for domain update tasks.',
  },
  GenericIncidentTaskWorkflow: {
    displayName: 'Generic Incident Task Workflow',
    description:
      'Default workflow-driven lifecycle for incident-style tasks with configurable stages from Open through Closed.',
  },
  GenericReviewTaskWorkflow: {
    displayName: 'Generic Review Task Workflow',
    description:
      'Default workflow-driven lifecycle for task forms that require a single review stage with approve or reject transitions.',
  },
  GlossaryTermApprovalWorkflow: {
    displayName: 'Glossary Approval Workflow',
    description:
      'When a Glossary Term is Created or Updated, this Workflow will be triggered for the Term to be Approved.',
  },
  IncidentLifecycleWorkflow: {
    displayName: 'Incident Lifecycle Workflow',
    description:
      'Default workflow definition for incident lifecycle management.',
  },
  IncidentResolutionTaskWorkflow: {
    displayName: 'Incident Resolution Task Workflow',
    description:
      'Default workflow-driven lifecycle for incident resolution tasks. Stages mirror the incident manager flow: New, Acknowledged, Assigned.',
  },
  OwnershipUpdateTaskWorkflow: {
    displayName: 'Ownership Update Task Workflow',
    description:
      'Default workflow-driven lifecycle for ownership update tasks.',
  },
  PipelineReviewTaskWorkflow: {
    displayName: 'Pipeline Review Task Workflow',
    description: 'Default workflow-driven lifecycle for pipeline review tasks.',
  },
  RecognizerFeedbackReviewWorkflow: {
    displayName: 'Recognizer Feedback Review Workflow',
    description:
      'When feedback is submitted on auto-applied tags, check if tag has reviewers and either auto-apply or create approval task',
  },
  RequestApprovalTaskWorkflow: {
    displayName: 'Request Approval Task Workflow',
    description:
      'Default workflow-driven lifecycle for approval request tasks.',
  },
  SuggestionTaskWorkflow: {
    displayName: 'Suggestion Task Workflow',
    description: 'Default workflow-driven lifecycle for suggestion tasks.',
  },
  TagUpdateTaskWorkflow: {
    displayName: 'Tag Update Task Workflow',
    description: 'Default workflow-driven lifecycle for tag update tasks.',
  },
  TestCaseResolutionTaskWorkflow: {
    displayName: 'Test Case Resolution Task Workflow',
    description:
      'Default workflow-driven lifecycle for test case resolution tasks. Stages mirror the incident manager flow: New, Acknowledged, Assigned.',
  },
  TierUpdateTaskWorkflow: {
    displayName: 'Tier Update Task Workflow',
    description: 'Default workflow-driven lifecycle for tier update tasks.',
  },
};
