import { useState } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { MODALITIES } from './domain';
import type { ImagingWorklistOrder, Modality } from './domain';
import { modalityMessageKey, radworklistText } from './labels';

/** The scheduling facts the worklist API records on a received order. */
export interface ScheduleDraft {
  scheduledAt: string;
  modality: Modality;
  accession: string;
  accessionIssuer: string;
}

/** Props for the accession and modality form. */
export interface ScheduleFormProps {
  order: ImagingWorklistOrder;
  /** Receives a complete draft; the page owns the API command. */
  onSubmit: (draft: ScheduleDraft) => void;
}

/**
 * Collect the study time, an approved modality and the issuer-scoped accession.
 * Nothing is sent until every field is present, and a rejected command keeps the
 * draft because the form owns its state.
 */
export function ScheduleForm({ order, onSubmit }: ScheduleFormProps) {
  const { locale } = useI18n();
  const [scheduledAt, setScheduledAt] = useState('');
  const [accession, setAccession] = useState('');
  const [accessionIssuer, setAccessionIssuer] = useState('');
  // No clinical modality is assumed: an untouched form must not record CT.
  const [modality, setModality] = useState<Modality | ''>('');
  const ready = [scheduledAt, accession, accessionIssuer, modality].every(
    (value) => value.trim().length > 0,
  );

  function handleSubmit() {
    if (!ready) {
      return;
    }
    onSubmit({
      scheduledAt: scheduledAt.trim(),
      modality: modality as Modality,
      accession: accession.trim(),
      accessionIssuer: accessionIssuer.trim(),
    });
  }

  return (
    <Form
      label={radworklistText(locale, 'scheduleHeader')}
      submitLabel={radworklistText(locale, 'schedule')}
      onSubmit={handleSubmit}
    >
      <p>{order.orderCode}</p>
      <FormField
        id={`schedule-at-${order.orderId}`}
        label={radworklistText(locale, 'scheduledAt')}
        value={scheduledAt}
        onChange={setScheduledAt}
        placeholder="2026-03-09T09:00:00Z"
      />
      <label htmlFor={`schedule-modality-${order.orderId}`}>
        {radworklistText(locale, 'modality')}
      </label>
      <select
        id={`schedule-modality-${order.orderId}`}
        value={modality}
        onChange={(event) => setModality(event.target.value as Modality | '')}
      >
        <option value="">{radworklistText(locale, 'selectModality')}</option>
        {MODALITIES.map((option) => (
          <option key={option} value={option}>
            {radworklistText(locale, modalityMessageKey[option])}
          </option>
        ))}
      </select>
      <FormField
        id={`schedule-accession-${order.orderId}`}
        label={radworklistText(locale, 'accession')}
        value={accession}
        onChange={setAccession}
        placeholder="ACC-001"
      />
      <FormField
        id={`schedule-issuer-${order.orderId}`}
        label={radworklistText(locale, 'accessionIssuer')}
        value={accessionIssuer}
        onChange={setAccessionIssuer}
        placeholder="SYN-ORG"
      />
    </Form>
  );
}
