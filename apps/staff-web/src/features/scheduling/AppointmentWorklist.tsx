import type { CSSProperties, ReactNode } from 'react';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { isTerminal } from './domain';
import type { Appointment, WorklistRange } from './domain';
import { schedulingText } from './messages';
import { VisitStateBadge } from './VisitStateBadge';

/** Synthetic assignees available to the reception work queue. */
const assignableStaff: ReadonlyArray<{ subject: string; name: string }> = [
  { subject: 'synthetic.reception', name: 'Synthetic Reception' },
  { subject: 'synthetic.nurse', name: 'Synthetic Nurse' },
];

/** Props for the day/week appointment worklist and its row controls. */
export interface AppointmentWorklistProps {
  /** Tenant-scoped, range-filtered and time-ordered appointments. */
  appointments: readonly Appointment[];
  range: WorklistRange;
  onRangeChange: (range: WorklistRange) => void;
  onCheckIn: (id: string) => void;
  onAssign: (id: string, assignee: string) => void;
}

/**
 * Render the accessible staff worklist with day/week and row controls.
 *
 * One real table, caption and full column set exist at every breakpoint: width
 * changes presentation only, never the clinical data or the available actions
 * (WEB-07, UX-01). On compact phones each row is styled as a stacked card and
 * the table sits in a labelled, keyboard-scrollable region so no column forces
 * a page-level horizontal scroll. Long English and Arabic values wrap instead
 * of clipping; the shell supplies safe-area padding and direction.
 */
export function AppointmentWorklist({
  appointments,
  range,
  onRangeChange,
  onCheckIn,
  onAssign,
}: AppointmentWorklistProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const compact = useBreakpoint() === 'compact';
  const text = (key: Parameters<typeof schedulingText>[1]) => schedulingText(locale, key);

  // Only the layout variables differ between bands. The card class is placed on
  // tbody so the existing `.card-list > *` rule gives each row a card border and
  // padding; the inline style stacks its cells with logical, mirroring properties.
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

  // Wrapping on every cell keeps long identifiers, values and mixed-script
  // names readable at 320 px and when text is scaled to 200 percent.
  const cellStyle: CSSProperties = {
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  };

  const table = (
    <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }} data-layout={layout}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {text('appointments')}
      </caption>
      <thead>
        <tr style={cardStyle}>
          <th scope="col" style={cellStyle}>
            {text('patient')}
          </th>
          <th scope="col" style={cellStyle}>
            {text('time')}
          </th>
          <th scope="col" style={cellStyle}>
            {text('resource')}
          </th>
          <th scope="col" style={cellStyle}>
            {text('state')}
          </th>
          <th scope="col" style={cellStyle}>
            {text('assignee')}
          </th>
          <th scope="col" style={cellStyle}>
            {text('actions')}
          </th>
        </tr>
      </thead>
      <tbody className={bodyClass}>
        {appointments.length === 0 ? (
          <tr>
            <td colSpan={6} style={cellStyle}>
              {text('noAppointments')}
            </td>
          </tr>
        ) : (
          appointments.map((appointment) => {
            // Composite key includes the tenant so identical local ids never merge.
            const key = `${appointment.organizationId}:${appointment.id}`;
            const rowName = displayLocalName(appointment.patientName, locale);
            return (
              <tr key={key} style={cardStyle}>
                <th scope="row" style={cellStyle}>
                  <MixedScriptText text={rowName} lang={locale} />
                </th>
                <td style={cellStyle}>
                  <time dateTime={appointment.scheduledAt}>
                    {formatInstant(appointment.scheduledAt, appointment.timeZone, locale)}
                  </time>
                </td>
                <td style={cellStyle}>{appointment.resourceId}</td>
                <td style={cellStyle}>
                  <VisitStateBadge state={appointment.state} locale={locale} />
                </td>
                <td style={cellStyle}>{appointment.assignee ?? text('unassigned')}</td>
                <td style={cellStyle}>
                  <Button
                    onClick={() => onCheckIn(appointment.id)}
                    disabled={appointment.state !== 'booked'}
                  >
                    {text('checkIn')}
                  </Button>
                  <label htmlFor={`assign-${key}`}>{text('assign')}</label>
                  <select
                    id={`assign-${key}`}
                    className="field touch"
                    style={{ fontSize: '16px' }}
                    value={appointment.assignee ?? ''}
                    disabled={isTerminal(appointment.state)}
                    onChange={(event) => onAssign(appointment.id, event.target.value)}
                  >
                    <option value="">{text('unassigned')}</option>
                    {assignableStaff.map((candidate) => (
                      <option key={candidate.subject} value={candidate.subject}>
                        {candidate.name}
                      </option>
                    ))}
                  </select>
                </td>
              </tr>
            );
          })
        )}
      </tbody>
    </table>
  );

  let worklist: ReactNode = table;
  if (compact) {
    // The labelled region is the one extra tab stop on phones; it lets a
    // keyboard user scroll the stacked cards without a page-level scrollbar.
    worklist = (
      <div
        className="table-region"
        role="region"
        aria-label={text('appointments')}
        tabIndex={0}
        data-layout="cards"
      >
        {table}
      </div>
    );
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <div
        role="group"
        aria-label={text('worklistRange')}
        className="cluster"
        style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}
      >
        <Button pressed={range === 'day'} onClick={() => onRangeChange('day')}>
          {text('day')}
        </Button>
        <Button pressed={range === 'week'} onClick={() => onRangeChange('week')}>
          {text('week')}
        </Button>
      </div>
      {worklist}
    </div>
  );
}
