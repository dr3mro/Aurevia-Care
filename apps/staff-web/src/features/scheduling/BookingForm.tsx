import { useState } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Appointment, BookingRequest } from './domain';
import { conflictingAppointment } from './domain';
import { schedulingText } from './messages';

/** Props for the synthetic protected-resource booking form. */
export interface BookingFormProps {
  /** Appointments already scoped to the active tenant. */
  appointments: readonly Appointment[];
  organizationId: string;
  onBook: (request: BookingRequest) => void;
}

/** Build a local booking draft and warn about a same-tenant occupied slot. */
export function BookingForm({ appointments, organizationId, onBook }: BookingFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [resourceId, setResourceId] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');

  const resource = resourceId.trim();
  const start = scheduledAt.trim();
  const ready = resource.length > 0 && start.length > 0;
  const request: BookingRequest = { organizationId, resourceId: resource, scheduledAt: start };
  const conflict = ready ? conflictingAppointment(appointments, request) : undefined;

  function handleSubmit() {
    if (!ready || conflict) {
      return;
    }
    onBook(request);
    // Clear the draft so a committed booking cannot conflict with itself.
    setResourceId('');
    setScheduledAt('');
  }

  return (
    <Form
      label={schedulingText(locale, 'bookingForm')}
      submitLabel={schedulingText(locale, 'book')}
      onSubmit={handleSubmit}
    >
      <FormField
        id="booking-resource"
        label={schedulingText(locale, 'resource')}
        value={resourceId}
        onChange={setResourceId}
        placeholder="room-1"
      />
      <p style={{ color: tokens.foreground }}>{schedulingText(locale, 'resourceHint')}</p>
      <FormField
        id="booking-start"
        label={schedulingText(locale, 'start')}
        value={scheduledAt}
        onChange={setScheduledAt}
        placeholder="2026-03-09T07:00:00Z"
      />
      <p role="status" data-testid="conflict-status">
        {conflict ? schedulingText(locale, 'conflictHint') : ''}
      </p>
    </Form>
  );
}
