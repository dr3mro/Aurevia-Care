import { useState } from 'react';
import type { SyntheticEvent } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../i18n/I18nProvider';
import type { ImagingWorklistOrder } from './domain';
import { checkPatientAndAccession } from './domain';
import { PatientAccessionCheck } from './PatientAccessionCheck';
import { radworklistText } from './labels';

/** The workstation labels and study reference needed to link an external study. */
export interface StudyLinkDraft {
  patientId: string;
  accession: string;
  studyInstanceUid: string;
  workstationRef: string;
}

/** Props for the verified study-link form. */
export interface StudyLinkFormProps {
  order: ImagingWorklistOrder;
  /** Receives a complete draft; the page owns the API command. */
  onLink: (draft: StudyLinkDraft) => void;
}

/**
 * Compare the partner labels with the order as they are typed and withhold the
 * link action until both match. The server repeats the check on link, so a
 * mismatch can never create a performed study even if this guard is bypassed.
 */
export function StudyLinkForm({ order, onLink }: StudyLinkFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [patientId, setPatientId] = useState('');
  const [accession, setAccession] = useState('');
  const [studyInstanceUid, setStudyInstanceUid] = useState('');
  const [workstationRef, setWorkstationRef] = useState('');

  const patient = patientId.trim();
  const accessionLabel = accession.trim();
  const checked = patient.length > 0 && accessionLabel.length > 0;
  const result = checked ? checkPatientAndAccession(order, patient, accessionLabel) : undefined;
  const matched = result?.ok === true;
  const canLink = matched && studyInstanceUid.trim().length > 0 && workstationRef.trim().length > 0;

  function handleSubmit(event: SyntheticEvent) {
    event.preventDefault();
    onLink({
      patientId: patient,
      accession: accessionLabel,
      studyInstanceUid: studyInstanceUid.trim(),
      workstationRef: workstationRef.trim(),
    });
  }

  return (
    <form
      aria-label={radworklistText(locale, 'linkHeader')}
      onSubmit={handleSubmit}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <FormField
        id={`link-patient-${order.orderId}`}
        label={radworklistText(locale, 'patientLabel')}
        value={patientId}
        onChange={setPatientId}
        placeholder="synthetic-patient-002"
      />
      <FormField
        id={`link-accession-${order.orderId}`}
        label={radworklistText(locale, 'accessionLabel')}
        value={accession}
        onChange={setAccession}
        placeholder="ACC-002"
      />
      <PatientAccessionCheck checked={checked} result={result} locale={locale} />
      <FormField
        id={`link-study-${order.orderId}`}
        label={radworklistText(locale, 'studyInstanceUid')}
        value={studyInstanceUid}
        onChange={setStudyInstanceUid}
        placeholder="1.2.826.0.1"
      />
      <FormField
        id={`link-workstation-${order.orderId}`}
        label={radworklistText(locale, 'workstationRef')}
        value={workstationRef}
        onChange={setWorkstationRef}
        placeholder="workstation-1"
      />
      <Button type="submit" disabled={!canLink}>
        {radworklistText(locale, 'linkStudy')}
      </Button>
    </form>
  );
}
