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
import { useEffect, useMemo, useState } from 'react';
import {
  getIntegrationFailure,
  getIntegrationTable,
  getIntegrationTableOptions,
  IntegrationTable,
} from '../../rest/hospitalIntegrationAPI';

export const useIntegrationTables = (
  connectionId: string,
  schema: string,
  name: string,
  active: boolean,
  reload: number
) => {
  const [options, setOptions] = useState<IntegrationTable[]>([]);
  const [detail, setDetail] = useState<IntegrationTable>();
  const [loading, setLoading] = useState({ options: false, detail: false });
  const [errors, setErrors] = useState<{ options?: string; detail?: string }>(
    {}
  );
  useEffect(() => {
    setOptions([]);
    setErrors((current) => ({ ...current, options: undefined }));
    if (!active || !connectionId) {
      setLoading((current) => ({ ...current, options: false }));

      return;
    }
    const controller = new AbortController();
    setLoading((current) => ({ ...current, options: true }));
    const load = async () => {
      try {
        const result = await getIntegrationTableOptions(
          connectionId,
          controller.signal
        );
        if (!controller.signal.aborted) {
          setOptions(result);
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setErrors((current) => ({
            ...current,
            options: getIntegrationFailure(error).code,
          }));
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading((current) => ({ ...current, options: false }));
        }
      }
    };
    void load();

    return () => controller.abort();
  }, [active, connectionId, reload]);
  useEffect(() => {
    setDetail(undefined);
    setErrors((current) => ({ ...current, detail: undefined }));
    if (!active || !connectionId || !schema || !name) {
      setLoading((current) => ({ ...current, detail: false }));

      return;
    }
    const controller = new AbortController();
    setLoading((current) => ({ ...current, detail: true }));
    const load = async () => {
      try {
        const result = await getIntegrationTable(
          connectionId,
          schema,
          name,
          controller.signal
        );
        if (
          !controller.signal.aborted &&
          result.schema === schema &&
          result.name === name
        ) {
          setDetail(result);
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setErrors((current) => ({
            ...current,
            detail: getIntegrationFailure(error).code,
          }));
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading((current) => ({ ...current, detail: false }));
        }
      }
    };
    void load();

    return () => controller.abort();
  }, [active, connectionId, name, reload, schema]);
  const tables = useMemo(
    () =>
      options.map((table) =>
        detail?.schema === table.schema && detail.name === table.name
          ? detail
          : table
      ),
    [detail, options]
  );

  return {
    tables,
    detail,
    loading: loading.options || loading.detail,
    errorCode: errors.options ?? errors.detail,
  };
};
