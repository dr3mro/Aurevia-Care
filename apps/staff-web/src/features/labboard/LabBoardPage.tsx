import { useI18n } from '../../i18n/I18nProvider';
import { LabBoardOverview } from './LabBoardScreen';
import { outageBoard } from './fixtures';

/**
 * Staff page for the laboratory turnaround board.
 *
 * The board is synthetic: no turnaround or partner-health endpoint is published
 * in the generated contract, so the page reads fixtures and states that plainly
 * on screen. Live wiring belongs to a separate API story.
 */
export function LabBoardPage() {
  const { locale } = useI18n();
  return <LabBoardOverview board={outageBoard} locale={locale} />;
}
