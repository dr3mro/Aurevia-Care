import { useState } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { NoShowRecord } from './domain';
import { receptionNow } from './fixtures';
import { schedulingText } from './messages';

/** Props for the inline no-show reason form. */
export interface NoShowReasonFormProps {
  patientName: string;
  actor: string;
  onRecord: (record: NoShowRecord) => void;
}

/**
 * Capture the reason for a no-show before recording it. An empty reason is
 * rejected at this boundary so a clinical record is never written without an
 * explanation; the actor and synthetic instant are attached by the caller's
 * record sink. The form uses the shared labelled field and stays bilingual.
 */
export function NoShowReasonForm({ patientName, actor, onRecord }: NoShowReasonFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [reason, setReason] = useState('');
  const [rejected, setRejected] = useState(false);

  function submit() {
    const trimmed = reason.trim();
    if (trimmed === '') {
      setRejected(true);
      return;
    }
    setRejected(false);
    onRecord({ reason: trimmed, actor, at: receptionNow });
  }

  return (
    <section
      data-testid="no-show-reason-form"
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        paddingBlock: tokens.spacing.md, paddingInline: tokens.spacing.md,
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        background: tokens.background,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h4 style={{ marginBlock: 0 }}>
        {schedulingText(locale, 'noShowTitle')}: <MixedScriptText text={patientName} lang={locale} />
      </h4>
      <FormField
        id="no-show-reason"
        label={schedulingText(locale, 'reasonLabel')}
        placeholder={schedulingText(locale, 'reasonPlaceholder')}
        value={reason}
        onChange={(value) => setReason(value)}
        error={rejected ? schedulingText(locale, 'reasonRequired') : ''}
      />
      <Button onClick={submit}>{schedulingText(locale, 'recordReason')}</Button>
    </section>
  );
}
