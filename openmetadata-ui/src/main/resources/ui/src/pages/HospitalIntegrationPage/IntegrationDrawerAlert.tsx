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
import { ReactNode, useEffect, useRef } from 'react';

type IntegrationDrawerAlertProps = {
  children: ReactNode;
  focusKey: string | string[];
};

export const IntegrationDrawerAlert = ({
  children,
  focusKey,
}: IntegrationDrawerAlertProps) => {
  const summary = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = summary.current;
    if (!element) {
      return;
    }
    element.focus({ preventScroll: true });
    element.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [focusKey]);

  return (
    <div
      className="hospital-integration__alert"
      ref={summary}
      role="alert"
      tabIndex={-1}>
      {children}
    </div>
  );
};
