/*
 * Copyright 2026 Collate.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
import { TreeDataNode } from 'antd';
import { LeftSidebarItem } from '../../components/MyData/LeftSidebar/LeftSidebar.interface';
import { AppPlugin } from '../../components/Settings/Applications/plugins/AppPlugin';
import { ROUTES } from '../../constants/constants';
import { SIDEBAR_LIST } from '../../constants/LeftSidebar.constants';
import { NavigationItem } from '../../generated/system/ui/uiCustomization';
import {
  filterHiddenNavigationItems,
  getHiddenKeysFromNavigationItems,
  getSidebarItemsWithPlugins,
  getTreeDataForNavigationItems,
} from './CustomizeNavigation';

const SOURCES = '/hospital/integration/sources';
const TASKS = '/hospital/integration/tasks';
const nav = (
  id: string,
  isHidden = false,
  children?: NavigationItem[]
): NavigationItem => ({ id, title: id, pageId: id, isHidden, children });
const collectIds = (items: LeftSidebarItem[]): string[] =>
  items.flatMap((item) => [item.key, ...collectIds(item.children ?? [])]);
const treeToNavigation = (
  items: TreeDataNode[],
  hiddenKeys: string[]
): NavigationItem[] =>
  items.map((item) => ({
    id: String(item.key),
    pageId: String(item.key),
    title: String(item.title),
    isHidden: hiddenKeys.includes(String(item.key)),
    children: item.children
      ? treeToNavigation(item.children, hiddenKeys)
      : undefined,
  }));

describe('hospital workspace navigation', () => {
  it('provides the approved six groups and independently addressable integration children', () => {
    expect(SIDEBAR_LIST.map((item) => item.key)).toEqual([
      ROUTES.MY_DATA,
      ROUTES.HOSPITAL_INTEGRATION,
      ROUTES.DATA_MARKETPLACE_SECTION,
      ROUTES.OBSERVABILITY,
      'governance',
      ROUTES.CONTEXT_CENTER,
    ]);
    expect(
      SIDEBAR_LIST.find(
        (item) => item.key === ROUTES.HOSPITAL_INTEGRATION
      )?.children?.map((item) => [item.key, item.redirect_url])
    ).toEqual([
      [SOURCES, SOURCES],
      [TASKS, TASKS],
    ]);
  });

  it('migrates the hidden legacy integration leaf in both the menu and editor', () => {
    const saved = [nav(ROUTES.MY_DATA), nav(ROUTES.HOSPITAL_INTEGRATION, true)];
    const rendered = filterHiddenNavigationItems(saved);
    const edited = getTreeDataForNavigationItems(saved);

    expect(collectIds(rendered)).not.toContain(ROUTES.HOSPITAL_INTEGRATION);
    expect(
      edited
        .find((item) => item.key === ROUTES.HOSPITAL_INTEGRATION)
        ?.children?.map((item) => item.key)
    ).toEqual([SOURCES, TASKS]);
    expect(getHiddenKeysFromNavigationItems(saved)).toEqual(
      expect.arrayContaining([ROUTES.HOSPITAL_INTEGRATION, SOURCES, TASKS])
    );
  });

  it('retains legacy leaf order and explicit hidden preferences after regrouping', () => {
    const saved = [
      nav(ROUTES.PLATFORM_LINEAGE),
      nav(ROUTES.EXPLORE, true),
      nav(ROUTES.DATA_MARKETPLACE_SECTION, false, [
        nav(ROUTES.DATA_PRODUCT),
        nav(ROUTES.DOMAIN),
        nav(ROUTES.DATA_MARKETPLACE),
      ]),
      nav(ROUTES.DATA_INSIGHT),
      nav('governance', false, [nav(ROUTES.TAGS, true), nav(ROUTES.GLOSSARY)]),
    ];
    const edited = getTreeDataForNavigationItems(saved);
    const rendered = filterHiddenNavigationItems(saved);

    expect(
      edited
        .find((item) => item.key === ROUTES.DATA_MARKETPLACE_SECTION)
        ?.children?.map((item) => item.key)
    ).toEqual([
      ROUTES.PLATFORM_LINEAGE,
      ROUTES.EXPLORE,
      ROUTES.DATA_PRODUCT,
      ROUTES.DOMAIN,
      ROUTES.DATA_MARKETPLACE,
    ]);
    expect(edited.map((item) => item.key)).not.toEqual(
      expect.arrayContaining([
        ROUTES.EXPLORE,
        ROUTES.PLATFORM_LINEAGE,
        ROUTES.DATA_INSIGHT,
      ])
    );
    expect(collectIds(rendered)).not.toContain(ROUTES.EXPLORE);
    expect(collectIds(rendered)).not.toContain(ROUTES.TAGS);
    expect(collectIds(rendered)).toContain(ROUTES.DATA_INSIGHT);
  });

  it('carries hidden ancestor state to moved leaves without hiding unrelated visible leaves', () => {
    const saved = [
      nav(ROUTES.EXPLORE),
      nav(ROUTES.DATA_MARKETPLACE_SECTION, true, [
        nav(ROUTES.DOMAIN),
        nav(ROUTES.DATA_PRODUCT),
      ]),
    ];
    const ids = collectIds(filterHiddenNavigationItems(saved));

    expect(ids).toContain(ROUTES.EXPLORE);
    expect(ids).not.toContain(ROUTES.DOMAIN);
    expect(ids).not.toContain(ROUTES.DATA_PRODUCT);
  });

  it('keeps a saved plugin root at the user-selected position and avoids duplicate IDs', () => {
    const plugins: AppPlugin[] = [
      {
        name: 'custom-tools',
        isInstalled: true,
        getSidebarActions: () => [
          {
            key: 'custom-tools',
            title: 'custom-tools',
            icon: SIDEBAR_LIST[0].icon,
            dataTestId: 'custom-tools',
            index: 99,
          },
        ],
      },
    ];
    const saved = [
      nav('custom-tools'),
      nav(ROUTES.MY_DATA),
      nav(ROUTES.EXPLORE),
      nav(ROUTES.EXPLORE),
      nav(ROUTES.HOSPITAL_INTEGRATION),
    ];
    const rendered = filterHiddenNavigationItems(saved, plugins);
    const ids = collectIds(rendered);

    expect(rendered[0].key).toBe('custom-tools');
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('does not duplicate a native child ID when a plugin supplies the same key', () => {
    const items = getSidebarItemsWithPlugins([
      {
        name: 'catalog-shortcut',
        isInstalled: true,
        getSidebarActions: () => [
          {
            key: ROUTES.EXPLORE,
            title: 'catalog-shortcut',
            dataTestId: 'catalog-shortcut',
            icon: SIDEBAR_LIST[0].icon,
            index: 0,
          },
        ],
      },
    ]);
    const ids = collectIds(items);

    expect(new Set(ids).size).toBe(ids.length);
  });

  it('is idempotent when migrated navigation is saved and loaded again', () => {
    const saved = [
      nav(ROUTES.MY_DATA),
      nav(ROUTES.HOSPITAL_INTEGRATION, true),
      nav(ROUTES.EXPLORE, true),
      nav(ROUTES.PLATFORM_LINEAGE),
      nav('governance', false, [nav(ROUTES.GLOSSARY), nav(ROUTES.TAGS, true)]),
    ];
    const once = treeToNavigation(
      getTreeDataForNavigationItems(saved),
      getHiddenKeysFromNavigationItems(saved)
    );
    const twice = treeToNavigation(
      getTreeDataForNavigationItems(once),
      getHiddenKeysFromNavigationItems(once)
    );

    expect(twice).toEqual(once);
    expect(filterHiddenNavigationItems(twice)).toEqual(
      filterHiddenNavigationItems(saved)
    );
  });

  it('shows advanced entries only when enabled in saved navigation', () => {
    const advanced = [
      ROUTES.ONTOLOGY_EXPLORER,
      ROUTES.COLUMN_BULK_OPERATIONS,
      ROUTES.CONTEXT_CENTER_MEMORIES,
    ];
    const defaultIds = collectIds(filterHiddenNavigationItems(null));

    advanced.forEach((id) => expect(defaultIds).not.toContain(id));
    const saved = [
      nav('governance', false, [
        nav(ROUTES.ONTOLOGY_EXPLORER),
        nav(ROUTES.COLUMN_BULK_OPERATIONS),
      ]),
      nav(ROUTES.CONTEXT_CENTER, false, [nav(ROUTES.CONTEXT_CENTER_MEMORIES)]),
    ];

    expect(collectIds(filterHiddenNavigationItems(saved))).toEqual(
      expect.arrayContaining(advanced)
    );
  });
});
