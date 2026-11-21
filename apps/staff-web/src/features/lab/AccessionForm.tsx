import { useState } from 'react';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { labText } from './messages';
import type { AccessionBody } from './types';

/** Props for the requisition accession form. */
export interface AccessionFormProps {
  onSubmit: (body: AccessionBody) => void;
  onCancel: () => void;
}

/** A field-level error summary keyed by the field it describes. */
type FieldErrors = Record<keyof AccessionBody, string>;

/** No field has an error before the first submit. */
const NO_ERRORS: FieldErrors = {
  accessionNo: '',
  accessionIssuer: '',
  patientId: '',
  sourceOrderRef: '',
};

/**
 * Collect the issuer-scoped requisition identity needed by `POST
 * /api/v1/lab/accessions`. Specimen type and site are recorded later by the
 * collect command, which is where the API accepts them.
 */
export function AccessionForm({ onSubmit, onCancel }: AccessionFormProps) {
  const { locale } = useI18n();
  const [body, setBody] = useState<AccessionBody>({
    accessionNo: '',
    accessionIssuer: 'SYNLAB',
    patientId: '',
    sourceOrderRef: 'SYN-ORDER-0001',
  });
  const [errors, setErrors] = useState<FieldErrors>(NO_ERRORS);

  function field(key: keyof AccessionBody) {
    return (value: string) => setBody((current) => ({ ...current, [key]: value }));
  }

  function submit() {
    const nextErrors: FieldErrors = { ...NO_ERRORS };
    if (body.accessionNo.trim() === '') {
      nextErrors.accessionNo = labText(locale, 'required');
    }
    if (body.patientId.trim() === '') {
      nextErrors.patientId = labText(locale, 'required');
    }
    setErrors(nextErrors);
    if (nextErrors.accessionNo !== '' || nextErrors.patientId !== '') {
      return;
    }
    onSubmit(body);
  }

  return (
    <Form label={labText(locale, 'newAccession')} submitLabel={labText(locale, 'createAccession')} onSubmit={submit}>
      <FormField
        id="accession-no"
        label={labText(locale, 'accessionNo')}
        value={body.accessionNo}
        onChange={field('accessionNo')}
        error={errors.accessionNo}
      />
      <FormField
        id="accession-issuer"
        label={labText(locale, 'accessionIssuer')}
        value={body.accessionIssuer}
        onChange={field('accessionIssuer')}
      />
      <FormField
        id="accession-patient"
        label={labText(locale, 'patientId')}
        value={body.patientId}
        onChange={field('patientId')}
        error={errors.patientId}
      />
      <FormField
        id="accession-order"
        label={labText(locale, 'sourceOrderRef')}
        value={body.sourceOrderRef}
        onChange={field('sourceOrderRef')}
      />
      <Button variant="secondary" onClick={onCancel}>
        {labText(locale, 'cancel')}
      </Button>
    </Form>
  );
}
