import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { SpecimenDetails } from './domain';
import { labText } from './messages';

/** Props for the specimen detail form. */
export interface CollectionFormProps {
  value: SpecimenDetails;
  onChange: (next: SpecimenDetails) => void;
  onCollect: () => void;
  disabled: boolean;
}

/**
 * Collect the specimen fields the LABFLOW-01 API requires. The parent gates the
 * submit action on a completed identity check; this form only holds the draft.
 */
export function CollectionForm({ value, onChange, onCollect, disabled }: CollectionFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();

  return (
    <section
      aria-label={labText(locale, 'specimenDetails')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{labText(locale, 'specimenDetails')}</h3>
      <FormField
        id="lab-specimen-type"
        label={labText(locale, 'specimenType')}
        value={value.specimenType}
        onChange={(specimenType) => onChange({ ...value, specimenType })}
      />
      <FormField
        id="lab-site-code"
        label={labText(locale, 'siteCode')}
        value={value.siteCode}
        onChange={(siteCode) => onChange({ ...value, siteCode })}
      />
      <FormField
        id="lab-collector"
        label={labText(locale, 'collector')}
        value={value.collector}
        onChange={(collector) => onChange({ ...value, collector })}
      />
      <FormField
        id="lab-collected-at"
        label={labText(locale, 'collectedAt')}
        value={value.collectedAt}
        onChange={(collectedAt) => onChange({ ...value, collectedAt })}
      />
      <FormField
        id="lab-quality"
        label={labText(locale, 'quality')}
        value={value.quality}
        onChange={(quality) => onChange({ ...value, quality })}
      />
      <Button onClick={onCollect} disabled={disabled}>
        {labText(locale, 'recordCollection')}
      </Button>
    </section>
  );
}
