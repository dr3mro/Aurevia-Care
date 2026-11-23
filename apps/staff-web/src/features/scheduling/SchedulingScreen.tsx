import { useState } from 'react';
import { defined } from '../../components/invariant';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { AppointmentWorklist } from './AppointmentWorklist';
import type { BookingInput } from './BookingForm';
import { BookingForm } from './BookingForm';
import { BookingCheckPanel } from './BookingCheckPanel';
import { CheckInPanel } from './CheckInPanel';
import type {
  Appointment,
  CheckInRecord,
  FreeSlot,
  NoShowRecord,
  PatientIdentity,
  WorklistFilter,
  WorklistRange,
} from './domain';
import { appointmentsForTenant, applyCommand, replaceAppointment } from './domain';
import {
  receptionChecker,
  receptionFacilities,
  receptionIdentityFor,
  receptionIdleMinutes,
  receptionRole,
  receptionStaleSlot,
  receptionUnreadMessages,
  receptionWalkIn,
} from './fixtures';
import { NoShowReasonForm } from './NoShowReasonForm';
import { ReceptionHeader } from './ReceptionHeader';
import { ReceptionNav } from './ReceptionNav';
import { schedulingText, schedulingTextWithValue } from './messages';
import { StateSummaryCards } from './StateSummaryCards';

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

/** Scope a tenant-scoped list to the facility selected in the reception header. */
function forFacility(appointments: readonly Appointment[], facilityCode: string): Appointment[] {
  return appointments.filter((appointment) => appointment.facilityCode === facilityCode);
}

/** Props for the narrow check-in and booking-check column. */
interface SidePanelsProps {
  appointment: Appointment | undefined;
  identity: PatientIdentity | undefined;
  reasonAppointment: Appointment | undefined;
  appointments: readonly Appointment[];
  organizationId: string;
  checker: string;
  onCheckIn: (record: CheckInRecord) => void;
  onPickSlot: (slot: FreeSlot) => void;
  onNoShowReason: (record: NoShowRecord) => void;
  onBook: (request: BookingInput) => void;
}

/** Render the narrow column of check-in, booking-check and reason forms. */
function ReceptionSidePanels({
  appointment,
  identity,
  reasonAppointment,
  appointments,
  organizationId,
  checker,
  onCheckIn,
  onPickSlot,
  onNoShowReason,
  onBook,
}: SidePanelsProps) {
  return (
    <div style={{ display: 'grid', gap: 0, alignContent: 'start', minInlineSize: 0 }}>
      <CheckInPanel
        key={appointment?.id}
        appointment={appointment}
        identity={identity}
        checker={checker}
        onCheckIn={onCheckIn}
      />
      <BookingCheckPanel staleSlot={receptionStaleSlot} onPickSlot={onPickSlot} />
      {reasonAppointment === undefined ? null : (
        <NoShowReasonForm
          patientName={reasonAppointment.patientName.latinName}
          actor={checker}
          onRecord={onNoShowReason}
        />
      )}
      <BookingForm appointments={appointments} organizationId={organizationId} onBook={onBook} />
    </div>
  );
}

