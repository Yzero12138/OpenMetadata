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
import axios from 'axios';
import { getBasePath } from '../utils/HistoryUtils';
import { IntegrateTicket } from '../utils/IntegrateSso';

export interface IntegrateConfiguration {
  enabled: boolean;
  issuer: string;
  portalUrl: string;
}

interface IntegrateLoginResponse {
  accessToken: string;
  expiryDuration: number;
  tokenType: string;
}

const api = `${getBasePath()}/api/v1/integrate/auth`;

export const getIntegrateConfiguration = async (signal?: AbortSignal) => {
  const { data } = await axios.get<IntegrateConfiguration>(`${api}/config`, {
    signal,
    timeout: 10000,
  });
  if (data.enabled) {
    const issuer = new URL(data.issuer);
    const portal = new URL(data.portalUrl);
    if (
      !['http:', 'https:'].includes(issuer.protocol) ||
      issuer.origin !== data.issuer ||
      portal.origin !== issuer.origin ||
      portal.username ||
      portal.password
    ) {
      throw new Error('invalid_portal_configuration');
    }
  }

  return data;
};

export const exchangeIntegrateTicket = async (
  ticket: IntegrateTicket,
  signal: AbortSignal
) => {
  const { data } = await axios.post<IntegrateLoginResponse>(
    `${api}/exchange`,
    ticket,
    {
      signal,
      timeout: 15000,
      headers: { 'Content-Type': 'application/json' },
    }
  );
  if (!data.accessToken || !Number.isFinite(data.expiryDuration)) {
    throw new Error('invalid_login_response');
  }

  return data;
};
