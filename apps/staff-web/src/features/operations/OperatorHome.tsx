import { useMemo } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { Link } from '../../app/router';
import type { Role } from '../../app/navigation';
import { useRole } from '../../app/useRole';
import { LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { QueueQuery } from './api';
import { loadQueues } from './api';
import type { QueueCollection } from './types';
import { queueCounts } from './domain';
import { queuesText, queueTabKey } from './messages';
import { queueIds } from './types';

/** Props for the operator role's role-aware home content. */
export interface OperatorHomeProps {
  /** Role override for previews and tests; production uses the signed-in role. */
  role?: Role | undefined;
}

/**
 * The `/` content for an operator: one link per queue carrying its live count.
 * Exported here so the application shell can mount it without this feature
 * claiming the app-reserved `'/'` route (READY-51).
 */
export function OperatorHome({ role }: OperatorHomeProps) {
  const detectedRole = useRole();
  const effectiveRole = role ?? detectedRole;
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const query = useMemo<QueueQuery>(
    () => ({ unitId: 'synthetic-unit', facilityCode: 'MAIN' }),
    [],
  );
  const result = useApi<QueueCollection>(() => loadQueues(organizationId, query), [
    organizationId,
    query,
  ]);

  if (effectiveRole !== 'operator') {
    return null;
  }
  if (result.status === 'loading') {
    return <LoadingState />;
  }
  if (result.status === 'error') {
    return <div>{StateView.forTransportError(result.error)}</div>;
  }
  const counts = queueCounts(result.data);
  return (
    <section
      aria-label={queuesText(locale, 'operatorHome')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h2>{queuesText(locale, 'operatorHome')}</h2>
      <ul>
        {queueIds.map((queue) => (
          <li key={queue}>
            <Link to="/operations/queues">
              {`${queuesText(locale, queueTabKey[queue])} (${counts[queue]})`}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
