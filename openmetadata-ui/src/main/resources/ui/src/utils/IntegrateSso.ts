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
export interface IntegrateTicket {
  code: string;
  challenge: string;
}

export const isIntegrateLaunch = () =>
  new URLSearchParams(window.location.search).get('integrate_connect') === '1';

export const receiveIntegrateTicket = (
  issuer: string,
  signal: AbortSignal
): Promise<IntegrateTicket> => {
  const opener: Window | null = window.opener;
  if (!opener) {
    return Promise.reject(new Error('portal_required'));
  }
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const challenge = btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '');

  return new Promise((resolve, reject) => {
    function cleanup() {
      clearTimeout(deadline);
      window.removeEventListener('message', receive);
      signal.removeEventListener('abort', abort);
    }

    function fail(reason: string) {
      cleanup();
      reject(new Error(reason));
    }

    function abort() {
      fail('portal_cancelled');
    }

    function receive(event: MessageEvent<unknown>) {
      if (event.origin !== issuer || event.source !== opener) {
        return;
      }
      const data = event.data;
      if (
        typeof data !== 'object' ||
        data === null ||
        !('challenge' in data) ||
        data.challenge !== challenge ||
        !('type' in data)
      ) {
        return;
      }
      if (data.type === 'datahub:sso:error') {
        fail('portal_denied');
      } else if (
        data.type === 'datahub:sso:code' &&
        'code' in data &&
        typeof data.code === 'string' &&
        /^[A-Za-z0-9_-]{43}$/.test(data.code)
      ) {
        cleanup();
        resolve({ code: data.code, challenge });
      }
    }
    const deadline = setTimeout(() => fail('portal_timeout'), 90000);
    window.addEventListener('message', receive);
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) {
      abort();

      return;
    }
    try {
      opener.postMessage({ type: 'datahub:sso:challenge', challenge }, issuer);
    } catch {
      fail('portal_required');
    }
  });
};
