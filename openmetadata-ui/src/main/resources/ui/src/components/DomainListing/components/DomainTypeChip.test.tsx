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

import { render, screen } from '@testing-library/react';
import { createInstance } from 'i18next';
import chinese from '../../../locale/languages/zh-cn.json';
import { DomainTypeChip } from './DomainTypeChip';

const mockTranslations = createInstance();
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: mockTranslations.t.bind(mockTranslations) }),
}));

beforeAll(async () => {
  await mockTranslations.init({
    lng: 'zh-CN',
    resources: { 'zh-CN': { translation: chinese } },
  });
});

it.each([
  ['Aggregate', '聚合'],
  ['Consumer-aligned', '消费对齐'],
  ['Source-aligned', '源对齐'],
])('shows a Chinese label for %s', (domainType, label) => {
  render(<DomainTypeChip domainType={domainType} />);

  expect(screen.getByText(label)).toBeInTheDocument();
  expect(screen.queryByText(domainType)).not.toBeInTheDocument();
});

it('keeps the unknown-domain placeholder', () => {
  render(<DomainTypeChip domainType="unknown" />);

  expect(screen.getByText('-')).toBeInTheDocument();
});
