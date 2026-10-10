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
  IntegrationConnection,
  IntegrationConnectionInput,
  IntegrationTable,
  IntegrationTask,
  IntegrationTaskInput,
} from '../../rest/hospitalIntegrationAPI';

export interface HospitalIntegrationWorkspaceProps {
  isAdmin: boolean;
}

export interface IntegrationTaskFormProps {
  value: IntegrationTaskInput;
  connections: IntegrationConnection[];
  tablesLoading: boolean;
  onReloadTables: () => void;
  sourceTables: IntegrationTable[];
  targetTables: IntegrationTable[];
  errors: string[];
  disabled: boolean;
  validating: boolean;
  saving: boolean;
  tablesUnavailable: boolean;
  onChange: (value: IntegrationTaskInput) => void;
  onValidate: () => void;
  onSave: () => void;
  onCancel: () => void;
}

export interface IntegrationTaskDetailProps {
  task: IntegrationTask;
  sourceConnectionName?: string;
  targetConnectionName?: string;
  busy: boolean;
  uncertain: boolean;
  engineAvailable: boolean;
  loading: Record<string, boolean>;
  onEdit: () => void;
  onRefresh: () => void;
  onRun: (resume: boolean) => void;
  onStop: () => void;
  onCatalog: () => void;
}

export interface IntegrationConnectionsProps {
  connections: IntegrationConnection[];
  loading: boolean;
  createRequest: number;
  onChange: (connections: IntegrationConnection[]) => void;
  onBusyChange: (busy: boolean) => void;
}

export interface IntegrationConnectionFormProps {
  value: IntegrationConnectionInput;
  schemasText: string;
  editing: boolean;
  disabled: boolean;
  errors: string[];
  onChange: (value: IntegrationConnectionInput) => void;
  onSchemasChange: (value: string) => void;
  onSave: () => void;
}
