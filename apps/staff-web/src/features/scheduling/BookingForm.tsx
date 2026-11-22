import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../../theme/useBreakpoint';
import type { Appointment, BookingRequest } from './domain';
import { conflictingAppointment } from './domain';
import { schedulingText } from './messages';

/**
 * A booking draft with the clinical context the real API requires. The
 * synthetic `BookingRequest` fields stay unchanged; the extra values are
 * entered by the user so no patient identity is invented on their behalf.
 */
export type BookingInput = BookingRequest & {
  facilityCode: string;
  patientId: string;
  visitType: string;
};

/** Props for the protected-resource booking form. */
export interface BookingFormProps {
  /** Appointments already scoped to the active tenant. */
  appointments: readonly Appointment[];
  organizationId: string;
  onBook: (request: BookingInput) => void;
}

/** Build a booking draft and warn about a same-tenant occupied slot. */
export function BookingForm({ appointments, organizationId, onBook }: BookingFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const [resourceId, setResourceId] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');
  const [facilityCode, setFacilityCode] = useState('');
  const [patientId, setPatientId] = useState('');
  const [visitType, setVisitType] = useState('');

  const resource = resourceId.trim();
  const start = scheduledAt.trim();
  const facility = facilityCode.trim();
  const patient = patientId.trim();
  const visit = visitType.trim();
  const ready =
    resource.length > 0 &&
    start.length > 0 &&
    facility.length > 0 &&
    patient.length > 0 &&
    visit.length > 0;
  const request: BookingInput = {
    organizationId,
    resourceId: resource,
    scheduledAt: start,
    facilityCode: facility,
    patientId: patient,
    visitType: visit,
  };
  const conflict = ready ? conflictingAppointment(appointments, request) : undefined;

  // Presentation only. The shared Form already keeps one column on compact
  // viewports; this inner region stacks the booking fields on phones and reuses
  // the stylesheet's two-column grid from medium upward. It never hides a
  // field or changes what is submitted.
  const fieldLayout =
    breakpoint === 'compact'
      ? { className: 'stack', label: 'single-column' }
      : { className: 'grid-2', label: 'two-column' };

  // Long English and Arabic hints must wrap instead of forcing a horizontal
  // scroll on a narrow phone. Logical properties mirror the same rules in RTL.
  const hintStyle: CSSProperties = {
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  };

  function handleSubmit() {
    if (!ready || conflict) {
      return;
    }
    onBook(request);
    // Clear the slot so a committed booking cannot conflict with itself.
    setResourceId('');
    setScheduledAt('');
  }

  return (
    <Form
      label={schedulingText(locale, 'bookingForm')}
      submitLabel={schedulingText(locale, 'book')}
      onSubmit={handleSubmit}
    >
      <div
        className={fieldLayout.className}
        data-part="booking-fields"
        data-layout={fieldLayout.label}
        data-breakpoint={breakpoint}
        data-coarse-pointer={coarsePointer}
      >
        <FormField
          id="booking-facility"
          label={schedulingText(locale, 'facilityCode')}
          value={facilityCode}
          onChange={setFacilityCode}
          placeholder="MAIN"
        />
        <FormField
          id="booking-patient"
          label={schedulingText(locale, 'patientId')}
          value={patientId}
          onChange={setPatientId}
          placeholder="synthetic-patient-001"
        />
        <FormField
          id="booking-visit"
          label={schedulingText(locale, 'visitType')}
          value={visitType}
          onChange={setVisitType}
          placeholder="follow-up"
        />
        <FormField
          id="booking-resource"
          label={schedulingText(locale, 'resource')}
          value={resourceId}
          onChange={setResourceId}
          placeholder="clinician-7"
        />
        <FormField
          id="booking-start"
          label={schedulingText(locale, 'start')}
          value={scheduledAt}
          onChange={setScheduledAt}
          placeholder="2026-03-09T07:00:00Z"
        />
        {/* Span both grid columns from medium upward so long hints wrap across
            the full form width; grid line numbers are direction-neutral. */}
        <div
          className="stack"
          data-part="booking-hints"
          style={{ ...hintStyle, gridColumn: '1 / -1' }}
        >
          <p style={{ ...hintStyle, color: tokens.foreground }}>
            {schedulingText(locale, 'resourceHint')}
          </p>
          <p role="status" data-testid="conflict-status" style={hintStyle}>
            {conflict ? schedulingText(locale, 'conflictHint') : ''}
          </p>
        </div>
      </div>
    </Form>
  );
}
