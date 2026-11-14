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
import {
  getIntegrationCollectionSearch,
  getIntegrationTaskId,
  getIntegrationTaskPath,
} from './HospitalIntegrationRouteUtils';

describe('Hospital integration page routes', () => {
  it('preserves collection filters while dropping login tickets and arbitrary parameters', () => {
    expect(
      getIntegrationCollectionSearch(
        '?q=HIS&mode=FULL&page=2&ticket=synthetic-ticket&integrate_connect=1&access_token=synthetic-token&redirect=https://example.invalid'
      )
    ).toBe('?q=HIS&mode=FULL&page=2');
  });

  it('bounds search values and returns no suffix for an empty collection query', () => {
    expect(
      new URLSearchParams(
        getIntegrationCollectionSearch('?q=' + 'x'.repeat(300))
      ).get('q')
    ).toHaveLength(256);
    expect(getIntegrationCollectionSearch('?ticket=synthetic-ticket')).toBe('');
  });

  it('encodes task identifiers for the detail URL and recognizes only detail routes', () => {
    expect(getIntegrationTaskPath('task with spaces')).toBe(
      '/hospital/integration/tasks/task%20with%20spaces'
    );
    expect(getIntegrationTaskId('/hospital/integration/tasks/demo')).toBe(
      'demo'
    );
    expect(getIntegrationTaskId('/hospital/integration/tasks')).toBeUndefined();
    expect(
      getIntegrationTaskId('/hospital/integration/sources')
    ).toBeUndefined();
    expect(
      getIntegrationTaskId('/hospital/integration/tasks/demo/extra')
    ).toBeUndefined();
  });
});
