/** Presentational components for the partner interface and outage board. */

import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { fallbackFor, isStuck, scriptForName, stuckCount, waitingCount } from './domain';
import type { PartnerBoardWire, PartnerKind, WaitingItemWire } from './domain';
import {
  fallbackDetailKey,
  fallbackLabelKey,
  healthKey,
  partnerMessage,
  partnerText,
  responseKey,
  reviewKey,
  stuckKey,
  transmissionKey,
  waitMinutesText,
  workflowKey,
} from './partnerMessages';
import type { PartnerMessageKey } from './partnerMessages';

/** Table column keys, in reading order for both locales. */
const COLUMNS: readonly PartnerMessageKey[] = [
  'colReference',
  'colType',
  'colWait',
  'colWorkflow',
  'colTransmission',
  'colReview',
  'colResponse',
  'colStatus',
];

/** Props for the waiting-items table. */
export interface WaitingTableProps {
  items: readonly WaitingItemWire[];
  locale: Locale;
}

/** Render one partner's waiting orders and prescriptions as an accessible table. */
export function WaitingTable({ items, locale }: WaitingTableProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {partnerText(locale, 'waitingCaption')}
      </caption>
      <thead>
        <tr>
          {COLUMNS.map((key) => (
            <th key={key} scope="col">
              {partnerText(locale, key)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.id}>
            <td>
              <code dir="ltr">{item.reference}</code>
            </td>
            <td>
              {partnerText(
                locale,
                item.itemType === 'order' ? 'itemTypeOrder' : 'itemTypePrescription',
              )}
            </td>
            <td>
              {partnerMessage(locale, 'waitAge', {
                minutes: waitMinutesText(item.ageMinutes, locale),
              })}
            </td>
            <td>{partnerText(locale, workflowKey(item.workflow))}</td>
            <td>{partnerText(locale, transmissionKey(item.transmission))}</td>
            <td>{partnerText(locale, reviewKey(item.review))}</td>
            <td>{partnerText(locale, responseKey(item.recipientResponse))}</td>
            <td>{partnerText(locale, stuckKey(isStuck(item)))}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Props for the manual fallback link and procedure. */
export interface FallbackPanelProps {
  kind: PartnerKind;
  /** Partner name, so the region landmark is unique on the overview. */
  partnerName: string;
  locale: Locale;
}

/** Render the approved fallback for one partner with an in-page link to its procedure. */
export function FallbackPanel({ kind, partnerName, locale }: FallbackPanelProps) {
  const { tokens } = useTheme();
  const fallback = fallbackFor(kind);
  const procedureId = `fallback-${kind}`;
  return (
    <section
      aria-label={partnerMessage(locale, 'fallbackRegion', { partner: partnerName })}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h4>{partnerText(locale, 'fallbackTitle')}</h4>
      <a
        href={`#${procedureId}`}
        style={{
          minHeight: '32px',
          display: 'inline-block',
          padding: tokens.spacing.sm,
          color: tokens.accent,
        }}
      >
        {partnerMessage(locale, 'fallbackLink', {
          fallback: partnerText(locale, fallbackLabelKey(fallback)),
        })}
      </a>
      <div id={procedureId}>
        <p>{partnerText(locale, fallbackDetailKey(fallback))}</p>
        <p>{partnerText(locale, 'evidenceNote')}</p>
      </div>
    </section>
  );
}

/** Props for one partner's board. */
export interface PartnerBoardProps {
  board: PartnerBoardWire;
  locale: Locale;
}

/** Render interface health, backlog, waiting ages and the fallback for one partner. */
export function PartnerBoard({ board, locale }: PartnerBoardProps) {
  const { tokens } = useTheme();
  return (
    <section
      aria-label={partnerMessage(locale, 'boardRegion', { partner: board.displayName })}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.md,
      }}
    >
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>
          <MixedScriptText text={board.displayName} lang={scriptForName(board.displayName)} />
        </h3>
        <p>
          {partnerText(locale, 'healthLabel')}: {partnerText(locale, healthKey(board.health))}
        </p>
        <p>
          {partnerMessage(locale, 'lastChecked', {
            instant: formatInstant(board.lastCheckedAt, board.timeZone, locale),
          })}
        </p>
        <p>{partnerMessage(locale, 'waitingCount', { count: String(waitingCount(board)) })}</p>
        <p>{partnerMessage(locale, 'stuckCount', { count: String(stuckCount(board)) })}</p>
      </header>
      {board.waiting.length === 0 ? (
        <p>{partnerText(locale, 'noWaiting')}</p>
      ) : (
        <WaitingTable items={board.waiting} locale={locale} />
      )}
      <FallbackPanel kind={board.kind} partnerName={board.displayName} locale={locale} />
    </section>
  );
}

/** Props for the board of all partners. */
export interface PartnerBoardOverviewProps {
  boards: readonly PartnerBoardWire[];
  locale: Locale;
}

/** Render one board per partner under a shared, synthetic-data heading. */
export function PartnerBoardOverview({ boards, locale }: PartnerBoardOverviewProps) {
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{partnerText(locale, 'title')}</h2>
        <p>{partnerText(locale, 'syntheticNotice')}</p>
      </header>
      {boards.map((board) => (
        <PartnerBoard key={board.kind} board={board} locale={locale} />
      ))}
    </div>
  );
}
