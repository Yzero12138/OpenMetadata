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
import { act, fireEvent, render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import LeftSidebar from './LeftSidebar.component';

let mockIsSidebarCollapsed = false;

jest.mock('../../../hooks/currentUserStore/useCurrentUserStore', () => ({
  useCurrentUserPreferences: () => ({
    preferences: { isSidebarCollapsed: mockIsSidebarCollapsed },
  }),
}));

jest.mock(
  '../../Settings/Applications/ApplicationsProvider/ApplicationsProvider',
  () => ({
    useApplicationsProvider: () => ({ applications: [], plugins: [] }),
  })
);

jest.mock('../../../hooks/useCustomPages', () => ({
  useCustomPages: jest.fn().mockReturnValue({
    customizedPage: null,
    navigation: null,
    isLoading: false,
  }),
}));

describe('LeftSidebar', () => {
  beforeEach(() => {
    mockIsSidebarCollapsed = false;
    window.history.replaceState(null, '', '/my-data');
  });

  it('renders sidebar links correctly', async () => {
    await act(async () => {
      render(
        <BrowserRouter>
          <LeftSidebar />
        </BrowserRouter>
      );
    });

    expect(screen.getByTestId('image')).toBeInTheDocument();
    expect(
      screen.getByTestId('app-bar-item-hospital-integration')
    ).toBeInTheDocument();
    expect(screen.getByTestId('observability')).toBeInTheDocument();
    expect(screen.getByTestId('data-marketplace-section')).toBeInTheDocument();
    expect(screen.getByTestId('governance')).toBeInTheDocument();
    expect(screen.getByTestId('app-bar-item-settings')).toBeInTheDocument();
    expect(screen.getByTestId('app-bar-item-logout')).toBeInTheDocument();
  });

  it('opens integration on a direct task detail load and follows browser history', async () => {
    window.history.replaceState(
      null,
      '',
      '/hospital/integration/tasks/task-17'
    );
    await act(async () => {
      render(
        <BrowserRouter>
          <LeftSidebar />
        </BrowserRouter>
      );
    });

    expect(
      screen
        .getByTestId('app-bar-item-hospital-integration-tasks')
        .closest('.ant-menu-item')
    ).toHaveClass('ant-menu-item-selected');
    expect(
      screen
        .getByTestId('app-bar-item-hospital-integration')
        .closest('.ant-menu-submenu')
    ).toHaveClass('ant-menu-submenu-open');

    await act(async () => {
      window.history.pushState(null, '', '/domain/Hospital');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    expect(
      screen.getByTestId('app-bar-item-domain').closest('.ant-menu-item')
    ).toHaveClass('ant-menu-item-selected');
    expect(
      screen
        .getByTestId('data-marketplace-section')
        .closest('.ant-menu-submenu')
    ).toHaveClass('ant-menu-submenu-open');
  });

  it('allows a user to open a different group before choosing a destination', async () => {
    await act(async () => {
      render(
        <BrowserRouter>
          <LeftSidebar />
        </BrowserRouter>
      );
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('governance'));
    });

    expect(
      screen.getByTestId('governance').closest('.ant-menu-submenu')
    ).toHaveClass('ant-menu-submenu-open');
  });

  it('keeps collapsed root, popup and fixed actions named when root labels are hidden', async () => {
    mockIsSidebarCollapsed = true;
    await act(async () => {
      render(
        <BrowserRouter>
          <LeftSidebar />
        </BrowserRouter>
      );
    });
    const integrationTitle = screen
      .getByTestId('app-bar-item-hospital-integration')
      .closest('.ant-menu-submenu-title');
    const titleContent = integrationTitle?.querySelector(
      '.ant-menu-title-content'
    );
    if (titleContent instanceof HTMLElement) {
      titleContent.style.display = 'none';
    }

    expect(integrationTitle).toHaveAccessibleName(
      'hospitalNavigation.integration'
    );
    expect(
      screen.getByRole('menuitem', { name: 'hospitalNavigation.workbench' })
    ).toHaveAttribute('aria-label', 'hospitalNavigation.workbench');
    expect(
      screen.getByTestId('app-bar-item-settings').closest('[role="menuitem"]')
    ).toHaveAttribute('aria-label', 'hospitalNavigation.settings');
    expect(
      screen.getByTestId('app-bar-item-logout').closest('[role="menuitem"]')
    ).toHaveAttribute('aria-label', 'label.logout');

    await act(async () => {
      if (integrationTitle) {
        fireEvent.mouseEnter(integrationTitle);
      }
    });

    expect(
      await screen.findByTestId(
        'side-bar-app-bar-item-hospital-integration-tasks'
      )
    ).toHaveAttribute('aria-label', 'hospitalNavigation.tasks');
  });
});
