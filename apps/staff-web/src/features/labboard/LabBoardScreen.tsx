/** Presentational components for the laboratory turnaround board (WEB-09). */

import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  isOutage,
  isOverdue,
  minutesBeyondTarget,
  ordersByStage,
  TURNAROUND_STAGES,
} from './domain';
import type { TurnaroundBoard, TurnaroundOrder, TurnaroundStage } from './domain';
import { labBoardMessage, labBoardText, numberText, stageColumnKey } from './labBoardMessages';

/**
 * Fixed alert colour for an overdue card. The token set has no danger token yet
 * (design doc "Known deviations"), so this is intentionally a module constant;
 * the overdue state is always carried by a word as well, never by colour alone.
 */
const OVERDUE_COLOUR = '#b3261e';

/** Props for one order card. */
export interface TurnaroundCardProps {
  order: TurnaroundOrder;
  locale: Locale;
}

/** Render one order card with its age against target and worded, coloured state. */
export function TurnaroundCard({ order, locale }: TurnaroundCardProps) {
  const { tokens } = useTheme();
  const overdue = isOverdue(order);
  const borderColour = overdue ? OVERDUE_COLOUR : tokens.border;
  const statusColour = overdue ? { color: OVERDUE_COLOUR } : undefined;
  return (
    <li
      data-testid="turnaround-card"
      data-overdue={overdue}
      style={{
        border: `1px solid ${borderColour}`,
        borderRadius: '4px',
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
        listStyle: 'none',
      }}
    >
      <code dir="ltr">{order.accession}</code>
      <p>
        {labBoardMessage(locale, 'ageTarget', {
          age: numberText(order.ageMinutes, locale),
          target: numberText(order.targetMinutes, locale),
        })}
      </p>
      <p data-testid="turnaround-status" style={statusColour}>
        {overdue
          ? labBoardMessage(locale, 'overdue', {
              minutes: numberText(minutesBeyondTarget(order), locale),
            })
          : labBoardText(locale, 'onTrack')}
      </p>
    </li>
  );
}

/** Props for the partner-interface outage banner. */
export interface OutageBannerProps {
  locale: Locale;
}

/** Render a persistent, in-flow outage alert with the approved manual route link. */
export function OutageBanner({ locale }: OutageBannerProps) {
  const { tokens } = useTheme();
  const procedureId = 'lab-manual-route';
  return (
    <section
      role="alert"
      aria-label={labBoardText(locale, 'outageRegion')}
      data-testid="outage-banner"
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <h3>{labBoardText(locale, 'outageTitle')}</h3>
      <p>{labBoardText(locale, 'outageDetail')}</p>
      <a
        href={`#${procedureId}`}
        style={{
          minHeight: '32px',
          display: 'inline-block',
          padding: tokens.spacing.sm,
          color: tokens.accent,
        }}
      >
        {labBoardText(locale, 'outageLink')}
      </a>
      <div id={procedureId}>
        <h4>{labBoardText(locale, 'outageProcedure')}</h4>
        <p>{labBoardText(locale, 'outageProcedureDetail')}</p>
      </div>
    </section>
  );
}

/** Props for one workflow column. */
export interface TurnaroundColumnProps {
  stage: TurnaroundStage;
  orders: readonly TurnaroundOrder[];
  locale: Locale;
}

/** Render one labelled column with its card count and card list or empty state. */
export function TurnaroundColumn({ stage, orders, locale }: TurnaroundColumnProps) {
  const { tokens } = useTheme();
  const label = labBoardText(locale, stageColumnKey(stage));
  return (
    <section
      aria-label={label}
      data-testid="turnaround-column"
      data-stage={stage}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        alignContent: 'start',
        gap: tokens.spacing.sm,
      }}
    >
      <h3 style={{ margin: 0 }}>
        {label}{' '}
        <span data-testid="column-count">
          {labBoardMessage(locale, 'orderCount', {
            count: numberText(orders.length, locale),
          })}
        </span>
      </h3>
      {orders.length === 0 ? (
        <p>{labBoardText(locale, 'noOrders')}</p>
      ) : (
        <ol
          style={{
            listStyle: 'none',
            margin: 0,
            padding: 0,
            display: 'grid',
            gap: tokens.spacing.sm,
          }}
        >
          {orders.map((order) => (
            <TurnaroundCard key={order.id} order={order} locale={locale} />
          ))}
        </ol>
      )}
    </section>
  );
}

/** Props for the whole board. */
export interface LabBoardOverviewProps {
  board: TurnaroundBoard;
  locale: Locale;
}

/** Render the board heading, conditional outage banner and five columns. */
export function LabBoardOverview({ board, locale }: LabBoardOverviewProps) {
  const { tokens } = useTheme();
  const grouped = ordersByStage(board.orders);
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{labBoardText(locale, 'title')}</h2>
        <p>{labBoardText(locale, 'syntheticNotice')}</p>
      </header>
      {isOutage(board.interfaceHealth) ? <OutageBanner locale={locale} /> : null}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(5, minmax(0, 1fr))',
          gap: tokens.spacing.md,
        }}
      >
        {TURNAROUND_STAGES.map((stage) => (
          <TurnaroundColumn key={stage} stage={stage} orders={grouped[stage]} locale={locale} />
        ))}
      </div>
    </div>
  );
}
