import React from 'react';
import { createRoot } from 'react-dom/client';
import { HelmetProvider } from 'react-helmet-async';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import '../../openmetadata-ui/src/main/resources/ui/src/utils/i18next/LocalUtil';
import IntegrateLoginPage from '../../openmetadata-ui/src/main/resources/ui/src/pages/LoginPage/IntegrateLoginPage';
import HospitalWorkbench from '../../openmetadata-ui/src/main/resources/ui/src/pages/MyDataPage/HospitalWorkbench';
import WorkflowsPage from '../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowsPage/WorkflowsPage';
import WorkflowBuilder from '../../openmetadata-ui/src/main/resources/ui/src/pages/WorkflowDefinitions/WorkflowBuilder/WorkflowBuilder';
import DomainTypeSelectForm from '../../openmetadata-ui/src/main/resources/ui/src/components/Domain/DomainTypeSelectForm/DomainTypeSelectForm.component';
import { DomainTypeChip } from '../../openmetadata-ui/src/main/resources/ui/src/components/DomainListing/components/DomainTypeChip';
import { DomainType } from '../../openmetadata-ui/src/main/resources/ui/src/generated/entity/domains/domain';
import '../../openmetadata-ui/src/main/resources/ui/src/styles/index';
import '../../openmetadata-ui/src/main/resources/ui/src/styles/hospital-theme.less';

document.body.classList.add('hospital-ui');
if (new URLSearchParams(location.search).get('theme') === 'dark') {
  document.documentElement.classList.add('dark-mode');
  document.body.classList.add('dark-mode');
}
const login = new URLSearchParams(location.search).get('surface') === 'login';
createRoot(document.getElementById('root')!).render(
  <HelmetProvider><BrowserRouter>
    <div style={{ padding: '8px 20px', fontSize: '12px', color: 'var(--color-text-secondary)', background: 'var(--color-bg-primary)' }}>
      界面验证 · 合成元数据，无真实患者数据 · 员工登录由 Integrate 提供
    </div>
    <Routes>
      <Route path="/workflows" element={<WorkflowsPage />} />
      <Route path="/workflows/new" element={<WorkflowBuilder />} />
      <Route path="/workflows/:fqn/:tab" element={<WorkflowBuilder />} />
      <Route path="/domains-preview" element={<div className="tw:p-6 tw:bg-primary">
        <h1>域类型 · 合成界面验证</h1>
        <DomainTypeSelectForm onCancel={() => undefined} onSubmit={() => undefined} />
        {Object.values(DomainType).map(value => <DomainTypeChip key={value} domainType={value} />)}
      </div>} />
      <Route path="*" element={login ? <IntegrateLoginPage /> : <HospitalWorkbench />} />
    </Routes>
  </BrowserRouter></HelmetProvider>
);
