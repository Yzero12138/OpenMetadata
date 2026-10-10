/*
 *  Copyright 2025 Collate.
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
import { ROUTES } from '../constants/constants';
import {
  SIDEBAR_LIST,
  SIDEBAR_NESTED_KEYS,
} from '../constants/LeftSidebar.constants';
import {
  getSidebarActiveKeys,
  getSidebarParentKeys,
  getSidebarPathname,
} from './LeftSidebarUtils';

describe('getSidebarActiveKeys', () => {
  it.each([
    '/table/his.patient/versions/1.0',
    '/dashboard/reporting.overview',
    '/pipeline/ingestion.daily',
    '/apiEndpoint/clinical.results',
    '/aiApplication/research.assistant',
  ])(
    'selects the catalog and asset parent for direct entity route %s',
    (path) => {
      const keys = getSidebarActiveKeys(path, SIDEBAR_NESTED_KEYS);

      expect(keys).toEqual([ROUTES.EXPLORE]);
      expect(getSidebarParentKeys(keys, SIDEBAR_LIST)).toEqual([
        ROUTES.DATA_MARKETPLACE_SECTION,
      ]);
    }
  );

  it('selects distinct integration children for lists and direct task details', () => {
    expect(
      getSidebarActiveKeys('/hospital/integration/sources', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/hospital/integration/sources']);
    expect(
      getSidebarActiveKeys(
        '/hospital/integration/tasks/task-17',
        SIDEBAR_NESTED_KEYS
      )
    ).toEqual(['/hospital/integration/tasks']);
  });

  it('uses the longest registered route with a segment boundary', () => {
    const nested = {
      '/plugin': 'plugin-parent',
      '/plugin/reports': 'plugin-parent',
      '/plugin/reports/recent': 'plugin-parent',
    };

    expect(
      getSidebarActiveKeys('/plugin/reports/recent/report-1', nested)
    ).toEqual(['/plugin/reports/recent']);
    expect(getSidebarActiveKeys('/plugin/reporting', nested)).toEqual([
      '/plugin',
    ]);
  });

  it('selects tasks for the legacy integration entry', () => {
    expect(
      getSidebarActiveKeys(ROUTES.HOSPITAL_INTEGRATION, SIDEBAR_NESTED_KEYS)
    ).toEqual([ROUTES.HOSPITAL_INTEGRATION_TASKS]);
  });

  it('should return the two-segment path for a list page', () => {
    expect(getSidebarActiveKeys('/tags', SIDEBAR_NESTED_KEYS)).toEqual([
      '/tags',
    ]);
    expect(getSidebarActiveKeys('/metrics', SIDEBAR_NESTED_KEYS)).toEqual([
      '/metrics',
    ]);
  });

  it('should keep a list page active on its own detail route', () => {
    expect(
      getSidebarActiveKeys('/glossary/Business.Term', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/glossary']);
    expect(getSidebarActiveKeys('/domain/Sales', SIDEBAR_NESTED_KEYS)).toEqual([
      '/domain',
    ]);
    expect(
      getSidebarActiveKeys('/dataProduct/Orders', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/dataProduct']);
  });

  it('should alias singular entity detail routes back to their sidebar key', () => {
    // Classification: sidebar key is `/tags`, entity route is `/tag/:fqn`
    expect(
      getSidebarActiveKeys('/tag/Certification.Silver', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/tags']);

    // Metrics: sidebar key is `/metrics`, entity route is `/metric/:fqn`
    expect(
      getSidebarActiveKeys('/metric/sales.revenue', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/metrics']);
  });

  it('should alias glossary-term version pages back to Glossary', () => {
    expect(
      getSidebarActiveKeys(
        '/glossary-term/1234/versions/0.2',
        SIDEBAR_NESTED_KEYS
      )
    ).toEqual(['/glossary']);
  });

  it('should keep aliased entity version pages active', () => {
    expect(
      getSidebarActiveKeys(
        '/metric/sales.revenue/versions/0.1',
        SIDEBAR_NESTED_KEYS
      )
    ).toEqual(['/metrics']);
  });

  it('should alias the singular observability alert deep path back to Alerts', () => {
    expect(
      getSidebarActiveKeys(
        '/observability/alert/OpenMetadata_alert_uuOIJhshj/configuration',
        SIDEBAR_NESTED_KEYS
      )
    ).toEqual(['/observability/alerts']);
  });

  it('should keep Incident Manager active for a direct test case detail route', () => {
    expect(
      getSidebarActiveKeys(
        '/test-case/service.database.schema.table.test/results',
        SIDEBAR_NESTED_KEYS
      )
    ).toEqual(['/incident-manager']);
  });

  it('should keep the plural observability alerts list/add pages active', () => {
    expect(
      getSidebarActiveKeys('/observability/alerts', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/observability/alerts']);
    expect(
      getSidebarActiveKeys('/observability/alerts/add', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/observability/alerts']);
  });

  it('should return a registered deep path as-is', () => {
    expect(
      getSidebarActiveKeys('/context-center/overview', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/context-center/overview']);
    expect(
      getSidebarActiveKeys('/governance/ontology', SIDEBAR_NESTED_KEYS)
    ).toEqual(['/governance/ontology']);
  });

  it('should honour an extended nested-keys map (e.g. Collate)', () => {
    const CUSTOM_DEEP_PATH = '/data-marketplace/data-access-requests';
    const nestedKeys = {
      ...SIDEBAR_NESTED_KEYS,
      [CUSTOM_DEEP_PATH]: CUSTOM_DEEP_PATH,
    };

    expect(getSidebarActiveKeys(CUSTOM_DEEP_PATH, nestedKeys)).toEqual([
      CUSTOM_DEEP_PATH,
    ]);
  });
});

describe('getSidebarPathname', () => {
  it('should map the in-place landing route `/` to the Home sidebar key', () => {
    expect(getSidebarPathname(ROUTES.HOME, undefined)).toEqual(ROUTES.MY_DATA);
  });

  it('should leave `/my-data` untouched', () => {
    expect(getSidebarPathname(ROUTES.MY_DATA, undefined)).toEqual(
      ROUTES.MY_DATA
    );
  });

  it('should prefer the breadcrumb origin url over the landing route', () => {
    expect(
      getSidebarPathname(ROUTES.HOME, {
        breadcrumbData: [{ url: ROUTES.EXPLORE }],
      })
    ).toEqual(ROUTES.EXPLORE);
  });
});
