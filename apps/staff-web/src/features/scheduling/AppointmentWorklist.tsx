import type { CSSProperties, ReactNode } from 'react';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { ThemeTokens } from '../../theme/tokens';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { Appointment, WorklistFilter, WorklistRange } from './domain';
import { nextVisitStates } from './domain';
import { receptionIdentityFor, receptionLastUpdatedSeconds, receptionNow } from './fixtures';
import { schedulingText, schedulingTextWithValue, stateNoteMessageKey } from './messages';
import { VisitStateBadge } from './VisitStateBadge';

/** Synthetic assignees available to the reception work queue. */
const assignableStaff: readonly { subject: string; name: string }[] = [
  { subject: 'synthetic.reception', name: 'Synthetic Reception' },
  { subject: 'synthetic.nurse', name: 'Synthetic Nurse' },
];

/** The one action shown in each row, derived from the visit lifecycle. */
type RowAction = 'check-in' | 'handoff' | 'open-chart' | 'rebook';

/** Props for the day/week appointment worklist and its row controls. */
export interface AppointmentWorklistProps {
  /** Tenant-scoped, range-filtered and time-ordered appointments. */
  appointments: readonly Appointment[];
  range: WorklistRange;
  onRangeChange: (range: WorklistRange) => void;
  filter?: WorklistFilter | undefined;
  onFilterChange?: ((filter: WorklistFilter) => void) | undefined;
  onCheckIn: (id: string) => void;
  onAssign: (id: string, assignee: string) => void;
  onHandoff?: ((id: string) => void) | undefined;
  onOpenChart?: ((id: string) => void) | undefined;
  onRebook?: ((id: string) => void) | undefined;
}

/** Inert sink for optional callbacks when a host renders the base worklist. */
const noop = (): void => undefined;

// A horizontally scrolling region must be reachable by keyboard (WCAG 2.1.1).
const SCROLL_REGION_TAB_INDEX = 0;

/** Fifteen minutes, the threshold of the waiting filter chip. */
const WAIT_THRESHOLD_MS = 15 * 60 * 1000;

/** Decide whether one row belongs to the active filter chip. */
export function matchesFilter(appointment: Appointment, filter: WorklistFilter): boolean {
  if (filter === 'needs-check-in') {
    return appointment.state === 'booked';
  }
  if (filter === 'waiting-over-15') {
    return (
      appointment.state === 'checked-in' &&
      Date.parse(receptionNow) - Date.parse(appointment.scheduledAt) > WAIT_THRESHOLD_MS
    );
  }
  return true;
}

/** Map a visit state to its single worklist action. */
export function actionFor(state: Appointment['state']): RowAction {
  if (state === 'booked') {
    return 'check-in';
  }
  if (state === 'checked-in') {
    return 'handoff';
  }
  if (state === 'in-progress' || state === 'completed') {
    return 'open-chart';
  }
  return 'rebook';
}

/** Required row callbacks, after optional hosts have been defaulted. */
interface RowCallbacks {
  onCheckIn: (id: string) => void;
  onAssign: (id: string, assignee: string) => void;
  onHandoff: (id: string) => void;
  onOpenChart: (id: string) => void;
  onRebook: (id: string) => void;
}

/** Dispatch one row action to the callback the screen owns. */
function dispatchAction(action: RowAction, id: string, callbacks: RowCallbacks): void {
  if (action === 'check-in') {
    callbacks.onCheckIn(id);
  } else if (action === 'handoff') {
    callbacks.onHandoff(id);
  } else if (action === 'open-chart') {
    callbacks.onOpenChart(id);
  } else {
    callbacks.onRebook(id);
  }
}

/** Props for one synthetic worklist row. */
interface RowProps {
  appointment: Appointment;
  tokens: ThemeTokens;
  cellStyle: CSSProperties;
  cardStyle: CSSProperties | undefined;
  callbacks: RowCallbacks;
}

