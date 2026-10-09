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

import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import {
  createWorkflowDefinition,
  getWorkflowDefinitions,
} from '../../../rest/workflowDefinitionsAPI';
import WorkflowsPage from './WorkflowsPage';

jest.mock('../../../rest/workflowDefinitionsAPI', () => ({
  getWorkflowDefinitions: jest.fn(),
  createWorkflowDefinition: jest.fn(),
}));
jest.mock('../../../hooks/useAppMode', () => ({ useIsAiMode: () => false }));
jest.mock('../../../components/PageLayoutV1/PageLayoutV1', () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));
jest.mock('../../../components/PageHeader/PageHeader.component', () => ({
  __esModule: true,
  default: () => <h1>Workflows</h1>,
}));
jest.mock(
  '../../../components/Learning/LearningIcon/LearningIcon.component',
  () => ({
    LearningIcon: () => null,
  })
);

const DraftDestination = () => {
  const { state }: { state: unknown } = useLocation();

  return <pre data-testid="local-workflow-draft">{JSON.stringify(state)}</pre>;
};

const renderWorkflows = async () => {
  render(
    <MemoryRouter>
      <Routes>
        <Route element={<WorkflowsPage />} path="/" />
        <Route element={<DraftDestination />} path="/workflows/new" />
      </Routes>
    </MemoryRouter>
  );
  await act(async () => {
    await (getWorkflowDefinitions as jest.Mock).mock.results[0].value;
  });
};

describe('Workflow creation', () => {
  beforeEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
    (getWorkflowDefinitions as jest.Mock).mockResolvedValue({
      data: [
        {
          name: 'SyntheticReview',
          fullyQualifiedName: 'SyntheticReview',
          description: '',
        },
      ],
      paging: { total: 1 },
    });
  });

  it('opens the visual editor with a Chinese display name without persisting an empty graph', async () => {
    await renderWorkflows();
    await userEvent.click(await screen.findByTestId('create-workflow-button'));
    await userEvent.type(
      screen.getByRole('textbox', { name: /^label.workflow-name/ }),
      'qa_quality_review'
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /^label.display-name/ }),
      '数据质量复核'
    );
    await userEvent.type(
      screen.getByRole('textbox', { name: /^label.description/ }),
      '合成验收流程'
    );
    await userEvent.click(screen.getByTestId('submit-workflow-button'));

    expect(await screen.findByTestId('local-workflow-draft')).toHaveTextContent(
      '数据质量复核'
    );
    expect(screen.getByTestId('local-workflow-draft')).toHaveTextContent(
      'qa_quality_review'
    );
    expect(createWorkflowDefinition).not.toHaveBeenCalled();
  });

  it('keeps an invalid technical name in the form without navigating or writing', async () => {
    await renderWorkflows();
    await userEvent.click(await screen.findByTestId('create-workflow-button'));
    await userEvent.type(
      screen.getByRole('textbox', { name: /^label.workflow-name/ }),
      'invalid.name'
    );
    await userEvent.click(screen.getByTestId('submit-workflow-button'));

    await waitFor(() =>
      expect(
        screen.getByText('message.workflow-name-invalid-characters')
      ).toBeInTheDocument()
    );

    expect(
      screen.queryByTestId('local-workflow-draft')
    ).not.toBeInTheDocument();
    expect(createWorkflowDefinition).not.toHaveBeenCalled();
  });
});
