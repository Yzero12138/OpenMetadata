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

import english from '../locale/languages/en-us.json';
import chinese from '../locale/languages/zh-cn.json';

const flatten = (
  resource: Record<string, unknown>,
  prefix = ''
): Record<string, string> =>
  Object.fromEntries(
    Object.entries(resource).flatMap(([key, value]) => {
      const fullKey = prefix ? `${prefix}.${key}` : key;
      if (typeof value === 'string') {
        return [[fullKey, value]];
      }
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        return Object.entries(
          flatten(value as Record<string, unknown>, fullKey)
        );
      }

      return [];
    })
  );

const tokens = (text: string) =>
  [...new Set(text.match(/{{[^}]+}}|<\/?[0-9]+>/g) || [])].sort();

it('preserves dynamic values and interactive Trans elements throughout the Chinese UI', () => {
  const source = flatten(english);
  const translated = flatten(chinese);
  const missing = Object.keys(source).filter(
    (key) => translated[key] === undefined
  );
  const broken = Object.keys(source).filter(
    (key) =>
      translated[key] !== undefined &&
      JSON.stringify(tokens(source[key])) !==
        JSON.stringify(tokens(translated[key]))
  );

  expect(missing).toEqual([]);
  expect(broken).toEqual([]);
});
