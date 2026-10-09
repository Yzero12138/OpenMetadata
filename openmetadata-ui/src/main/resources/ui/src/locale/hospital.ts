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
export const hospitalEnglish = {
  title: 'Hospital data governance',
  purpose: 'Find data. Align definitions. Resolve quality issues.',
  portalTitle: 'Sign in through the hospital portal',
  portalBody:
    'Use your employee number and password in Integrate, then open Data Governance from the application portal.',
  portalAction: 'Open Integrate',
  accountHint: 'Your hospital employee account controls access.',
  catalog: 'Data catalog',
  catalogBody: 'Find HIS and LIS assets, fields and their owners.',
  definitions: 'Business definitions',
  definitionsBody: 'Use shared clinical and operational terminology.',
  quality: 'Data quality',
  qualityBody: 'Review rules and follow quality incidents through resolution.',
  connecting: 'Connecting to the hospital portal…',
  connectingBody: 'Verifying your current Integrate session.',
  configError:
    'The hospital portal connection is not available. Contact your platform administrator.',
  denied:
    'Your session expired or you cannot access this application. Open it again from Integrate.',
  timeout:
    'The connection timed out. Open Data Governance again from the Integrate portal.',
  unavailable:
    'The hospital identity service is temporarily unavailable. Try again from the portal.',
  direct: 'Open this application from Integrate to complete sign-in.',
  retry: 'Retry connection',
  secure: 'Hospital unified access',
  powered: 'Powered by OpenMetadata',
  welcome: 'Data governance workbench',
  welcomeBody:
    'Discover hospital assets and keep data definitions and quality in view.',
  searchLabel: 'Search the data catalog',
  searchPlaceholder: 'Search a table, field or business term',
  search: 'Search',
  tables: 'Catalog tables',
  glossaries: 'Business glossaries',
  tests: 'Quality rules',
  sources: 'Database services',
  recent: 'Catalog assets',
  asset: 'Asset',
  source: 'Source',
  owner: 'Owner',
  unassigned: 'Unassigned',
  empty: 'No hospital assets yet',
  emptyBody: 'Connect a test data source and ingest its metadata to begin.',
  failed: 'Unable to load this data. Check your access or retry.',
  reload: 'Reload',
  viewCatalog: 'Browse the catalog',
  actions: 'Governance actions',
  glossaryAction: 'Maintain hospital definitions',
  qualityAction: 'Review quality rules and incidents',
  servicesAction: 'Manage data sources',
  permissionsHint:
    'Available actions follow your OpenMetadata governance permissions.',
  notAvailable: 'Unavailable',
};

export const hospitalChinese: typeof hospitalEnglish = {
  title: '医院数据治理',
  purpose: '查找数据，统一口径，跟进质量问题。',
  portalTitle: '通过院内门户登录',
  portalBody:
    '使用工号和密码登录 Integrate，在应用门户点击“数据治理”即可进入。',
  portalAction: '进入 Integrate',
  accountHint: '访问权限由您的院内工号账号控制。',
  catalog: '数据目录',
  catalogBody: '查找 HIS、LIS 数据资产、字段及负责人。',
  definitions: '业务术语',
  definitionsBody: '统一临床与运营数据的业务定义。',
  quality: '数据质量',
  qualityBody: '检查质量规则，跟进问题处理与整改。',
  connecting: '正在连接院内门户…',
  connectingBody: '正在核验您在 Integrate 中的登录状态。',
  configError: '院内门户连接尚未就绪，请联系平台管理员。',
  denied: '登录已失效，或您没有此应用的访问权限。请从 Integrate 重新进入。',
  timeout: '统一登录连接超时，请在 Integrate 应用门户重新打开数据治理。',
  unavailable: '院内身份服务暂时无法连接，请稍后从门户重试。',
  direct: '请从 Integrate 应用门户打开此应用，以完成统一登录。',
  retry: '重新连接',
  secure: '院内统一访问',
  powered: '基于 OpenMetadata',
  welcome: '数据治理工作台',
  welcomeBody: '发现医院数据资产，维护业务口径，跟进数据质量。',
  searchLabel: '查找数据资产',
  searchPlaceholder: '搜索数据表、字段或业务术语',
  search: '搜索',
  tables: '数据表',
  glossaries: '业务词汇表',
  tests: '质量规则',
  sources: '数据库服务',
  recent: '目录中的数据资产',
  asset: '数据资产',
  source: '所属数据源',
  owner: '负责人',
  unassigned: '待指定',
  empty: '尚未接入医院数据资产',
  emptyBody: '先连接测试数据源并采集元数据，即可开始治理。',
  failed: '暂时无法获取数据，请检查访问权限或重试。',
  reload: '重新加载',
  viewCatalog: '浏览数据目录',
  actions: '治理操作',
  glossaryAction: '维护医院业务术语',
  qualityAction: '查看质量规则与整改问题',
  servicesAction: '管理数据源',
  permissionsHint: '可用操作遵循您在 OpenMetadata 中的数据治理权限。',
  notAvailable: '不可用',
};