/**
 * Compose the tenant-scoped reception Today screen: navigation, header, state
 * cards, worklist, check-in and booking-check panels. Data and durable commands
 * belong to the page; only presentation state (facility, filter, selected row,
 * pending no-show reason) is held here. Every row still passes through the
 * single `appointmentsForTenant` boundary, and no new endpoint is invented.
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
  const [facilityCode, setFacilityCode] = useState(
    defined(receptionFacilities[0], 'first reception facility').code,
  );
  const [filter, setFilter] = useState<WorklistFilter>('all');
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [reasonForId, setReasonForId] = useState<string | undefined>(undefined);

  const [source, setSource] = useState({ appointments, organizationId });
  if (source.appointments !== appointments || source.organizationId !== organizationId) {
    setSource({ appointments, organizationId });
    setItems([...appointmentsForTenant(appointments, organizationId)]);
    setAnnouncement('');
    setSelectedId(undefined);
    setReasonForId(undefined);
  }

  const visible = forFacility(appointmentsForTenant(items, organizationId), facilityCode);
  const stacked = breakpoint === 'compact';
  const wrappingStyle = { overflowWrap: 'anywhere' } as const;
  const panelAppointment =
    visible.find((appointment) => appointment.id === selectedId) ??
    visible.find((appointment) => appointment.state === 'booked');
  const panelIdentity =
    panelAppointment === undefined ? undefined : receptionIdentityFor(panelAppointment, organizationId);
  const reasonAppointment =
    reasonForId === undefined ? undefined : visible.find((appointment) => appointment.id === reasonForId);

  function patientNameFor(id: string): string {
    return defined(visible.find((appointment) => appointment.id === id), 'worklist row').patientName.latinName;
  }

  function handleCheckIn(id: string) {
    setSelectedId(id);
    setAnnouncement(`${patientNameFor(id)}: ${schedulingText(locale, 'commandSent')}`);
    onCheckIn(id);
  }

  function handleAssign(id: string, assignee: string) {
    const target = defined(items.find((appointment) => appointment.id === id), 'assign target');
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

  function handlePickSlot(slot: FreeSlot) {
    handleBook({
      organizationId,
      resourceId: slot.resourceId,
      scheduledAt: slot.scheduledAt,
      facilityCode: receptionWalkIn.facilityCode,
      patientId: receptionWalkIn.patientId,
      visitType: receptionWalkIn.visitType,
    });
  }

  function handleNewBooking() {
    defined(document.getElementById('booking-facility'), 'booking form field').focus();
  }

  function handleHandoff(id: string) {
    setAnnouncement(`${patientNameFor(id)}: ${schedulingText(locale, 'handoff')}`);
  }

  function handleOpenChart(id: string) {
    setAnnouncement(`${patientNameFor(id)}: ${schedulingText(locale, 'openChart')}`);
  }

  function handleRebook(id: string) {
    setReasonForId(id);
  }

  function handleNoShowReason(record: { reason: string }) {
    setAnnouncement(schedulingTextWithValue(locale, 'recordedNoShow', record.reason));
    setReasonForId(undefined);
  }

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-layout={locale === 'ar' ? 'rtl' : 'ltr'}
      data-scheduling-view={stacked ? 'stacked-panels' : 'side-by-side-panels'}
      aria-label={schedulingText(locale, 'scheduling')}
      style={{ display: 'flex', flexDirection: 'row', gap: 0, minInlineSize: 0, inlineSize: '100%' }}
    >
      <ReceptionNav
        unreadMessages={receptionUnreadMessages}
        roleName={receptionRole}
        userName={receptionChecker}
        idleMinutes={receptionIdleMinutes}
      />
      <div style={{ display: 'grid', gap: 0, flex: 1, minInlineSize: 0 }}>
        <ReceptionHeader
          facilities={receptionFacilities}
          facilityCode={facilityCode}
          onFacilityChange={setFacilityCode}
          onNewBooking={handleNewBooking}
        />
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
        <StateSummaryCards appointments={visible} />
        <div className="grid-2" data-part="panels" data-layout={stacked ? 'stacked' : 'side-by-side'}>
          <AppointmentWorklist
            appointments={visible}
            range={range}
            onRangeChange={onRangeChange}
            filter={filter}
            onFilterChange={setFilter}
            onCheckIn={handleCheckIn}
            onAssign={handleAssign}
            onHandoff={handleHandoff}
            onOpenChart={handleOpenChart}
            onRebook={handleRebook}
          />
          <ReceptionSidePanels
            appointment={panelAppointment}
            identity={panelIdentity}
            reasonAppointment={reasonAppointment}
            appointments={appointmentsForTenant(items, organizationId)}
            organizationId={organizationId}
            checker={receptionChecker}
            onCheckIn={(record) =>
              setAnnouncement(schedulingTextWithValue(locale, 'checkInRecorded', record.checker))
            }
            onPickSlot={handlePickSlot}
            onNoShowReason={handleNoShowReason}
            onBook={handleBook}
          />
        </div>
      </div>
    </section>
  );
}
