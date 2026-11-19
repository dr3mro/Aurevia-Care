import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
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

/** Render the accessible staff worklist with day/week and row controls. */
export function AppointmentWorklist({
  appointments,
  range,
  onRangeChange,
  onCheckIn,
  onAssign,
}: AppointmentWorklistProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof schedulingText>[1]) => schedulingText(locale, key);

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <div role="group" aria-label={text('worklistRange')} style={{ display: 'flex', gap: tokens.spacing.sm }}>
        <Button pressed={range === 'day'} onClick={() => onRangeChange('day')}>
          {text('day')}
        </Button>
        <Button pressed={range === 'week'} onClick={() => onRangeChange('week')}>
          {text('week')}
        </Button>
      </div>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{text('appointments')}</caption>
        <thead>
          <tr>
            <th scope="col">{text('patient')}</th>
            <th scope="col">{text('time')}</th>
            <th scope="col">{text('resource')}</th>
            <th scope="col">{text('state')}</th>
            <th scope="col">{text('assignee')}</th>
            <th scope="col">{text('actions')}</th>
          </tr>
        </thead>
        <tbody>
          {appointments.length === 0 ? (
            <tr>
              <td colSpan={6}>{text('noAppointments')}</td>
            </tr>
          ) : (
            appointments.map((appointment) => {
              // Composite key includes the tenant so identical local ids never merge.
              const key = `${appointment.organizationId}:${appointment.id}`;
              const rowName = displayLocalName(appointment.patientName, locale);
              return (
                <tr key={key}>
                  <th scope="row">
                    <MixedScriptText text={rowName} lang={locale} />
                  </th>
                  <td>
                    <time dateTime={appointment.scheduledAt}>
                      {formatInstant(appointment.scheduledAt, appointment.timeZone, locale)}
                    </time>
                  </td>
                  <td>{appointment.resourceId}</td>
                  <td>
                    <VisitStateBadge state={appointment.state} locale={locale} />
                  </td>
                  <td>{appointment.assignee ?? text('unassigned')}</td>
                  <td>
                    <Button
                      onClick={() => onCheckIn(appointment.id)}
                      disabled={appointment.state !== 'booked'}
                    >
                      {text('checkIn')}
                    </Button>
                    <label htmlFor={`assign-${key}`}>{text('assign')}</label>
                    <select
                      id={`assign-${key}`}
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
    </div>
  );
}
