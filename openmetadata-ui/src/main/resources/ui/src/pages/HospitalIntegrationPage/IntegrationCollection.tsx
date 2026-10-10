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
import { Button, Input, Select } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import {
  IntegrationCollectionPaginationProps,
  IntegrationCollectionToolsProps,
} from './HospitalIntegrationPage.interface';
import { getIntegrationCollectionSearch } from './HospitalIntegrationRouteUtils';

export const useIntegrationCollection = () => {
  const [params, setParams] = useSearchParams();
  const update = (key: string, value: string) => {
    const next = new URLSearchParams(
      getIntegrationCollectionSearch(params.toString())
    );
    if (value && value !== 'all') {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    if (key !== 'page') {
      next.delete('page');
    }
    setParams(next, { replace: true });
  };
  const reset = () => setParams(new URLSearchParams(), { replace: true });
  const requestedPage = Number(params.get('page'));
  const page =
    Number.isSafeInteger(requestedPage) && requestedPage > 0
      ? requestedPage
      : 1;

  return { params, update, reset, page };
};

export const IntegrationCollectionTools = ({
  filters,
  params,
  onChange,
  onReset,
}: IntegrationCollectionToolsProps) => {
  const { t } = useTranslation();

  return (
    <div
      className="hospital-integration__filters"
      data-testid="integration-collection-filters">
      <Input
        id="integration-collection-search"
        label={t('label.search')}
        maxLength={256}
        value={params.get('q') ?? ''}
        onChange={(value) => onChange('q', value)}
      />
      {filters.map((filter) => (
        <Select
          id={'integration-filter-' + filter.key}
          items={[{ id: 'all', label: t('label.all') }, ...filter.options]}
          key={filter.key}
          label={filter.label}
          selectedKey={params.get(filter.key) ?? 'all'}
          onSelectionChange={(key) => onChange(filter.key, String(key))}>
          {(item) => <Select.Item {...item} />}
        </Select>
      ))}
      <Button color="secondary" onClick={onReset}>
        {t('label.reset')}
      </Button>
    </div>
  );
};

export const IntegrationCollectionPagination = ({
  total,
  page,
  pageSize = 25,
  onPageChange,
}: IntegrationCollectionPaginationProps) => {
  const { t } = useTranslation();
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const current = Math.min(page, pages);

  return (
    <div
      className="hospital-integration__pagination"
      data-testid="integration-collection-pagination">
      <p role="status">
        {t('hospitalIntegration.loadedResults', { count: total })}
      </p>
      <div>
        <Button
          color="secondary"
          isDisabled={current <= 1}
          onClick={() => onPageChange(current - 1)}>
          {t('label.previous')}
        </Button>
        <span>
          {t('label.page')} {current} / {pages}
        </span>
        <Button
          color="secondary"
          isDisabled={current >= pages}
          onClick={() => onPageChange(current + 1)}>
          {t('label.next')}
        </Button>
      </div>
    </div>
  );
};
