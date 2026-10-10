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
  createContext,
  MutableRefObject,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { IntegrationNavigationGuardProviderProps } from './HospitalIntegrationPage.interface';

type PopStateGuard = (event: PopStateEvent) => void;

export const IntegrationPopStateGuardContext = createContext<
  MutableRefObject<PopStateGuard | undefined> | undefined
>(undefined);

// Mount the router only after this listener exists. Native Window popstate
// events run in registration order; capture alone cannot precede the router.
export const IntegrationNavigationGuardProvider = ({
  children,
}: IntegrationNavigationGuardProviderProps) => {
  const guard = useRef<PopStateGuard>();
  const [ready, setReady] = useState(false);
  useLayoutEffect(() => {
    const delegate = (event: PopStateEvent) => guard.current?.(event);
    window.addEventListener('popstate', delegate, true);
    setReady(true);

    return () => {
      window.removeEventListener('popstate', delegate, true);
      guard.current = undefined;
    };
  }, []);

  return (
    <IntegrationPopStateGuardContext.Provider value={guard}>
      {ready ? children : null}
    </IntegrationPopStateGuardContext.Provider>
  );
};
