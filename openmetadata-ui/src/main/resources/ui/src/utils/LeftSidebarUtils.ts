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
import { LeftSidebarItem } from '../components/MyData/LeftSidebar/LeftSidebar.interface';
import { PLACEHOLDER_ROUTE_FQN, ROUTES } from '../constants/constants';
import {
  SIDEBAR_ENTITY_PATH_ALIASES,
  SIDEBAR_LIST,
} from '../constants/LeftSidebar.constants';

interface BreadcrumbLocationState {
  breadcrumbData?: Array<{ url?: string }>;
}

const TEST_CASE_ROUTE_PREFIX = ROUTES.TEST_CASE_DETAILS.replace(
  `/${PLACEHOLDER_ROUTE_FQN}`,
  ''
);
const TEST_SUITE_ROUTE_PREFIX = ROUTES.TEST_SUITES_WITH_FQN.replace(
  `/${PLACEHOLDER_ROUTE_FQN}`,
  ''
);

export const getSidebarPathname = (
  pathname: string,
  locationState: unknown
): string => {
  const originUrl = (locationState as BreadcrumbLocationState | null)
    ?.breadcrumbData?.[0]?.url;

  if (originUrl) {
    return originUrl;
  }

  // `/` renders the landing page in place, so the Home item owns it too.
  if (pathname === ROUTES.HOME) {
    return ROUTES.MY_DATA;
  }

  if (pathname.startsWith(`${TEST_CASE_ROUTE_PREFIX}/`)) {
    return ROUTES.INCIDENT_MANAGER;
  }

  if (pathname.startsWith(`${TEST_SUITE_ROUTE_PREFIX}/`)) {
    return ROUTES.DATA_QUALITY;
  }

  return pathname;
};

export const getSidebarActiveKeys = (
  pathname: string,
  nestedKeys: Record<string, string>,
  aliases: Record<string, string> = SIDEBAR_ENTITY_PATH_ALIASES,
  items: LeftSidebarItem[] = SIDEBAR_LIST
): string[] => {
  const routes = new Map<string, string>();
  const collectRoutes = (sidebarItems: LeftSidebarItem[]) => {
    sidebarItems.forEach((item) => {
      if (item.redirect_url) {
        routes.set(item.key, item.key);
      }
      collectRoutes(item.children ?? []);
    });
  };
  collectRoutes(items);
  Object.keys(nestedKeys).forEach((key) => routes.set(key, key));
  Object.entries(aliases).forEach(([path, key]) => routes.set(path, key));
  const path = pathname.split(/[?#]/)[0];
  const match = [...routes.keys()]
    .filter((route) => path === route || path.startsWith(`${route}/`))
    .sort((a, b) => b.length - a.length)[0];

  return [
    match ? routes.get(match) ?? match : path.split('/').slice(0, 2).join('/'),
  ];
};

export const getSidebarParentKeys = (
  selectedKeys: string[],
  items: LeftSidebarItem[]
): string[] => {
  const parents = new Set<string>();
  const collectParents = (
    sidebarItems: LeftSidebarItem[],
    ancestors: string[]
  ) => {
    sidebarItems.forEach((item) => {
      if (selectedKeys.includes(item.key)) {
        ancestors.forEach((key) => parents.add(key));
      }
      collectParents(item.children ?? [], [...ancestors, item.key]);
    });
  };
  collectParents(items, []);

  return [...parents];
};