/** Render one tenant-scoped appointment with identity context and one action. */
function WorklistRow({ appointment, tokens, cellStyle, cardStyle, callbacks }: RowProps) {
  const { locale } = useI18n();
  const key = `${appointment.organizationId}:${appointment.id}`;
  const identity = receptionIdentityFor(appointment, appointment.organizationId);
  const name = displayLocalName(appointment.patientName, locale);
  const action = actionFor(appointment.state);
  const actionKey = action === 'check-in' ? 'checkIn' : action === 'handoff' ? 'handoff' : action === 'open-chart' ? 'openChart' : 'rebook';
  const needsAssignee = action === 'handoff' && appointment.assignee === undefined;
  return (
    <tr key={key} style={cardStyle}>
      <td style={cellStyle}>
        <time dateTime={appointment.scheduledAt}>
          {formatInstant(appointment.scheduledAt, appointment.timeZone, locale)}
        </time>
      </td>
      <th scope="row" style={cellStyle}>
        <MixedScriptText text={name} lang={locale} />
        <div aria-hidden="true">
          {schedulingText(locale, 'mrnLabel')} {identity.mrn}
        </div>
        <div aria-hidden="true">{schedulingTextWithValue(locale, 'ageNote', identity.ageYears)}</div>
      </th>
      <td style={cellStyle}>{appointment.visitType}</td>
      <td style={cellStyle}>
        {identity.clinician}
        <br />
        {identity.room}
      </td>
      <td style={cellStyle}>
        <VisitStateBadge state={appointment.state} locale={locale} />
        <div>{schedulingText(locale, stateNoteMessageKey[appointment.state])}</div>
      </td>
      <td style={cellStyle}>
        <span aria-hidden="true">{appointment.assignee ?? schedulingText(locale, 'unassigned')}</span>
        <label htmlFor={`assign-${key}`}>{schedulingText(locale, 'assignee')}</label>
        <select
          id={`assign-${key}`}
          className="field touch"
          style={{ fontSize: '16px' }}
          value={appointment.assignee ?? ''}
          disabled={nextVisitStates(appointment.state).length === 0}
          onChange={(event) => callbacks.onAssign(appointment.id, event.target.value)}
        >
          <option value="">{schedulingText(locale, 'unassigned')}</option>
          {assignableStaff.map((candidate) => (
            <option key={candidate.subject} value={candidate.subject}>
              {candidate.name}
            </option>
          ))}
        </select>
      </td>
      <td style={cellStyle}>
        <Button
          disabled={needsAssignee}
          onClick={() => dispatchAction(action, appointment.id, callbacks)}
        >
          {schedulingText(locale, actionKey)}
        </Button>
        {needsAssignee ? (
          <p data-testid="handoff-hint" style={{ color: tokens.foreground, marginBlock: 0 }}>
            {schedulingText(locale, 'handoffHint')}
          </p>
        ) : null}
      </td>
    </tr>
  );
}

/**
 * Render the accessible staff worklist with range and filter chips, the full
 * clinical column set, one lifecycle action per row and a count footer.
 *
 * One real table and every column exist at each breakpoint: width changes
 * presentation only, never the clinical data or the available actions. On
 * compact phones each row is styled as a stacked card in a labelled,
 * keyboard-scrollable region. Long values wrap with logical properties and the
 * tenant boundary remains the caller's scoped list (WEB-07, UX-01, CLN-02).
 */
