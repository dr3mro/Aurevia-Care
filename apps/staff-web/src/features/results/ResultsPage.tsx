import { useCallback, useState } from 'react';

import { useActiveOrganization } from '../../api/activeOrganization';
import type { ApiLoader } from '../../api/useApi';
import { ApiPage } from '../../components/ApiPage';
import { useI18n } from '../../i18n/I18nProvider';
import { isEmptySnapshot } from './domain';
import {
  loadSyntheticResults,
  resultsNow,
  resultsTimeZone,
  resultsViewerId,
} from './fixtures';
import { resultsText } from './resultsMessages';
import { ResultsScreen } from './ResultsScreen';
import type { ResultsSnapshot } from './types';

/** Props for the results page; every default is a synthetic, deterministic value. */
export interface ResultsPageProps {
  /** Loader override for tests; production uses a local synthetic loader. */
  load?: ApiLoader<ResultsSnapshot> | undefined;
  /** Fixed clock used by the overdue calculation. */
  now?: string | undefined;
  /** IANA zone used to render instants. */
  timeZone?: string | undefined;
  /** Id whose coverage queues the chips describe. */
  viewerId?: string | undefined;
}

/**
 * Load one tenant's results through `ApiPage` and render every WEB-09 state.
 *
 * No results-inbox endpoint exists in the published contract, so the default
 * loader resolves synthetic fixtures only; it never builds a URL. The active
 * organization is part of the request dependencies, so a tenant switch clears
 * the previous rows before a replacement request can respond.
 */
export function ResultsPage({ load, now, timeZone, viewerId }: ResultsPageProps) {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const loader = load ?? loadSyntheticResults(organizationId);
  const clock = now ?? resultsNow;
  const zone = timeZone ?? resultsTimeZone;
  const viewer = viewerId ?? resultsViewerId;
  const [retryGeneration, setRetryGeneration] = useState(0);

  // ApiPage leaves a screen-owned retry action to the caller. Changing the
  // dependency key refetches the same tenant's resource without caching rows.
  const retry = useCallback(() => setRetryGeneration((value) => value + 1), []);

  return (
    <ApiPage
      resource={{
        label: resultsText(locale, 'title'),
        load: loader,
        deps: [organizationId, retryGeneration],
      }}
      isEmpty={isEmptySnapshot}
      actions={{ onRetry: retry, onReload: retry }}
    >
      {(snapshot: ResultsSnapshot) => (
        <ResultsScreen
          snapshot={snapshot}
          now={clock}
          timeZone={zone}
          viewerId={viewer}
          onReadBack={() => undefined}
          onAcknowledge={() => undefined}
          onEscalate={() => undefined}
          onAskLab={() => undefined}
          onAssignOwner={() => undefined}
          onLink={() => undefined}
        />
      )}
    </ApiPage>
  );
}
