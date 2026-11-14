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
import { act, renderHook } from '@testing-library/react';
import { useIntegrationScopedRead } from './useIntegrationScopedRead';

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((success, failure) => {
    resolve = success;
    reject = failure;
  });

  return { promise, resolve, reject };
};

describe('authorized manual scoped reads', () => {
  it.each([undefined, 'admin:route:b'])(
    'permanently discards successful readiness and zero count after scope A changes to %s and returns to A',
    async (otherScope) => {
      const read = jest.fn().mockResolvedValue({ ready: true, count: 0 });
      const { result, rerender } = renderHook(
        ({ scope }: { scope?: string }) =>
          useIntegrationScopedRead<{ ready: boolean; count: number }>(scope),
        { initialProps: { scope: 'admin:route:a' as string | undefined } }
      );
      await act(async () => {
        await result.current.execute(read);
      });

      expect(result.current.data).toEqual({ ready: true, count: 0 });

      rerender({ scope: otherScope });

      expect(result.current.data).toBeUndefined();

      rerender({ scope: 'admin:route:a' });

      expect(result.current.data).toBeUndefined();
      expect(result.current.loading).toBe(false);
      expect(read).toHaveBeenCalledTimes(1);
    }
  );

  it('does not revive an old error after administrator loss and regain without another request', async () => {
    const read = jest.fn().mockRejectedValue({
      isAxiosError: true,
      response: { status: 503, data: { code: 'TARGET_COUNT_UNAVAILABLE' } },
    });
    const { result, rerender } = renderHook(
      ({ scope }: { scope?: string }) =>
        useIntegrationScopedRead<number>(scope),
      { initialProps: { scope: 'admin:task' as string | undefined } }
    );
    await act(async () => {
      await result.current.execute(read);
    });

    expect(result.current.errorCode).toBe('TARGET_COUNT_UNAVAILABLE');

    rerender({ scope: undefined });
    rerender({ scope: 'admin:task' });

    expect(result.current.errorCode).toBeUndefined();
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('never fetches on mount and rejects actions without an authorized scope', async () => {
    const read = jest.fn().mockResolvedValue('value');
    const { result } = renderHook(() =>
      useIntegrationScopedRead<string>(undefined)
    );
    await act(async () => {
      expect(await result.current.execute(read)).toBeUndefined();
    });

    expect(read).not.toHaveBeenCalled();
  });

  it('hides stale results synchronously and ignores old responses and finally while a new request is pending', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const read = jest
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(
      ({ scope }) => useIntegrationScopedRead<string>(scope),
      { initialProps: { scope: 'admin:route:a:version:1' } }
    );
    let a!: Promise<string | undefined>;
    act(() => {
      a = result.current.execute(read);
    });
    const firstSignal = read.mock.calls[0][0] as AbortSignal;
    rerender({ scope: 'admin:route:b:version:2' });

    expect(firstSignal.aborted).toBe(true);
    expect(result.current.data).toBeUndefined();

    let b!: Promise<string | undefined>;
    act(() => {
      b = result.current.execute(read);
    });
    await act(async () => {
      first.resolve('old');
      await a;
    });

    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBeUndefined();

    await act(async () => {
      second.resolve('new');
      await b;
    });

    expect(result.current.data).toBe('new');
    expect(result.current.loading).toBe(false);
  });

  it('aborts on close or role loss and clears successful evidence after explicit invalidation', async () => {
    const pending = deferred<string>();
    const read = jest
      .fn()
      .mockResolvedValueOnce('ready')
      .mockReturnValueOnce(pending.promise);
    const { result, rerender } = renderHook(
      ({ scope }: { scope?: string }) =>
        useIntegrationScopedRead<string>(scope),
      { initialProps: { scope: 'admin:detail' as string | undefined } }
    );
    await act(async () => {
      await result.current.execute(read);
    });

    expect(result.current.data).toBe('ready');

    act(() => result.current.invalidate());

    expect(result.current.data).toBeUndefined();

    let request!: Promise<string | undefined>;
    act(() => {
      request = result.current.execute(read);
    });
    rerender({ scope: undefined });

    expect((read.mock.calls[1][0] as AbortSignal).aborted).toBe(true);

    await act(async () => {
      pending.resolve('forbidden');
      await request;
    });

    expect(result.current.data).toBeUndefined();
  });
});
