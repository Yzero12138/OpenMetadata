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
import { ROUTES } from '../../constants/constants';
import {
  SIDEBAR_LIST,
  SIDEBAR_NESTED_KEYS,
} from '../../constants/LeftSidebar.constants';
import { getSidebarActiveKeys } from '../../utils/LeftSidebarUtils';
import { readDorisTaskTarget } from './DorisTableDesignUtils';

describe('Doris table navigation', () => {
  it('has a separate target-table menu and selects it on direct navigation', () => {
    const path = '/hospital/integration/target-tables';
    const group = SIDEBAR_LIST.find(
      (item) => item.key === ROUTES.HOSPITAL_INTEGRATION
    );

    expect(group?.children?.some((item) => item.redirect_url === path)).toBe(
      true
    );
    expect(getSidebarActiveKeys(path, SIDEBAR_NESTED_KEYS)).toEqual([path]);
  });

  it('carries only bounded target identifiers into a new integration task', () => {
    const target = {
      connectionId: 'doris-ods-validation',
      schema: 'ods_validation',
      table: 'visits',
    };

    expect(readDorisTaskTarget({ dorisTarget: target })).toEqual(target);
    expect(
      readDorisTaskTarget({ dorisTarget: { ...target, table: 'visits;DROP' } })
    ).toBeUndefined();
    expect(
      readDorisTaskTarget({
        dorisTarget: { ...target, connectionId: 'x'.repeat(65) },
      })
    ).toBeUndefined();
    expect(readDorisTaskTarget(null)).toBeUndefined();
  });
});
