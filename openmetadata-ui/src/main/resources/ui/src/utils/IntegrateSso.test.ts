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
import { receiveIntegrateTicket } from './IntegrateSso';

describe('Integrate portal handshake', () => {
  const issuer = 'https://integrate.hospital.example';
  let postMessage: jest.SpyInstance;

  beforeEach(() => {
    jest.useFakeTimers();
    Object.defineProperty(window, 'opener', {
      configurable: true,
      value: window,
      writable: true,
    });
    postMessage = jest
      .spyOn(window, 'postMessage')
      .mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    jest.useRealTimers();
    window.opener = null;
  });

  const send = (
    data: unknown,
    origin = issuer,
    source: Window | null = window
  ) => {
    window.dispatchEvent(new MessageEvent('message', { data, origin, source }));
  };

  it('accepts only a ticket bound to the exact portal window, origin and challenge', async () => {
    const controller = new AbortController();
    const pending = receiveIntegrateTicket(issuer, controller.signal);
    const challenge = postMessage.mock.calls[0][0].challenge as string;

    expect(challenge).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(postMessage.mock.calls[0][1]).toBe(issuer);

    const code = 'C'.repeat(43);
    send(
      { type: 'datahub:sso:code', challenge, code },
      'https://attacker.example'
    );
    send({ type: 'datahub:sso:code', challenge, code }, issuer, null);
    send({ type: 'datahub:sso:code', challenge: 'wrong', code });
    send({ type: 'datahub:sso:code', challenge, code });

    expect(await pending).toEqual({ code, challenge });
    expect(jest.getTimerCount()).toBe(0);
  });

  it('rejects a portal error without accepting a subsequent replay', async () => {
    const controller = new AbortController();
    const pending = receiveIntegrateTicket(issuer, controller.signal);
    const challenge = postMessage.mock.calls[0][0].challenge as string;
    send({ type: 'datahub:sso:error', challenge });

    await expect(pending).rejects.toThrow('portal_denied');
    expect(jest.getTimerCount()).toBe(0);
  });

  it('cleans up on timeout and cancellation', async () => {
    const controller = new AbortController();
    const pending = receiveIntegrateTicket(issuer, controller.signal);
    const rejected = expect(pending).rejects.toThrow('portal_timeout');
    jest.advanceTimersByTime(90000);
    await rejected;
    const cancelled = receiveIntegrateTicket(issuer, controller.signal);
    const aborted = expect(cancelled).rejects.toThrow('portal_cancelled');
    controller.abort();
    await aborted;

    expect(jest.getTimerCount()).toBe(0);
  });

  it('rejects direct launches with no opener', async () => {
    window.opener = null;

    await expect(
      receiveIntegrateTicket(issuer, new AbortController().signal)
    ).rejects.toThrow('portal_required');
  });
});
