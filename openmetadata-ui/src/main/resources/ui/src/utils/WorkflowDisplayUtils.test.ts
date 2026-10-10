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

import { createInstance } from 'i18next';
import { getLocalizedWorkflow } from './WorkflowDisplayUtils';

const glossaryWorkflow = {
  name: 'GlossaryTermApprovalWorkflow',
  displayName: 'Glossary Approval Workflow',
  description:
    'When a Glossary Term is Created or Updated, this Workflow will be triggered for the Term to be Approved.',
};
const chineseDisplay = {
  displayName: '术语审批工作流',
  description: '创建或更新术语时，将触发此工作流，对该术语进行审批。',
};
const translations = createInstance();

beforeAll(async () => {
  await translations.init({
    lng: 'zh-CN',
    fallbackLng: false,
    resources: {
      'zh-CN': {
        translation: {
          builtinWorkflow: {
            GlossaryTermApprovalWorkflow: chineseDisplay,
          },
        },
      },
    },
  });
});

describe('getLocalizedWorkflow', () => {
  it('resolves unchanged seeded text through the real i18next translator', () => {
    expect(getLocalizedWorkflow(glossaryWorkflow, translations.t)).toEqual(
      chineseDisplay
    );
  });

  it('preserves a customized name while translating the unchanged description', () => {
    expect(
      getLocalizedWorkflow(
        { ...glossaryWorkflow, displayName: '临床数据术语审批' },
        translations.t
      )
    ).toEqual({
      displayName: '临床数据术语审批',
      description: chineseDisplay.description,
    });
  });

  it('preserves a customized description while translating the unchanged name', () => {
    expect(
      getLocalizedWorkflow(
        { ...glossaryWorkflow, description: '由临床数据治理委员会审核。' },
        translations.t
      )
    ).toEqual({
      displayName: chineseDisplay.displayName,
      description: '由临床数据治理委员会审核。',
    });
  });

  it('does not normalize customized whitespace or capitalization', () => {
    const customized = {
      ...glossaryWorkflow,
      displayName: ` ${glossaryWorkflow.displayName}`,
      description: glossaryWorkflow.description.toLowerCase(),
    };

    expect(getLocalizedWorkflow(customized, translations.t)).toEqual({
      displayName: customized.displayName,
      description: customized.description,
    });
  });

  it('preserves intentionally empty and missing display text', () => {
    expect(
      getLocalizedWorkflow(
        { ...glossaryWorkflow, displayName: undefined, description: '' },
        translations.t
      )
    ).toEqual({ displayName: undefined, description: '' });
  });

  it.each(['HospitalApprovalWorkflow', 'toString', '__proto__'])(
    'preserves an unrecognized workflow named %s even when its text matches a seed',
    (name) => {
      expect(
        getLocalizedWorkflow({ ...glossaryWorkflow, name }, translations.t)
      ).toEqual({
        displayName: glossaryWorkflow.displayName,
        description: glossaryWorkflow.description,
      });
    }
  );

  it('returns only display text without changing persisted workflow metadata', () => {
    const workflow = Object.freeze({
      ...glossaryWorkflow,
      deployed: true,
      trigger: Object.freeze({ type: 'eventBasedEntity' }),
      nodes: Object.freeze([{ name: 'review', subType: 'userApprovalTask' }]),
    });

    expect(getLocalizedWorkflow(workflow, translations.t)).toEqual(
      chineseDisplay
    );
    expect(workflow).toEqual({
      ...glossaryWorkflow,
      deployed: true,
      trigger: { type: 'eventBasedEntity' },
      nodes: [{ name: 'review', subType: 'userApprovalTask' }],
    });
  });

  it('falls back to seeded text when a translation is unavailable', async () => {
    const unsupportedLocale = createInstance();
    await unsupportedLocale.init({
      lng: 'en-US',
      fallbackLng: false,
      resources: { 'en-US': { translation: {} } },
    });

    expect(getLocalizedWorkflow(glossaryWorkflow, unsupportedLocale.t)).toEqual(
      {
        displayName: glossaryWorkflow.displayName,
        description: glossaryWorkflow.description,
      }
    );
  });
});
