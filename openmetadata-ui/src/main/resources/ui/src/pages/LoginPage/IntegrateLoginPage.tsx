import {
  ArrowRightOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Button } from '@openmetadata/ui-core-components';
import axios from 'axios';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  exchangeIntegrateTicket,
  getIntegrateConfiguration,
  IntegrateConfiguration,
} from '../../rest/integrateAPI';
import { receiveIntegrateTicket } from '../../utils/IntegrateSso';
import {
  clearOidcToken,
  getOidcToken,
  setOidcToken,
} from '../../utils/SwTokenStorageUtils';
import './hospital-login.less';

interface IntegrateLoginPageProps {
  connect?: boolean;
  onConnected?: () => void;
}

const IntegrateLoginPage = ({
  connect = false,
  onConnected,
}: IntegrateLoginPageProps) => {
  const { t } = useTranslation('hospital');
  const [configuration, setConfiguration] = useState<IntegrateConfiguration>();
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const initialize = async () => {
      setLoading(true);
      setProblem('');
      try {
        const config = await getIntegrateConfiguration(controller.signal);
        setConfiguration(config);
        if (!config.enabled) {
          setProblem('configError');

          return;
        }
        if (connect) {
          await clearOidcToken();
          const ticket = await receiveIntegrateTicket(
            config.issuer,
            controller.signal
          );
          const response = await exchangeIntegrateTicket(
            ticket,
            controller.signal
          );
          await setOidcToken(response.accessToken);
          if ((await getOidcToken()) !== response.accessToken) {
            throw new Error('token_storage_unavailable');
          }
          window.opener?.postMessage(
            { type: 'datahub:sso:complete', challenge: ticket.challenge },
            config.issuer
          );
          window.opener = null;
          onConnected?.();
        }
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }
        if (axios.isAxiosError(error)) {
          setProblem(
            error.response?.status === 401 || error.response?.status === 403
              ? 'denied'
              : 'unavailable'
          );
        } else if (error instanceof Error) {
          setProblem(
            error.message === 'portal_timeout'
              ? 'timeout'
              : error.message === 'portal_denied'
              ? 'denied'
              : error.message === 'portal_required'
              ? 'direct'
              : 'configError'
          );
        }
        if (connect) {
          window.opener = null;
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      }
    };
    void initialize();

    return () => controller.abort();
  }, [attempt, connect, onConnected]);

  return (
    <main className="hospital-login" data-testid="integrate-login">
      <header className="hospital-login__brand">
        <SafetyCertificateOutlined aria-hidden="true" />
        <span>{t('title')}</span>
      </header>
      <div className="hospital-login__layout">
        <section className="hospital-login__intro">
          <h1>{t('title')}</h1>
          <p className="hospital-login__purpose">{t('purpose')}</p>
          <dl className="hospital-login__capabilities">
            {(['catalog', 'definitions', 'quality'] as const).map((key) => (
              <div key={key}>
                <dt>{t(key)}</dt>
                <dd>{t(`${key}Body`)}</dd>
              </div>
            ))}
          </dl>
        </section>
        <section
          aria-busy={loading}
          aria-labelledby="portal-title"
          className="hospital-login__portal">
          <SafetyCertificateOutlined
            aria-hidden="true"
            className="hospital-login__portal-icon"
          />
          <h2 id="portal-title">
            {t(connect && loading ? 'connecting' : 'portalTitle')}
          </h2>
          <p>{t(connect && loading ? 'connectingBody' : 'portalBody')}</p>
          {loading && (
            <div
              aria-label={t('connecting')}
              className="hospital-login__loading"
              role="status">
              <span />
              <span />
            </div>
          )}
          {problem && (
            <p className="hospital-login__error" role="alert">
              {t(problem)}
            </p>
          )}
          <Button
            className="hospital-login__action"
            href={configuration?.enabled ? configuration.portalUrl : undefined}
            iconTrailing={<ArrowRightOutlined aria-hidden="true" />}
            isDisabled={loading || !configuration?.enabled}
            size="lg">
            {t('portalAction')}
          </Button>
          {!loading && !configuration?.enabled && (
            <Button
              color="link-gray"
              onClick={() => setAttempt((value) => value + 1)}>
              {t('retry')}
            </Button>
          )}
          <p className="hospital-login__hint">{t('accountHint')}</p>
        </section>
      </div>
      <footer className="hospital-login__footer">
        <span>{t('secure')}</span>
        <span>{t('powered')}</span>
      </footer>
    </main>
  );
};

export default IntegrateLoginPage;
