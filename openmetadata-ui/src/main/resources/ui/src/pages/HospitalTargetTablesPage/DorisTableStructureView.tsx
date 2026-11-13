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
import { useTranslation } from 'react-i18next';
import { DorisTableStructureProps } from './HospitalTargetTablesPage.interface';

export const DorisTableStructureView = ({
  structure,
}: DorisTableStructureProps) => {
  const { t } = useTranslation();

  return (
    <div
      className="hospital-table-design__structure"
      data-testid="doris-table-structure">
      <dl className="hospital-table-design__summary">
        <div>
          <dt>{t('hospitalIntegration.targetTable')}</dt>
          <dd>
            {structure.targetSchema}.{structure.targetTable}
          </dd>
        </div>
        <div>
          <dt>{t('label.description')}</dt>
          <dd>{structure.comment || t('label.no-description')}</dd>
        </div>
        <div>
          <dt>{t('hospitalTableDesign.model')}</dt>
          <dd>{structure.model}</dd>
        </div>
        <div>
          <dt>{t('hospitalTableDesign.buckets')}</dt>
          <dd>{structure.buckets ?? '—'}</dd>
        </div>
      </dl>
      <div className="hospital-table-design__table-scroll">
        <table className="hospital-table-design__structure-table">
          <caption>{t('hospitalTableDesign.existingStructure')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('label.name')}</th>
              <th scope="col">{t('label.type')}</th>
              <th scope="col">{t('label.primary-key')}</th>
              <th scope="col">{t('hospitalTableDesign.nullable')}</th>
              <th scope="col">{t('hospitalTableDesign.defaultValue')}</th>
              <th scope="col">{t('label.description')}</th>
            </tr>
          </thead>
          <tbody>
            {structure.columns.map((column) => (
              <tr key={column.name}>
                <th scope="row">{column.name}</th>
                <td>
                  {structure.table.columns.find(
                    (item) => item.name === column.name
                  )?.dataType ?? column.type}
                </td>
                <td>{t(column.primaryKey ? 'label.yes' : 'label.no')}</td>
                <td>{t(column.nullable ? 'label.yes' : 'label.no')}</td>
                <td>
                  {column.defaultKind === 'LITERAL'
                    ? column.defaultValue
                    : t('hospitalTableDesign.defaults.' + column.defaultKind)}
                </td>
                <td>{column.comment || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details className="hospital-table-design__ddl">
        <summary>{t('hospitalTableDesign.showDdl')}</summary>
        <pre>{structure.ddl}</pre>
      </details>
    </div>
  );
};
