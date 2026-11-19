import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AppointmentWorklist } from './AppointmentWorklist';
import { BookingForm } from './BookingForm';
import { appointmentsForTenant, applyCommand, replaceAppointment, selectWorklist } from './domain';
import type { Appointment, BookingRequest, WorklistRange } from './domain';
import { createSyntheticAppointment, schedulingAnchorCivilDate } from './fixtures';
import { schedulingText } from './messages';

/** Props for the synthetic scheduling screen. */
export interface SchedulingScreenProps {
  /** All synthetic appointments; the active tenant is selected inside. */
  appointments: readonly Appointment[];
  organizationId: string;
}

/** Compose the tenant-scoped worklist and booking form with local commands. */
export function SchedulingScreen({ appointments, organizationId }: SchedulingScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [range, setRange] = useState<WorklistRange>('day');
  const [items, setItems] = useState<Appointment[]>(() => [
    ...appointmentsForTenant(appointments, organizationId),
  ]);
  const [announcement, setAnnouncement] = useState('');

  // Switching the active organization starts a fresh tenant-local worklist;
  // local identifiers from the previous tenant are never carried forward.
  useEffect(() => {
    setItems([...appointmentsForTenant(appointments, organizationId)]);
    setAnnouncement('');
    setRange('day');
  }, [appointments, organizationId]);

  const visible = selectWorklist(items, organizationId, range, schedulingAnchorCivilDate);
  const scoped = appointmentsForTenant(items, organizationId);

  function handleCheckIn(id: string) {
    const target = items.find((appointment) => appointment.id === id)!;
    setItems((current) =>
      replaceAppointment(current, organizationId, id, (appointment) =>
        applyCommand(appointment, { kind: 'check-in' }),
      ),
    );
    setAnnouncement(`${target.patientName.latinName}: ${schedulingText(locale, 'stateCheckedIn')}`);
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

  function handleBook(request: BookingRequest) {
    const created = createSyntheticAppointment(request);
    setItems((current) => [...current, created]);
    setAnnouncement(`${created.patientName.latinName}: ${created.resourceId}`);
  }

  return (
    <section
      aria-label={schedulingText(locale, 'scheduling')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{schedulingText(locale, 'scheduling')}</h2>
      <p>{schedulingText(locale, 'syntheticNotice')}</p>
      <p role="status" data-testid="scheduling-status" aria-label={schedulingText(locale, 'status')}>
        {announcement}
      </p>
      <AppointmentWorklist
        appointments={visible}
        range={range}
        onRangeChange={setRange}
        onCheckIn={handleCheckIn}
        onAssign={handleAssign}
      />
      <BookingForm appointments={scoped} organizationId={organizationId} onBook={handleBook} />
    </section>
  );
}
