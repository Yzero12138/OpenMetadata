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
import { useCallback, useEffect, useRef, useState } from 'react';
import { getIntegrationFailure } from '../../rest/hospitalIntegrationAPI';

interface ScopedReadState<T> {
  revision: number;
  key?: string;
  data?: T;
  errorCode?: string;
  loading: boolean;
}

export const useIntegrationScopedRead = <T>(key?: string) => {
  const latestKey = useRef(key);
  const revision = useRef(0);
  if (latestKey.current !== key) {
    latestKey.current = key;
    revision.current += 1;
  }
  const currentRevision = revision.current;
  const request = useRef<AbortController>();
  const sequence = useRef(0);
  const [state, setState] = useState<ScopedReadState<T>>({
    revision: currentRevision,
    loading: false,
  });
  useEffect(() => {
    setState((value) =>
      value.revision < currentRevision
        ? { revision: currentRevision, loading: false }
        : value
    );

    return () => {
      request.current?.abort();
      sequence.current += 1;
    };
  }, [currentRevision]);

  const invalidate = useCallback(() => {
    request.current?.abort();
    sequence.current += 1;
    setState({ revision: revision.current, loading: false });
  }, []);

  const cancel = useCallback(() => {
    request.current?.abort();
    sequence.current += 1;
    setState((value) => ({ ...value, loading: false }));
  }, []);

  const execute = useCallback(
    async (
      read: (signal: AbortSignal) => Promise<T>
    ): Promise<T | undefined> => {
      const capturedKey = latestKey.current;
      const capturedRevision = revision.current;
      if (!capturedKey) {
        return undefined;
      }
      request.current?.abort();
      const controller = new AbortController();
      request.current = controller;
      const serial = ++sequence.current;
      const current = () =>
        !controller.signal.aborted &&
        latestKey.current === capturedKey &&
        revision.current === capturedRevision &&
        serial === sequence.current;
      setState({ key: capturedKey, revision: capturedRevision, loading: true });
      try {
        const data = await read(controller.signal);
        if (current()) {
          setState({
            key: capturedKey,
            revision: capturedRevision,
            data,
            loading: false,
          });

          return data;
        }
      } catch (error) {
        if (current()) {
          setState({
            key: capturedKey,
            revision: capturedRevision,
            errorCode: getIntegrationFailure(error).code,
            loading: false,
          });
        }
      } finally {
        if (current()) {
          setState((value) =>
            value.key === capturedKey && value.revision === capturedRevision
              ? { ...value, loading: false }
              : value
          );
        }
      }

      return undefined;
    },
    []
  );

  const accept = useCallback((data: T) => {
    if (latestKey.current) {
      setState({
        key: latestKey.current,
        revision: revision.current,
        data,
        loading: false,
      });
    }
  }, []);

  return {
    data:
      key && state.key === key && state.revision === currentRevision
        ? state.data
        : undefined,
    errorCode:
      key && state.key === key && state.revision === currentRevision
        ? state.errorCode
        : undefined,
    loading: Boolean(
      key &&
        state.key === key &&
        state.revision === currentRevision &&
        state.loading
    ),
    execute,
    invalidate,
    cancel,
    accept,
  };
};
