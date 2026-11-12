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
import { useEffect, useRef, useState } from 'react';
import {
  getIntegrationFailure,
  getIntegrationQuerySchema,
  IntegrationQuerySchemaInput,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';
import { SQL_QUERY_MAX_BYTES } from './HospitalIntegrationUtils';

export const useIntegrationQuerySchema = (
  input: IntegrationQuerySchemaInput,
  active: boolean
) => {
  const key = JSON.stringify([active, input]);
  const latestKey = useRef(key);
  latestKey.current = key;
  const controller = useRef<AbortController>();
  const pending = useRef(false);
  const [result, setResult] = useState<{
    key: string;
    table: IntegrationTable;
  }>();
  const [error, setError] = useState<{ key: string; code: string }>();
  const [loadingKey, setLoadingKey] = useState<string>();

  useEffect(() => {
    controller.current?.abort();
    pending.current = false;

    return () => controller.current?.abort();
  }, [key]);

  const read = async () => {
    if (
      !active ||
      pending.current ||
      !input.connectionId ||
      !input.schema ||
      !input.query.trim()
    ) {
      return;
    }
    if (new Blob([input.query]).size > SQL_QUERY_MAX_BYTES) {
      setError({ key, code: 'INVALID_SOURCE_QUERY' });

      return;
    }
    const request = new AbortController();
    controller.current?.abort();
    controller.current = request;
    pending.current = true;
    setLoadingKey(key);
    setError(undefined);
    try {
      const table = await getIntegrationQuerySchema(input, request.signal);
      if (!request.signal.aborted && latestKey.current === key) {
        setResult({
          key,
          table: { ...table, schema: input.schema, name: 'sql_result' },
        });
      }
    } catch (failure) {
      if (!request.signal.aborted && latestKey.current === key) {
        setError({ key, code: getIntegrationFailure(failure).code });
      }
    } finally {
      if (controller.current === request) {
        pending.current = false;
        setLoadingKey(undefined);
      }
    }
  };

  return {
    table: result?.key === key ? result.table : undefined,
    errorCode: error?.key === key ? error.code : undefined,
    loading: loadingKey === key,
    read,
  };
};
