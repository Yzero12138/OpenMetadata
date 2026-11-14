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
import { useCallback, useEffect, useMemo } from 'react';
import {
  getIntegrationTable,
  getIntegrationTableOptions,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';
import { useIntegrationScopedRead } from './useIntegrationScopedRead';

export const useIntegrationTables = (
  connectionId: string,
  schema: string,
  name: string,
  active: boolean,
  reload: number,
  scope?: string
) => {
  const optionsKey =
    active && connectionId
      ? JSON.stringify([scope, connectionId, reload])
      : undefined;
  const detailKey =
    optionsKey && schema && name
      ? JSON.stringify([optionsKey, schema, name])
      : undefined;
  const optionsRead = useIntegrationScopedRead<IntegrationTable[]>(optionsKey);
  const detailRead = useIntegrationScopedRead<IntegrationTable>(detailKey);
  const { execute: readOptions } = optionsRead;
  const { execute: readDetail } = detailRead;
  useEffect(() => {
    if (optionsKey) {
      void readOptions((signal) =>
        getIntegrationTableOptions(connectionId, signal)
      );
    }
  }, [connectionId, optionsKey, readOptions]);
  useEffect(() => {
    if (detailKey) {
      void readDetail(async (signal) => {
        const table = await getIntegrationTable(
          connectionId,
          schema,
          name,
          signal
        );
        if (table.schema !== schema || table.name !== name) {
          throw new Error('REQUEST_FAILED');
        }

        return table;
      });
    }
  }, [connectionId, schema, name, detailKey, readDetail]);
  const options = optionsRead.data;
  const detail = detailRead.data;
  const tables = useMemo(
    () =>
      (options ?? []).map((table) =>
        detail?.schema === table.schema && detail.name === table.name
          ? detail
          : table
      ),
    [options, detail]
  );
  const acceptTable = useCallback(
    (table: IntegrationTable) => {
      optionsRead.accept([
        ...(options ?? []).filter(
          (item) => item.schema !== table.schema || item.name !== table.name
        ),
        table,
      ]);
      detailRead.accept(table);
    },
    [options, optionsRead.accept, detailRead.accept]
  );

  return {
    acceptTable,
    tables,
    detail,
    loading:
      Boolean(optionsKey && !optionsRead.data && !optionsRead.errorCode) ||
      Boolean(detailKey && !detailRead.data && !detailRead.errorCode) ||
      optionsRead.loading ||
      detailRead.loading,
    errorCode: optionsRead.errorCode ?? detailRead.errorCode,
  };
};
