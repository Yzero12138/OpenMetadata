/*
 *  Copyright 2023 Collate.
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
  AlertTriangle,
  Archive,
  BarChart01,
  Bell01,
  BookOpen01,
  CheckDone01,
  Columns03,
  CpuChip01,
  Cube01,
  Database01,
  Dataflow03,
  File06,
  FileCheck02,
  Folder,
  GitBranch01,
  Globe01,
  Grid01,
  Home02,
  LayersThree01,
  List,
  LogOut01,
  RefreshCw01,
  SearchLg,
  Settings01,
  ShieldTick,
  Tag01,
} from '@untitledui/icons';
import { LeftSidebarItem } from '../components/MyData/LeftSidebar/LeftSidebar.interface';
import { SidebarItem } from '../enums/sidebar.enum';
import { DataInsightTabs } from '../interface/data-insight.interface';
import { createIconWithStroke } from '../utils/IconUtils';
import { ENTITY_PATH, PLACEHOLDER_ROUTE_TAB, ROUTES } from './constants';

type UntitledIconType = (
  ...props: Parameters<typeof Home02>
) => ReturnType<typeof Home02>;
const navigationIcon = (icon: UntitledIconType) =>
  createIconWithStroke(icon, 1.7);

export const SIDEBAR_NESTED_KEYS: Record<string, string> = {
  [ROUTES.HOSPITAL_INTEGRATION_SOURCES]: ROUTES.HOSPITAL_INTEGRATION,
  [ROUTES.HOSPITAL_INTEGRATION_TASKS]: ROUTES.HOSPITAL_INTEGRATION,
  [ROUTES.DATA_MARKETPLACE]: ROUTES.DATA_MARKETPLACE_SECTION,
  [ROUTES.EXPLORE]: ROUTES.DATA_MARKETPLACE_SECTION,
  [ROUTES.DOMAIN]: ROUTES.DATA_MARKETPLACE_SECTION,
  [ROUTES.DATA_PRODUCT]: ROUTES.DATA_MARKETPLACE_SECTION,
  [ROUTES.PLATFORM_LINEAGE]: ROUTES.DATA_MARKETPLACE_SECTION,
  [ROUTES.DATA_QUALITY]: ROUTES.OBSERVABILITY,
  [ROUTES.INCIDENT_MANAGER]: ROUTES.OBSERVABILITY,
  [ROUTES.TEST_LIBRARY]: ROUTES.OBSERVABILITY,
  [ROUTES.OBSERVABILITY_ALERTS]: ROUTES.OBSERVABILITY,
  [ROUTES.GLOSSARY]: 'governance',
  [ROUTES.TAGS]: 'governance',
  [ROUTES.METRICS]: 'governance',
  [ROUTES.WORKFLOWS]: 'governance',
  [ROUTES.DATA_INSIGHT]: 'governance',
  [ROUTES.ONTOLOGY_EXPLORER]: 'governance',
  [ROUTES.COLUMN_BULK_OPERATIONS]: 'governance',
  [ROUTES.CONTEXT_CENTER_OVERVIEW]: ROUTES.CONTEXT_CENTER,
  [ROUTES.CONTEXT_CENTER_ARTICLES]: ROUTES.CONTEXT_CENTER,
  [ROUTES.CONTEXT_CENTER_DOCUMENTS]: ROUTES.CONTEXT_CENTER,
  [ROUTES.CONTEXT_CENTER_MEMORIES]: ROUTES.CONTEXT_CENTER,
  [ROUTES.CONTEXT_CENTER_INTEGRATIONS]: ROUTES.CONTEXT_CENTER,
  [ROUTES.CONTEXT_CENTER_ARCHIVE]: ROUTES.CONTEXT_CENTER,
};

export const SIDEBAR_ENTITY_PATH_ALIASES: Record<string, string> = {
  ...Object.fromEntries(
    [
      ENTITY_PATH.tables,
      ENTITY_PATH.topics,
      ENTITY_PATH.dashboards,
      ENTITY_PATH.pipelines,
      ENTITY_PATH.mlmodels,
      ENTITY_PATH.containers,
      ENTITY_PATH.searchIndexes,
      ENTITY_PATH.storedProcedures,
      ENTITY_PATH.databases,
      ENTITY_PATH.databaseSchemas,
      ENTITY_PATH.dashboardDataModels,
      ENTITY_PATH.apiCollections,
      ENTITY_PATH.apiEndpoints,
      ENTITY_PATH.dataAssets,
      ENTITY_PATH.query,
      ENTITY_PATH.charts,
      ENTITY_PATH.directories,
      ENTITY_PATH.files,
      ENTITY_PATH.spreadsheets,
      ENTITY_PATH.worksheets,
      ENTITY_PATH.column,
      ENTITY_PATH.aiApplications,
      ENTITY_PATH.llmModels,
      ENTITY_PATH.mcpServers,
      ENTITY_PATH.agentExecutions,
      ENTITY_PATH.mcpExecutions,
      ENTITY_PATH.promptTemplates,
    ].map((path) => [`/${path}`, ROUTES.EXPLORE])
  ),
  [ROUTES.HOSPITAL_INTEGRATION]: ROUTES.HOSPITAL_INTEGRATION_TASKS,
  [`/${ENTITY_PATH.metrics}`]: ROUTES.METRICS,
  [`/${ENTITY_PATH.tags}`]: ROUTES.TAGS,
  '/glossary-term': ROUTES.GLOSSARY,
  '/observability/alert': ROUTES.OBSERVABILITY_ALERTS,
  '/test-case': ROUTES.INCIDENT_MANAGER,
  '/test-suites': ROUTES.DATA_QUALITY,
};

export const SIDEBAR_LIST: LeftSidebarItem[] = [
  {
    key: ROUTES.MY_DATA,
    title: 'hospitalNavigation.workbench',
    redirect_url: ROUTES.MY_DATA,
    icon: navigationIcon(Home02),
    dataTestId: `app-bar-item-${SidebarItem.HOME}`,
  },
  {
    key: ROUTES.HOSPITAL_INTEGRATION,
    title: 'hospitalNavigation.integration',
    icon: navigationIcon(RefreshCw01),
    dataTestId: `app-bar-item-${SidebarItem.HOSPITAL_INTEGRATION}`,
    children: [
      {
        key: ROUTES.HOSPITAL_INTEGRATION_SOURCES,
        title: 'hospitalNavigation.sources',
        redirect_url: ROUTES.HOSPITAL_INTEGRATION_SOURCES,
        icon: navigationIcon(Database01),
        dataTestId: 'app-bar-item-hospital-integration-sources',
      },
      {
        key: ROUTES.HOSPITAL_INTEGRATION_TASKS,
        title: 'hospitalNavigation.tasks',
        redirect_url: ROUTES.HOSPITAL_INTEGRATION_TASKS,
        icon: navigationIcon(List),
        dataTestId: 'app-bar-item-hospital-integration-tasks',
      },
    ],
  },
  {
    key: ROUTES.DATA_MARKETPLACE_SECTION,
    title: 'hospitalNavigation.assets',
    icon: navigationIcon(LayersThree01),
    dataTestId: SidebarItem.DATA_MARKETPLACE_SECTION,
    children: [
      {
        key: ROUTES.DATA_MARKETPLACE,
        title: 'hospitalNavigation.assetOverview',
        redirect_url: ROUTES.DATA_MARKETPLACE,
        icon: navigationIcon(Grid01),
        dataTestId: `app-bar-item-${SidebarItem.DATA_MARKETPLACE}`,
      },
      {
        key: ROUTES.EXPLORE,
        title: 'hospitalNavigation.catalog',
        redirect_url: ROUTES.EXPLORE,
        icon: navigationIcon(SearchLg),
        dataTestId: `app-bar-item-${SidebarItem.EXPLORE}`,
      },
      {
        key: ROUTES.DOMAIN,
        title: 'hospitalNavigation.domains',
        redirect_url: ROUTES.DOMAIN,
        icon: navigationIcon(Globe01),
        dataTestId: `app-bar-item-${SidebarItem.DOMAIN}`,
      },
      {
        key: ROUTES.DATA_PRODUCT,
        title: 'hospitalNavigation.products',
        redirect_url: ROUTES.DATA_PRODUCT,
        icon: navigationIcon(Cube01),
        dataTestId: `app-bar-item-${SidebarItem.DATA_PRODUCT}`,
      },
      {
        key: ROUTES.PLATFORM_LINEAGE,
        title: 'hospitalNavigation.lineage',
        redirect_url: ROUTES.PLATFORM_LINEAGE,
        icon: navigationIcon(GitBranch01),
        dataTestId: `app-bar-item-${SidebarItem.LINEAGE}`,
      },
    ],
  },
  {
    key: ROUTES.OBSERVABILITY,
    title: 'hospitalNavigation.quality',
    icon: navigationIcon(CheckDone01),
    dataTestId: SidebarItem.OBSERVABILITY,
    children: [
      {
        key: ROUTES.DATA_QUALITY,
        title: 'hospitalNavigation.checks',
        redirect_url: ROUTES.DATA_QUALITY,
        icon: navigationIcon(CheckDone01),
        dataTestId: `app-bar-item-${SidebarItem.DATA_QUALITY}`,
      },
      {
        key: ROUTES.INCIDENT_MANAGER,
        title: 'hospitalNavigation.incidents',
        redirect_url: ROUTES.INCIDENT_MANAGER,
        icon: navigationIcon(AlertTriangle),
        dataTestId: `app-bar-item-${SidebarItem.INCIDENT_MANAGER}`,
      },
      {
        key: ROUTES.TEST_LIBRARY,
        title: 'hospitalNavigation.templates',
        redirect_url: ROUTES.TEST_LIBRARY,
        icon: navigationIcon(FileCheck02),
        dataTestId: 'app-bar-item-test-library',
      },
      {
        key: ROUTES.OBSERVABILITY_ALERTS,
        title: 'hospitalNavigation.alerts',
        redirect_url: ROUTES.OBSERVABILITY_ALERTS,
        icon: navigationIcon(Bell01),
        dataTestId: `app-bar-item-${SidebarItem.OBSERVABILITY_ALERT}`,
      },
    ],
  },
  {
    key: 'governance',
    title: 'hospitalNavigation.governance',
    icon: navigationIcon(ShieldTick),
    dataTestId: SidebarItem.GOVERNANCE,
    children: [
      {
        key: ROUTES.GLOSSARY,
        title: 'hospitalNavigation.glossary',
        redirect_url: ROUTES.GLOSSARY,
        icon: navigationIcon(BookOpen01),
        dataTestId: `app-bar-item-${SidebarItem.GLOSSARY}`,
      },
      {
        key: ROUTES.TAGS,
        title: 'hospitalNavigation.tags',
        redirect_url: ROUTES.TAGS,
        icon: navigationIcon(Tag01),
        dataTestId: `app-bar-item-${SidebarItem.TAGS}`,
      },
      {
        key: ROUTES.METRICS,
        title: 'hospitalNavigation.metrics',
        redirect_url: ROUTES.METRICS,
        icon: navigationIcon(BarChart01),
        dataTestId: `app-bar-item-${SidebarItem.METRICS}`,
      },
      {
        key: ROUTES.WORKFLOWS,
        title: 'hospitalNavigation.workflows',
        redirect_url: ROUTES.WORKFLOWS,
        icon: navigationIcon(Dataflow03),
        dataTestId: `app-bar-item-${SidebarItem.WORKFLOWS}`,
      },
      {
        key: ROUTES.DATA_INSIGHT,
        title: 'hospitalNavigation.insights',
        redirect_url: ROUTES.DATA_INSIGHT_WITH_TAB.replace(
          PLACEHOLDER_ROUTE_TAB,
          DataInsightTabs.DATA_ASSETS
        ),
        icon: navigationIcon(BarChart01),
        dataTestId: `app-bar-item-${SidebarItem.DATA_INSIGHT}`,
      },
      {
        key: ROUTES.ONTOLOGY_EXPLORER,
        title: 'label.ontology-explorer',
        redirect_url: ROUTES.ONTOLOGY_EXPLORER,
        icon: navigationIcon(GitBranch01),
        dataTestId: `app-bar-item-${SidebarItem.ONTOLOGY_EXPLORER}`,
        isHiddenByDefault: true,
      },
      {
        key: ROUTES.COLUMN_BULK_OPERATIONS,
        title: 'label.column-bulk-operations',
        redirect_url: ROUTES.COLUMN_BULK_OPERATIONS,
        icon: navigationIcon(Columns03),
        dataTestId: `app-bar-item-${SidebarItem.COLUMN_BULK_OPERATIONS}`,
        isBeta: true,
        isHiddenByDefault: true,
      },
    ],
  },
  {
    key: ROUTES.CONTEXT_CENTER,
    title: 'hospitalNavigation.knowledge',
    icon: navigationIcon(BookOpen01),
    dataTestId: SidebarItem.CONTEXT_CENTER,
    children: [
      {
        key: ROUTES.CONTEXT_CENTER_OVERVIEW,
        title: 'hospitalNavigation.knowledgeOverview',
        redirect_url: ROUTES.CONTEXT_CENTER_OVERVIEW,
        icon: navigationIcon(Grid01),
        dataTestId: `app-bar-item-${SidebarItem.OVERVIEW}`,
      },
      {
        key: ROUTES.CONTEXT_CENTER_ARTICLES,
        title: 'hospitalNavigation.articles',
        redirect_url: ROUTES.CONTEXT_CENTER_ARTICLES,
        icon: navigationIcon(File06),
        dataTestId: `app-bar-item-${SidebarItem.ARTICLES}`,
      },
      {
        key: ROUTES.CONTEXT_CENTER_DOCUMENTS,
        title: 'hospitalNavigation.documents',
        redirect_url: ROUTES.CONTEXT_CENTER_DOCUMENTS,
        icon: navigationIcon(Folder),
        dataTestId: `app-bar-item-${SidebarItem.DOCUMENTS}`,
      },
      {
        key: ROUTES.CONTEXT_CENTER_ARCHIVE,
        title: 'label.archive',
        redirect_url: ROUTES.CONTEXT_CENTER_ARCHIVE,
        icon: navigationIcon(Archive),
        dataTestId: 'app-bar-item-context-center-archive',
      },
      {
        key: ROUTES.CONTEXT_CENTER_MEMORIES,
        title: 'label.memory-plural',
        redirect_url: ROUTES.CONTEXT_CENTER_MEMORIES,
        icon: navigationIcon(CpuChip01),
        dataTestId: `app-bar-item-${SidebarItem.MEMORIES}`,
        isHiddenByDefault: true,
      },
    ],
  },
];

export const SETTING_ITEM: LeftSidebarItem = {
  key: ROUTES.SETTINGS,
  title: 'hospitalNavigation.settings',
  redirect_url: ROUTES.SETTINGS,
  icon: navigationIcon(Settings01),
  dataTestId: `app-bar-item-${SidebarItem.SETTINGS}`,
};

export const LOGOUT_ITEM: LeftSidebarItem = {
  key: SidebarItem.LOGOUT,
  title: 'label.logout',
  icon: navigationIcon(LogOut01),
  dataTestId: `app-bar-item-${SidebarItem.LOGOUT}`,
};
