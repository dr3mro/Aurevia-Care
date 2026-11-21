import { useI18n } from '../../i18n/I18nProvider';
import { PartnerBoardOverview } from './PartnerBoardScreen';
import { syntheticBoards } from './fixtures';

/**
 * Staff page for the partner outage board.
 *
 * The board is currently synthetic: no partner-interface health endpoint is
 * published in the generated contract, so the page reads fixtures and states
 * that plainly on screen. Live wiring belongs to a separate API story.
 */
export function PartnerBoardPage() {
  const { locale } = useI18n();
  return <PartnerBoardOverview boards={syntheticBoards} locale={locale} />;
}
