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
import { useContext, useEffect, useRef, useState } from 'react';
import { UNSAFE_NavigationContext } from 'react-router-dom';
import { IntegrationNavigationGuardOptions } from './HospitalIntegrationPage.interface';
import { IntegrationPopStateGuardContext } from './IntegrationNavigationGuardProvider';

const historyIndex = (state: unknown): number | undefined =>
  state &&
  typeof state === 'object' &&
  'idx' in state &&
  typeof state.idx === 'number' &&
  Number.isFinite(state.idx)
    ? state.idx
    : undefined;

const navigationIndex = (): number | undefined => {
  if ('navigation' in window) {
    const navigation: unknown = window.navigation;
    if (
      navigation &&
      typeof navigation === 'object' &&
      'currentEntry' in navigation
    ) {
      const entry: unknown = navigation.currentEntry;
      if (
        entry &&
        typeof entry === 'object' &&
        'index' in entry &&
        typeof entry.index === 'number'
      ) {
        return entry.index;
      }
    }
  }

  return undefined;
};

// BrowserRouter does not expose useBlocker. Keep this guard local to the open
// integration editor, preserving the browser's existing history entries.
export const useIntegrationNavigationGuard = ({
  enabled,
  locked,
}: IntegrationNavigationGuardOptions) => {
  const { navigator } = useContext(UNSAFE_NavigationContext);
  const popStateGuard = useContext(IntegrationPopStateGuardContext);
  const [blocked, setBlocked] = useState(false);
  const pending = useRef<(() => void) | null>(null);
  const bypass = useRef(false);
  const lockedRef = useRef(locked);
  lockedRef.current = locked;

  useEffect(() => {
    if (!enabled) {
      pending.current = null;
      bypass.current = false;
      setBlocked(false);

      return;
    }
    const originalPush = navigator.push;
    const originalReplace = navigator.replace;
    const originalGo = navigator.go;
    const nativeGo = window.history.go.bind(window.history);
    const currentURL = window.location.href;
    const currentState: unknown = window.history.state;
    const currentIndex = historyIndex(currentState);
    const currentNavigationIndex = navigationIndex();
    let restoring = false;
    let probingUnindexedEntry = false;
    let restoreTimer: number | undefined;
    let restoredAction: (() => void) | null = null;

    const attempt = (action: () => void) => {
      if (bypass.current) {
        action();
      } else if (!lockedRef.current) {
        pending.current = action;
        setBlocked(true);
      }
    };
    const guardedPush: typeof navigator.push = (...args) =>
      attempt(() => originalPush.apply(navigator, args));
    const guardedReplace: typeof navigator.replace = (...args) =>
      attempt(() => originalReplace.apply(navigator, args));
    const guardedGo: typeof navigator.go = (...args) =>
      attempt(() => originalGo.apply(navigator, args));
    navigator.push = guardedPush;
    navigator.replace = guardedReplace;
    navigator.go = guardedGo;

    const onPopState = (event: PopStateEvent) => {
      if (bypass.current) {
        return;
      }
      // The provider subscribes before BrowserRouter; a Window-targeted
      // native event otherwise follows registration order, even with capture.
      event.stopImmediatePropagation();
      const nextIndex = historyIndex(event.state);
      const nextNavigationIndex = navigationIndex();
      const delta =
        currentNavigationIndex !== undefined &&
        nextNavigationIndex !== undefined
          ? nextNavigationIndex - currentNavigationIndex
          : currentIndex !== undefined && nextIndex !== undefined
          ? nextIndex - currentIndex
          : undefined;
      const atCurrentEntry = delta === 0 && window.location.href === currentURL;
      if (restoring) {
        if (!atCurrentEntry && window.location.href !== currentURL) {
          // Legacy entries can lack a router idx. A one-step forward probe
          // that did not return to the editor must be reversed, not replaced.
          if (probingUnindexedEntry) {
            window.clearTimeout(restoreTimer);
            probingUnindexedEntry = false;
            restoredAction = () => nativeGo(1);
            nativeGo(-2);
          } else if (delta !== undefined && delta !== 0) {
            nativeGo(-delta);
          }

          return;
        }
        // A duplicate URL may still refer to a different indexed entry.
        if (delta !== undefined && delta !== 0) {
          nativeGo(-delta);

          return;
        }
        window.clearTimeout(restoreTimer);
        restoring = false;
        probingUnindexedEntry = false;
        const action = restoredAction;
        restoredAction = null;
        if (action) {
          attempt(action);
        }

        return;
      }
      if (atCurrentEntry) {
        return;
      }
      restoring = true;
      if (delta !== undefined && delta !== 0) {
        restoredAction = () => nativeGo(delta);
        nativeGo(-delta);
      } else {
        // Without the Navigation API, probe the adjacent entry rather than
        // overwriting an unindexed legacy entry. An end-of-stack forward
        // traversal has no probe event and returns via the timeout branch.
        probingUnindexedEntry = true;
        restoredAction = () => nativeGo(-1);
        nativeGo(1);
        restoreTimer = window.setTimeout(() => {
          if (restoring && probingUnindexedEntry) {
            probingUnindexedEntry = false;
            restoredAction = () => nativeGo(1);
            nativeGo(-1);
          }
        }, 300);
      }
    };
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!bypass.current) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    if (popStateGuard) {
      popStateGuard.current = onPopState;
    } else {
      window.addEventListener('popstate', onPopState, true);
    }
    window.addEventListener('beforeunload', onBeforeUnload);

    return () => {
      window.clearTimeout(restoreTimer);
      if (popStateGuard?.current === onPopState) {
        popStateGuard.current = undefined;
      }
      window.removeEventListener('popstate', onPopState, true);
      window.removeEventListener('beforeunload', onBeforeUnload);
      if (navigator.push === guardedPush) {
        navigator.push = originalPush;
      }
      if (navigator.replace === guardedReplace) {
        navigator.replace = originalReplace;
      }
      if (navigator.go === guardedGo) {
        navigator.go = originalGo;
      }
    };
  }, [enabled, navigator, popStateGuard]);

  const stay = () => {
    pending.current = null;
    setBlocked(false);
  };
  const release = () => {
    pending.current = null;
    bypass.current = true;
    setBlocked(false);
  };
  const proceed = (discard: () => void) => {
    if (lockedRef.current) {
      return;
    }
    const action = pending.current;
    release();
    discard();
    action?.();
  };

  return { blocked, stay, proceed, release };
};