export function AppointmentWorklist({
  appointments,
  range,
  onRangeChange,
  filter,
  onFilterChange,
  onCheckIn,
  onAssign,
  onHandoff,
  onOpenChart,
  onRebook,
}: AppointmentWorklistProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const compact = useBreakpoint() === 'compact';
  const text = (key: Parameters<typeof schedulingText>[1]) => schedulingText(locale, key);
  const activeFilter = filter ?? 'all';
  const changeFilter = onFilterChange ?? noop;
  const handoff = onHandoff ?? noop;
  const openChart = onOpenChart ?? noop;
  const rebook = onRebook ?? noop;
  const visible = appointments.filter((appointment) => matchesFilter(appointment, activeFilter));

  let bodyClass: string | undefined;
  let cardStyle: CSSProperties | undefined;
  let layout: 'cards' | 'table' = 'table';
  if (compact) {
    bodyClass = 'card-list';
    cardStyle = {
      display: 'flex',
      flexDirection: 'column',
      gap: tokens.spacing.sm,
      paddingBlock: tokens.spacing.sm,
      paddingInline: tokens.spacing.sm,
      minInlineSize: 0,
      overflowWrap: 'anywhere',
      whiteSpace: 'normal',
    };
    layout = 'cards';
  }

  const cellStyle: CSSProperties = {
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  };
  const headStyle: CSSProperties = { ...cellStyle, textTransform: 'uppercase' };
  const callbacks = { onCheckIn, onAssign, onHandoff: handoff, onOpenChart: openChart, onRebook: rebook };
  const filters: readonly { id: WorklistFilter; label: 'filterAll' | 'filterNeedsCheckIn' | 'filterWaiting' }[] = [
    { id: 'all', label: 'filterAll' },
    { id: 'needs-check-in', label: 'filterNeedsCheckIn' },
    { id: 'waiting-over-15', label: 'filterWaiting' },
  ];

  const table = (
    <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }} data-layout={layout}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {text('appointments')}
      </caption>
      <thead>
        <tr style={cardStyle}>
          <th scope="col" style={headStyle}>{text('colTime')}</th>
          <th scope="col" style={headStyle}>{text('colPatient')}</th>
          <th scope="col" style={headStyle}>{text('colVisit')}</th>
          <th scope="col" style={headStyle}>{text('colClinicianRoom')}</th>
          <th scope="col" style={headStyle}>{text('colState')}</th>
          <th scope="col" style={headStyle}>{text('colAssigned')}</th>
          <th scope="col" style={headStyle}>{text('colActions')}</th>
        </tr>
      </thead>
      <tbody className={bodyClass}>
        {visible.length === 0 ? (
          <tr>
            <td colSpan={7} style={cellStyle}>{text('noAppointments')}</td>
          </tr>
        ) : (
          visible.map((appointment) => (
            <WorklistRow
              key={`${appointment.organizationId}:${appointment.id}`}
              appointment={appointment}
              tokens={tokens}
              cellStyle={cellStyle}
              cardStyle={cardStyle}
              callbacks={callbacks}
            />
          ))
        )}
      </tbody>
    </table>
  );

  let worklist: ReactNode = table;
  if (compact) {
    worklist = (
      <div
        className="table-region"
        role="region"
        aria-label={text('appointments')}
        tabIndex={SCROLL_REGION_TAB_INDEX}
        data-layout="cards"
      >
        {table}
      </div>
    );
  }

  return (
    <div
      data-testid="appointment-worklist"
      style={{ display: 'grid', gap: tokens.spacing.sm, minInlineSize: 0, inlineSize: '100%' }}
    >
      <h3 style={{ marginBlock: 0 }}>{text('worklistHeading')}</h3>
      <div
        role="group"
        aria-label={text('worklistRange')}
        className="cluster"
        style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}
      >
        <Button pressed={range === 'day'} onClick={() => onRangeChange('day')}>{text('day')}</Button>
        <Button pressed={range === 'week'} onClick={() => onRangeChange('week')}>{text('week')}</Button>
      </div>
      <div
        role="group"
        aria-label={text('filterLabel')}
        style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}
      >
        {filters.map((item) => (
          <Button
            key={item.id}
            pressed={activeFilter === item.id}
            onClick={() => changeFilter(item.id)}
          >
            {text(item.label)}
          </Button>
        ))}
      </div>
      {worklist}
      <footer
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
          gap: tokens.spacing.sm,
          color: tokens.foreground,
          minInlineSize: 0,
        }}
      >
        <span>{schedulingTextWithValue(locale, 'footerShowing', visible.length)}</span>
        <span>{schedulingTextWithValue(locale, 'footerUpdated', receptionLastUpdatedSeconds)}</span>
      </footer>
    </div>
  );
}
