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
import Icon from '@ant-design/icons/lib/components/Icon';
import { Button, Layout, Menu, MenuProps, Typography } from 'antd';
import Modal from 'antd/lib/modal/Modal';
import classNames from 'classnames';
import { noop } from 'lodash';
import { MenuInfo } from 'rc-menu/lib/interface';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ReactComponent as DataPlatformMark } from '../../../assets/svg/data-platform.svg';
import {
  LOGOUT_ITEM,
  SETTING_ITEM,
} from '../../../constants/LeftSidebar.constants';
import { SidebarItem } from '../../../enums/sidebar.enum';
import { useCurrentUserPreferences } from '../../../hooks/currentUserStore/useCurrentUserStore';
import { useApplicationStore } from '../../../hooks/useApplicationStore';
import useCustomLocation from '../../../hooks/useCustomLocation/useCustomLocation';
import { useSidebarItems } from '../../../hooks/useSidebarItems';
import leftSidebarClassBase from '../../../utils/LeftSidebarClassBase';
import {
  getSidebarActiveKeys,
  getSidebarParentKeys,
  getSidebarPathname,
} from '../../../utils/LeftSidebarUtils';
import { useAuthProvider } from '../../Auth/AuthProviders/AuthProvider';
import BrandImage from '../../common/BrandImage/BrandImage';
import './left-sidebar.less';
import LeftSidebarItem from './LeftSidebarItem.component';
const { Sider } = Layout;

const LeftSidebar = () => {
  const location = useCustomLocation();
  const { t } = useTranslation();
  const { onLogoutHandler } = useAuthProvider();
  const { applicationConfig } = useApplicationStore();
  const [isConfirmLogoutModalOpen, setIsConfirmLogoutModalOpen] =
    useState(false);
  const {
    preferences: { isSidebarCollapsed },
  } = useCurrentUserPreferences();

  const { i18n } = useTranslation();
  const isDirectionRTL = useMemo(() => i18n.dir() === 'rtl', [i18n]);
  const [openKeys, setOpenKeys] = useState<string[]>([]);

  const sideBarItems = useSidebarItems();

  const selectedKeys = useMemo(
    () =>
      getSidebarActiveKeys(
        getSidebarPathname(location.pathname, location.state),
        leftSidebarClassBase.getSidebarNestedKeys(),
        undefined,
        sideBarItems
      ),
    [location.pathname, location.state, sideBarItems]
  );
  const activeParentKey = getSidebarParentKeys(selectedKeys, sideBarItems)[0];

  useEffect(() => {
    setOpenKeys(activeParentKey ? [activeParentKey] : []);
  }, [location.pathname, activeParentKey]);

  const handleLogoutClick = useCallback(() => {
    setIsConfirmLogoutModalOpen(true);
  }, []);

  const hideConfirmLogoutModal = () => {
    setIsConfirmLogoutModalOpen(false);
  };

  const LOWER_SIDEBAR_TOP_SIDEBAR_MENU_ITEMS: MenuProps['items'] = useMemo(
    () =>
      [SETTING_ITEM, LOGOUT_ITEM].map((item) => ({
        key: item.key,
        'aria-label': t(item.title),
        icon: <Icon aria-hidden="true" component={item.icon} />,
        onClick: item.key === SidebarItem.LOGOUT ? handleLogoutClick : noop,
        label: <LeftSidebarItem data={item} />,
      })),
    [handleLogoutClick, t]
  );

  const menuItems = useMemo(() => {
    return sideBarItems.map((item) => ({
      key: item.key,
      'aria-label': t(item.title),
      icon: (
        <Icon
          aria-hidden={!isSidebarCollapsed || !item.children?.length}
          aria-label={
            isSidebarCollapsed && item.children?.length
              ? t(item.title)
              : undefined
          }
          component={item.icon}
        />
      ),
      label: <LeftSidebarItem data={item} />,
      popupClassName: 'hospital-workspace__submenu',
      'data-testid': `side-bar-${item.dataTestId}`,
      children: item.children?.map((child) => ({
        key: child.key,
        'aria-label': t(child.title),
        icon: <Icon aria-hidden="true" component={child.icon} />,
        label: <LeftSidebarItem data={child} />,
        'data-testid': `side-bar-${child.dataTestId}`,
      })),
    }));
  }, [sideBarItems, isSidebarCollapsed, t]);

  const handleMenuClick: MenuProps['onClick'] = useCallback(
    (info: MenuInfo) => {
      // keyPath is [childKey, ...ancestorKeys]; keep the active item's submenu
      // open (empty for a top-level item, which collapses any open submenu).
      setOpenKeys(info.keyPath.slice(1));
    },
    []
  );

  return (
    <Sider
      collapsible
      className={classNames({
        'hospital-workspace__sidebar': true,
        'left-sidebar-col-rtl': isDirectionRTL,
        'sidebar-open': !isSidebarCollapsed,
      })}
      collapsed={isSidebarCollapsed}
      collapsedWidth={72}
      data-testid="left-sidebar"
      trigger={null}
      width={240}>
      <div className="logo-container">
        <Link
          aria-label={t('hospitalNavigation.brand')}
          className="hospital-workspace__brand"
          id="openmetadata_logo"
          to="/">
          {applicationConfig?.customLogoConfig?.customLogoUrlPath ? (
            <BrandImage
              className="vertical-middle h-full"
              dataTestId="image"
              height={32}
              isMonoGram={isSidebarCollapsed}
              width="auto"
            />
          ) : (
            <>
              <span
                className="hospital-workspace__brand-mark"
                data-testid="image">
                <DataPlatformMark aria-hidden="true" height={24} width={24} />
              </span>
              {!isSidebarCollapsed && (
                <span>{t('hospitalNavigation.brand')}</span>
              )}
            </>
          )}
        </Link>
      </div>

      <div className="left-sidebar-layout">
        <div className="menu-container">
          <div className="top-menu">
            <Menu
              aria-label={t('hospitalNavigation.navigation')}
              inlineIndent={16}
              items={menuItems}
              mode="inline"
              openKeys={openKeys}
              rootClassName="left-sidebar-menu hospital-workspace__navigation"
              selectedKeys={selectedKeys}
              onClick={handleMenuClick}
              onOpenChange={(keys) => setOpenKeys(keys.slice(-1))}
            />
          </div>

          <div className="bottom-menu">
            <Menu
              inlineIndent={16}
              items={LOWER_SIDEBAR_TOP_SIDEBAR_MENU_ITEMS}
              mode="inline"
              rootClassName="left-sidebar-menu"
              selectedKeys={selectedKeys}
            />
          </div>
        </div>
      </div>

      {isConfirmLogoutModalOpen && (
        <Modal
          centered
          bodyStyle={{ textAlign: 'center' }}
          closable={false}
          closeIcon={null}
          footer={null}
          open={isConfirmLogoutModalOpen}
          width={360}
          onCancel={hideConfirmLogoutModal}>
          <Typography.Title level={5}>{t('label.logout')}</Typography.Title>
          <Typography.Text className="text-grey-muted">
            {t('message.logout-confirmation')}
          </Typography.Text>

          <div className="d-flex gap-2 w-full m-t-md justify-center">
            <Button className="confirm-btn" onClick={hideConfirmLogoutModal}>
              {t('label.cancel')}
            </Button>
            <Button
              className="confirm-btn"
              data-testid="confirm-logout"
              type="primary"
              onClick={onLogoutHandler}>
              {t('label.logout')}
            </Button>
          </div>
        </Modal>
      )}
    </Sider>
  );
};

export default LeftSidebar;
