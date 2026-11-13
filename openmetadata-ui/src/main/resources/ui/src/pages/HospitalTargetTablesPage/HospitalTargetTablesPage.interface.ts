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
  DorisColumnType,
  DorisDefaultKind,
  DorisTableStructure,
  IntegrationConnection,
  IntegrationMode,
  IntegrationTable,
  IntegrationTargetTableInput,
} from '../../rest/hospitalIntegrationAPI';

export interface DorisColumnDraft {
  rowId: string;
  name: string;
  type: DorisColumnType;
  length: string;
  precision: string;
  scale: string;
  nullable: boolean;
  primaryKey: boolean;
  defaultKind: DorisDefaultKind;
  defaultValue: string;
  comment: string;
}

export interface DorisTableDraft {
  targetSchema: string;
  targetTable: string;
  comment: string;
  buckets: string;
  columns: DorisColumnDraft[];
}

export interface DorisTaskTarget {
  connectionId: string;
  schema: string;
  table: string;
}

export interface DorisSourceSeed {
  connectionId: string;
  table: IntegrationTable;
  mode?: IntegrationMode;
  sourceQuery?: string;
  primaryKey?: string;
}

export interface DorisTableDesignerProps {
  connection: IntegrationConnection;
  existing?: DorisTableStructure;
  source?: DorisSourceSeed;
  onClose: () => void;
  onApplied: (
    structure: DorisTableStructure,
    fieldMappings: IntegrationTargetTableInput['fieldMappings']
  ) => void;
  onBusyChange?: (busy: boolean) => void;
  onDraftChange?: (dirty: boolean) => void;
  navigationBlocked?: boolean;
  onKeepNavigation?: () => void;
  onDiscardNavigation?: () => void;
}

export interface DorisFieldEditorProps {
  columns: DorisColumnDraft[];
  append: boolean;
  disabled: boolean;
  errors: Record<string, string[]>;
  onChange: (columns: DorisColumnDraft[]) => void;
}

export interface DorisTableStructureProps {
  structure: DorisTableStructure;
}
