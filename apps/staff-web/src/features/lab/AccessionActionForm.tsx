import { useState } from 'react';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { labText } from './messages';
import type { LabMessageKey } from './messages';
import type { ActionPayload, AccessionAction, LabAccession } from './types';

/** Props for the single-field command form shown in the accession detail. */
export interface AccessionActionFormProps {
  action: AccessionAction;
  accession: LabAccession;
  onSubmit: (payload: ActionPayload) => void;
  onCancel: () => void;
}

/** Per-command field configuration: what to ask, how to default, how to build. */
interface FieldConfig {
  label: LabMessageKey;
  initial: (accession: LabAccession) => string;
  build: (accession: LabAccession, value: string) => ActionPayload;
}

/** Identity and timestamp values are taken from the record or generated here. */
const FIELD_CONFIG: Record<AccessionAction, FieldConfig> = {
  collect: {
    label: 'specimenType',
    initial: () => 'SYN-WHOLE-BLOOD',
    build: (accession, value) => ({
      action: 'collect',
      body: {
        patientId: accession.patientId,
        accessionNo: accession.accessionNo,
        accessionIssuer: accession.accessionIssuer,
        specimenType: value,
        siteCode: 'SYN-MAIN',
        collector: 'SYN-COLLECTOR',
        collectedAt: new Date().toISOString(),
        expectedVersion: accession.version,
      },
    }),
  },
  receive: {
    label: 'quality',
    initial: () => 'SYN-ACCEPTABLE',
    build: (accession, value) => ({
      action: 'receive',
      body: {
        patientId: accession.patientId,
        accessionNo: accession.accessionNo,
        accessionIssuer: accession.accessionIssuer,
        quality: value,
        receivedAt: new Date().toISOString(),
        expectedVersion: accession.version,
      },
    }),
  },
  reject: {
    label: 'reason',
    initial: () => '',
    build: (accession, value) => ({
      action: 'reject',
      body: {
        reason: value,
        notification: 'SYN-NOTIFY',
        expectedVersion: accession.version,
      },
    }),
  },
  recollect: {
    label: 'newAccessionNo',
    initial: (accession) => `${accession.accessionNo}-R`,
    build: (accession, value) => ({
      action: 'recollect',
      body: {
        accessionNo: value,
        accessionIssuer: accession.accessionIssuer,
        expectedVersion: accession.version,
      },
    }),
  },
};

/**
 * Render the single field a command needs. Rejection is the only action that
 * requires free text from the operator, so an empty reason blocks the submit
 * and is announced inline. Every other action has a bounded synthetic default.
 */
export function AccessionActionForm({
  action,
  accession,
  onSubmit,
  onCancel,
}: AccessionActionFormProps) {
  const { locale } = useI18n();
  const config = FIELD_CONFIG[action];
  const [value, setValue] = useState(() => config.initial(accession));
  const [error, setError] = useState('');

  function submit() {
    const trimmed = value.trim();
    if (action === 'reject' && trimmed === '') {
      setError(labText(locale, 'reasonRequired'));
      return;
    }
    setError('');
    onSubmit(config.build(accession, trimmed));
  }

  return (
    <Form label={labText(locale, action)} submitLabel={labText(locale, action)} onSubmit={submit}>
      <FormField
        id={`action-${action}`}
        label={labText(locale, config.label)}
        value={value}
        onChange={setValue}
        error={error}
      />
      <Button variant="secondary" onClick={onCancel}>
        {labText(locale, 'cancel')}
      </Button>
    </Form>
  );
}
