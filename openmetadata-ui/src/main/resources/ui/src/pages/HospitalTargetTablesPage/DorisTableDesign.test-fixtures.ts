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
  DorisTableStructure,
  IntegrationConnection,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';

export const dorisConnection: IntegrationConnection = {
  id: 'doris-target',
  version: 1,
  name: 'ods',
  displayName: 'Doris ODS',
  role: 'TARGET',
  databaseType: 'Doris',
  databaseVersion: '4.0.8',
  host: 'doris.invalid',
  port: 9030,
  dorisHttpPort: 8030,
  database: 'ods_validation',
  schemas: ['ods_validation'],
  username: 'writer',
  tlsMode: 'DISABLED',
  enabled: true,
  managed: false,
  synthetic: true,
  passwordSet: true,
  supportedModes: ['FULL', 'SQL_T1'],
};

export const dorisTable: IntegrationTable = {
  schema: 'ods_validation',
  name: 'visits',
  columns: [
    { name: 'id', dataType: 'BIGINT', primaryKey: true, nullable: false },
  ],
};

export const dorisStructure: DorisTableStructure = {
  targetConnectionId: dorisConnection.id,
  targetSchema: 'ods_validation',
  targetTable: 'visits',
  comment: '',
  model: 'UNIQUE',
  buckets: 1,
  replicationNum: 1,
  structureHash: 'a'.repeat(64),
  columns: [
    {
      name: 'id',
      type: 'BIGINT',
      nullable: false,
      primaryKey: true,
      defaultKind: 'NONE',
      comment: '',
    },
  ],
  table: dorisTable,
  ddl: 'CREATE TABLE ods_validation.visits (id BIGINT NOT NULL) UNIQUE KEY (id)',
};
