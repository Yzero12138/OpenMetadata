import { IntegrationNavigationGuardProvider } from "../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/IntegrationNavigationGuardProvider";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Layout } from "antd";
import { createRoot } from "react-dom/client";
import { HelmetProvider } from "react-helmet-async";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import i18n from "../../openmetadata-ui/src/main/resources/ui/src/utils/i18next/LocalUtil";
import LeftSidebar from "../../openmetadata-ui/src/main/resources/ui/src/components/MyData/LeftSidebar/LeftSidebar.component";
import NavBar from "../../openmetadata-ui/src/main/resources/ui/src/components/NavBar/NavBar";
import { useApplicationStore } from "../../openmetadata-ui/src/main/resources/ui/src/hooks/useApplicationStore";
import { HospitalIntegrationWorkspace } from "../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationPage";
import { HospitalDataSourcesWorkspace } from "../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalDataSourcesPage";
import { HospitalIntegrationRedirect } from "../../openmetadata-ui/src/main/resources/ui/src/pages/HospitalIntegrationPage/HospitalIntegrationRedirect";
import "../../openmetadata-ui/src/main/resources/ui/src/styles/index";
import "../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less";
import "../../openmetadata-ui/src/main/resources/ui/src/components/AppContainer/app-container.less";
import "../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-workspace.less";

// Isolated visual fixture: no native authentication, employee identity or real database access.
const isAdmin = new URLSearchParams(location.search).get("role") !== "employee";
useApplicationStore
  .getState()
  .setCurrentUser({
    id: "00000000-0000-4000-8000-000000000999",
    name: "synthetic-ui-review",
    displayName: "合成界面验证",
    email: "synthetic@example.invalid",
    isAdmin,
  });
useApplicationStore.getState().setAppVersion("2.0.4");
document.body.classList.add("hospital-ui");
void i18n.changeLanguage("zh-CN");
createRoot(document.getElementById("root")!).render(
  <HelmetProvider>
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <IntegrationNavigationGuardProvider>
        <BrowserRouter>
          <Layout
            hasSider
            className="app-container hospital-workspace"
            style={{ height: "100vh", minHeight: 0 }}
          >
            <LeftSidebar />
            <Layout className="hospital-workspace__main">
              <NavBar />
              <Layout.Content className="hospital-workspace__content">
                <Routes>
                  <Route
                    path="/hospital/integration"
                    element={<HospitalIntegrationRedirect />}
                  />
                  <Route
                    path="/hospital/integration/sources"
                    element={<HospitalDataSourcesWorkspace isAdmin={isAdmin} />}
                  />
                  <Route
                    path="/hospital/integration/tasks"
                    element={<HospitalIntegrationWorkspace isAdmin={isAdmin} />}
                  />
                  <Route
                    path="/hospital/integration/tasks/:taskId"
                    element={<HospitalIntegrationWorkspace isAdmin={isAdmin} />}
                  />
                  <Route
                    path="*"
                    element={<p>PC 导航验证 · 合成元数据，无真实患者数据</p>}
                  />
                </Routes>
              </Layout.Content>
            </Layout>
          </Layout>
        </BrowserRouter>
      </IntegrationNavigationGuardProvider>
    </QueryClientProvider>
  </HelmetProvider>
);
