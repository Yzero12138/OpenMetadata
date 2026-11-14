/*
 * Copyright 2026 Collate.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
import { LeftSidebarItem } from '../../components/MyData/LeftSidebar/LeftSidebar.interface';
import { ROUTES } from '../../constants/constants';
import { NavigationItem } from '../../generated/system/ui/uiCustomization';

interface SavedNavigationEntry {
  item: NavigationItem;
  hidden: boolean;
  position: number;
}

const WORKSPACE_ROOTS = new Set([
  ROUTES.MY_DATA,
  ROUTES.HOSPITAL_INTEGRATION,
  ROUTES.DATA_MARKETPLACE_SECTION,
  ROUTES.OBSERVABILITY,
  'governance',
  ROUTES.CONTEXT_CENTER,
]);

export const migrateHospitalNavigation = (
  navigation: NavigationItem[] | null | undefined,
  sidebar: LeftSidebarItem[]
): NavigationItem[] | null | undefined => {
  const integration = sidebar.find(
    (item) => item.key === ROUTES.HOSPITAL_INTEGRATION
  );
  if (
    !integration?.children?.some(
      (item) => item.key === ROUTES.HOSPITAL_INTEGRATION_TASKS
    )
  ) {
    return navigation;
  }

  const hasSavedNavigation = Boolean(navigation?.length);
  const saved = new Map<string, SavedNavigationEntry>();
  let position = 0;
  const collectSaved = (items: NavigationItem[], ancestorHidden = false) => {
    items.forEach((item) => {
      const hidden = ancestorHidden || Boolean(item.isHidden);
      if (!saved.has(item.id)) {
        saved.set(item.id, { item, hidden, position: position++ });
      }
      collectSaved(item.children ?? [], hidden);
    });
  };
  collectSaved(navigation ?? []);

  const owners = new Map<string, string>();
  const collectOwners = (item: LeftSidebarItem, root: string) => {
    owners.set(item.key, root);
    item.children?.forEach((child) => collectOwners(child, root));
  };
  sidebar.forEach((item) => collectOwners(item, item.key));

  const rootOrder: string[] = [];
  saved.forEach((_entry, id) => {
    const owner = owners.get(id);
    if (owner && !rootOrder.includes(owner)) {
      rootOrder.push(owner);
    }
  });
  if (hasSavedNavigation && !rootOrder.includes(ROUTES.HOSPITAL_INTEGRATION)) {
    const homeIndex = rootOrder.indexOf(ROUTES.MY_DATA);
    rootOrder.splice(
      homeIndex < 0 ? Math.min(1, rootOrder.length) : homeIndex + 1,
      0,
      ROUTES.HOSPITAL_INTEGRATION
    );
  }
  sidebar.forEach((item) => {
    if (!rootOrder.includes(item.key)) {
      rootOrder.push(item.key);
    }
  });

  const buildItem = (item: LeftSidebarItem, root: string): NavigationItem => {
    const entry = saved.get(item.key);
    const plugin = !WORKSPACE_ROOTS.has(root);
    const orderedChildren = item.children
      ? [...item.children].sort(
          (a, b) =>
            (saved.get(a.key)?.position ?? Infinity) -
            (saved.get(b.key)?.position ?? Infinity)
        )
      : undefined;
    const children = orderedChildren?.map((child) => buildItem(child, root));
    const integrationChild = root === ROUTES.HOSPITAL_INTEGRATION;
    const hidden = children?.length
      ? children.every((child) => child.isHidden)
      : entry?.hidden ??
        (integrationChild
          ? saved.get(ROUTES.HOSPITAL_INTEGRATION)?.hidden ?? false
          : Boolean(item.isHiddenByDefault) || (hasSavedNavigation && !plugin));

    return {
      id: item.key,
      title: plugin ? entry?.item.title ?? item.title : item.title,
      pageId: entry?.item.pageId ?? item.key,
      isHidden: hidden,
      ...(children ? { children } : {}),
    };
  };

  const roots = new Map(sidebar.map((item) => [item.key, item]));

  return rootOrder.flatMap((key) => {
    const item = roots.get(key);

    return item ? [buildItem(item, item.key)] : [];
  });
};
