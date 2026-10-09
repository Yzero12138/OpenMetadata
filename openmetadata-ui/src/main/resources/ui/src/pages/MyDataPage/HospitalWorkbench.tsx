import { ArrowRightOutlined, DatabaseOutlined } from '@ant-design/icons';
import { Button, Input } from '@openmetadata/ui-core-components';
import { SearchLg } from '@untitledui/icons';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import PageLayoutV1 from '../../components/PageLayoutV1/PageLayoutV1';
import { ROUTES } from '../../constants/constants';
import { Table } from '../../generated/entity/data/table';
import APIClient from '../../rest';
import { getExplorePath } from '../../utils/RouterUtils';
import './hospital-workbench.less';

interface CatalogList<T> {
  data: T[];
  paging: { total: number };
}

const metrics = [
  { key: 'tables', path: '/tables' },
  { key: 'glossaries', path: '/glossaries' },
  { key: 'tests', path: '/dataQuality/testCases' },
  { key: 'sources', path: '/services/databaseServices' },
] as const;
type MetricKey = (typeof metrics)[number]['key'];

const HospitalWorkbench = () => {
  const { t } = useTranslation('hospital');
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [counts, setCounts] = useState<
    Partial<Record<MetricKey, number | null>>
  >({});
  const [assets, setAssets] = useState<Table[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>(
    'loading'
  );
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setCounts({});
    setStatus('loading');
    const load = async () => {
      const results = await Promise.allSettled(
        metrics.map((metric) =>
          APIClient.get<CatalogList<Table>>(metric.path, {
            signal: controller.signal,
            params: {
              limit: metric.key === 'tables' ? 5 : 1,
              ...(metric.key === 'tables' ? { fields: 'owners' } : {}),
            },
          })
        )
      );
      if (controller.signal.aborted) {
        return;
      }
      const totals: Partial<Record<MetricKey, number | null>> = {};
      results.forEach((result, index) => {
        totals[metrics[index].key] =
          result.status === 'fulfilled' ? result.value.data.paging.total : null;
      });
      setCounts(totals);
      const tables = results[0];
      if (tables.status === 'fulfilled') {
        setAssets(tables.value.data.data);
        setStatus('ready');
      } else {
        setAssets([]);
        setStatus('error');
      }
    };
    void load();

    return () => controller.abort();
  }, [attempt]);

  return (
    <PageLayoutV1 pageTitle={t('welcome')}>
      <main className="hospital-workbench" data-testid="hospital-workbench">
        <header className="hospital-workbench__header">
          <div>
            <h1>{t('welcome')}</h1>
            <p>{t('welcomeBody')}</p>
          </div>
          <Button
            color="secondary"
            onClick={() => setAttempt((value) => value + 1)}>
            {t('reload')}
          </Button>
        </header>
        <form
          className="hospital-workbench__search"
          onSubmit={(event) => {
            event.preventDefault();
            navigate(
              getExplorePath({ search: search.trim(), isPersistFilters: false })
            );
          }}>
          <Input
            icon={SearchLg}
            label={t('searchLabel')}
            placeholder={t('searchPlaceholder')}
            value={search}
            onChange={setSearch}
          />
          <Button size="lg" type="submit">
            {t('search')}
          </Button>
        </form>
        <dl aria-label={t('catalog')} className="hospital-workbench__metrics">
          {metrics.map(({ key }) => (
            <div key={key}>
              <dt>{t(key)}</dt>
              <dd>
                {counts[key] === undefined ? (
                  <span className="hospital-workbench__skeleton" />
                ) : counts[key] === null ? (
                  <span className="hospital-workbench__unavailable">
                    {t('notAvailable')}
                  </span>
                ) : (
                  counts[key]?.toLocaleString()
                )}
              </dd>
            </div>
          ))}
        </dl>
        <div className="hospital-workbench__columns">
          <section
            aria-busy={status === 'loading'}
            aria-labelledby="hospital-assets-title"
            className="hospital-workbench__assets">
            <div className="hospital-workbench__section-heading">
              <h2 id="hospital-assets-title">{t('recent')}</h2>
              <Link to={getExplorePath({ isPersistFilters: false })}>
                {t('viewCatalog')}
              </Link>
            </div>
            {status === 'loading' ? (
              <div
                aria-label={t('reload')}
                className="hospital-workbench__loading"
                role="status">
                {[1, 2, 3].map((item) => (
                  <span className="hospital-workbench__skeleton" key={item} />
                ))}
              </div>
            ) : status === 'error' ? (
              <div className="hospital-workbench__empty" role="alert">
                <p>{t('failed')}</p>
              </div>
            ) : assets.length === 0 ? (
              <div className="hospital-workbench__empty">
                <DatabaseOutlined aria-hidden="true" />
                <h3>{t('empty')}</h3>
                <p>{t('emptyBody')}</p>
              </div>
            ) : (
              <div className="hospital-workbench__table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">{t('asset')}</th>
                      <th scope="col">{t('source')}</th>
                      <th scope="col">{t('owner')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {assets.map((asset) => (
                      <tr key={asset.id}>
                        <td>
                          <Link
                            to={`/table/${encodeURIComponent(
                              asset.fullyQualifiedName ?? asset.name
                            )}`}>
                            {asset.displayName || asset.name}
                          </Link>
                          <small>{asset.fullyQualifiedName}</small>
                        </td>
                        <td>
                          {asset.service?.displayName || asset.service?.name}
                        </td>
                        <td>
                          {asset.owners
                            ?.map((owner) => owner.displayName || owner.name)
                            .join('、') || t('unassigned')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
          <aside
            aria-labelledby="hospital-actions-title"
            className="hospital-workbench__actions">
            <h2 id="hospital-actions-title">{t('actions')}</h2>
            <Link to={ROUTES.GLOSSARY}>
              <span>{t('glossaryAction')}</span>
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
            <Link to={ROUTES.DATA_QUALITY}>
              <span>{t('qualityAction')}</span>
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
            <Link to="/settings/services/databases">
              <span>{t('servicesAction')}</span>
              <ArrowRightOutlined aria-hidden="true" />
            </Link>
            <p>{t('permissionsHint')}</p>
          </aside>
        </div>
      </main>
    </PageLayoutV1>
  );
};

export default HospitalWorkbench;
