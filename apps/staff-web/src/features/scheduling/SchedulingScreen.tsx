import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { AppointmentWorklist } from './AppointmentWorklist';
import type { BookingInput } from './BookingForm';
import { BookingForm } from './BookingForm';
import { appointmentsForTenant, applyCommand, replaceAppointment } from './domain';
import type { Appointment, WorklistRange } from './domain';
import { schedulingText } from './messages';

/** Props for the API-backed scheduling screen. */
export interface SchedulingScreenProps {
  /** Appointments loaded by the page for the active tenant and range. */
  appointments: readonly Appointment[];
  organizationId: string;
  range: WorklistRange;
  onRangeChange: (range: WorklistRange) => void;
  /** Sent when a booked row is checked in; the page owns the API command. */
  onCheckIn: (id: string) => void;
  /** Sent with the validated booking draft; the page owns the API command. */
  onBook: (request: BookingInput) => void;
}

/**
 * Compose the tenant-scoped worklist and booking form. Data and durable
 * commands belong to the page; only the range and the local assignment draft
 * (no API endpoint exists for it) are held here.
 *
 * Layout is presentation only. The root `.page` supplies logical safe-area
 * padding and the reading-width cap; the shared `.grid-2` stacks the two panels
 * on a phone and places them side by side from the medium breakpoint upward.
 * Both use `minmax(0, 1fr)` tracks, so a long clinical value wraps instead of
 * forcing page-level horizontal scroll. Long English and Arabic prose wraps
 * with a logical property, and no field or column is hidden at any band.
 * Behaviour, text, API calls and exported names are unchanged (WEB-07, UX-01).
 */
export function SchedulingScreen({
  appointments,
  organizationId,
  range,
  onRangeChange,
  onCheckIn,
  onBook,
}: SchedulingScreenProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [items, setItems] = useState<Appointment[]>(() => [
    ...appointmentsForTenant(appointments, organizationId),
  ]);
  const [announcement, setAnnouncement] = useState('');

  // A reload, range change or tenant switch supplies a fresh projection. Local
  // identifiers from a previous tenant are never carried forward.
  useEffect(() => {
    setItems([...appointmentsForTenant(appointments, organizationId)]);
    setAnnouncement('');
  }, [appointments, organizationId]);

  // The server already selected the instant window; re-filtering by civil date
  // here would drop late-evening rows displayed in a different facility zone.
  const visible = appointmentsForTenant(items, organizationId);
  const scoped = appointmentsForTenant(items, organizationId);
  const stacked = breakpoint === 'compact';

  // responsive.css has wrapping rules for .grid-2 > * but not for .page > *,
  // so keep long synthetic prose wrapping with a logical inline property rather
  // than editing the shared stylesheet.
  const wrappingStyle = { overflowWrap: 'anywhere' } as const;

  function handleCheckIn(id: string) {
    // Row actions exist only for visible ids, so the target is always present.
    const target = items.find((appointment) => appointment.id === id)!;
    setAnnouncement(`${target.patientName.latinName}: ${schedulingText(locale, 'commandSent')}`);
    onCheckIn(id);
  }

  function handleAssign(id: string, assignee: string) {
    const target = items.find((appointment) => appointment.id === id)!;
    setItems((current) =>
      replaceAppointment(current, organizationId, id, (appointment) =>
        applyCommand(appointment, { kind: 'assign', assignee }),
      ),
    );
    setAnnouncement(`${target.patientName.latinName}: ${assignee}`);
  }

  function handleBook(request: BookingInput) {
    setAnnouncement(`${request.patientId}: ${schedulingText(locale, 'commandSent')}`);
    onBook(request);
  }

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-scheduling-view={stacked ? 'stacked-panels' : 'side-by-side-panels'}
      aria-label={schedulingText(locale, 'scheduling')}
    >
      <h2 style={wrappingStyle}>{schedulingText(locale, 'scheduling')}</h2>
      <p style={wrappingStyle}>{schedulingText(locale, 'syntheticNotice')}</p>
      <p
        role="status"
        data-testid="scheduling-status"
        aria-label={schedulingText(locale, 'status')}
        style={wrappingStyle}
      >
        {announcement}
      </p>
      <div
        className="grid-2"
        data-part="panels"
        data-layout={stacked ? 'stacked' : 'side-by-side'}
      >
        <AppointmentWorklist
          appointments={visible}
          range={range}
          onRangeChange={onRangeChange}
          onCheckIn={handleCheckIn}
          onAssign={handleAssign}
        />
        <BookingForm appointments={scoped} organizationId={organizationId} onBook={handleBook} />
      </div>
    </section>
  );
}
