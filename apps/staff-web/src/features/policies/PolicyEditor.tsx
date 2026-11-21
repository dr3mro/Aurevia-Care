/** Shared change form for templates, dictionaries and release policies. */

import { useState } from 'react';
import { FieldErrorSummary } from '../admin/FieldErrorSummary';
import type { FieldErrorLink } from '../admin/FieldErrorSummary';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  EMPTY_POLICY_DRAFT,
  hasPolicyErrors,
  validatePolicyChange,
} from './domain';
import type { PolicyChangeDraft, PolicyErrors, PolicyField, ReleaseMode } from './domain';
import { policyText, releaseModeMessageKey } from './messages';
import type { PolicyMessageKey } from './messages';

/** Release modes in the order staff review them. */
const RELEASE_MODES: readonly ReleaseMode[] = ['immediate', 'timed', 'hold'];
/** Stable order for the validation summary. */
const FIELD_ORDER: readonly PolicyField[] = [
  'code',
  'value',
  'approver',
  'effectiveFrom',
  'reason',
  'expiry',
];
/** Label of every field the editor can refuse. */
const FIELD_LABEL: Record<PolicyField, PolicyMessageKey> = {
  code: 'fieldCode',
  value: 'proposedValue',
  approver: 'approverName',
  effectiveFrom: 'effectiveFrom',
  reason: 'holdReason',
  expiry: 'holdExpiry',
};

/** Props for one policy change form. */
export interface PolicyEditorProps {
  title: string;
  submitLabel: string;
  /** A clinical or market policy change needs a named approver (ADM-01). */
  requiresApprover: boolean;
  /** A dictionary field definition needs a stable code. */
  requiresCode: boolean;
  /** Show the immediate, timed and hold controls (XCH-06). */
  showReleaseModes: boolean;
  /** Send the validated change; return `false` to keep the typed draft. */
  onSubmit: (draft: PolicyChangeDraft) => Promise<boolean | void> | boolean | void;
}

/**
 * Collect one change, refuse it locally when a required governance field is
 * missing, and send it only after validation. A refused change never reaches
 * the API and never clears what the reviewer typed.
 */
export function PolicyEditor({
  title,
  submitLabel,
  requiresApprover,
  requiresCode,
  showReleaseModes,
  onSubmit,
}: PolicyEditorProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [draft, setDraft] = useState<PolicyChangeDraft>(EMPTY_POLICY_DRAFT);
  const [errors, setErrors] = useState<PolicyErrors>({});
  const [statusKey, setStatusKey] = useState<PolicyMessageKey | undefined>(undefined);

  function update(field: keyof PolicyChangeDraft, value: string) {
    setDraft((current) => ({ ...current, [field]: value }));
    setErrors({});
    setStatusKey(undefined);
  }

  function errorFor(field: PolicyField): string | undefined {
    const key = errors[field];
    return key === undefined ? undefined : policyText(locale, key);
  }

  /** Bind an inline error only when one exists (exact optional properties). */
  function errorProps(field: PolicyField): { error?: string } {
    const message = errorFor(field);
    return message === undefined ? {} : { error: message };
  }

  const links: FieldErrorLink[] = [];
  for (const field of FIELD_ORDER) {
    const key = errors[field];
    if (key !== undefined) {
      links.push({
        id: `policy-${field}`,
        label: policyText(locale, FIELD_LABEL[field]),
        message: policyText(locale, key),
      });
    }
  }

  async function submit() {
    const refused = validatePolicyChange(draft, requiresApprover, requiresCode);
    setErrors(refused);
    if (hasPolicyErrors(refused)) {
      setStatusKey('changeRefused');
      return;
    }
    const outcome = await onSubmit(draft);
    if (outcome === false) {
      setStatusKey('changeRefused');
      return;
    }
    setDraft(EMPTY_POLICY_DRAFT);
    setStatusKey('changeSaved');
  }

  const timedOrHold = draft.mode === 'timed' || draft.mode === 'hold';

  return (
    <section aria-label={title} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{title}</h3>
      <FieldErrorSummary title={policyText(locale, 'errorSummary')} errors={links} />
      <Form label={title} submitLabel={submitLabel} onSubmit={() => void submit()}>
        <div style={{ display: 'grid', gap: tokens.spacing.md }}>
          {requiresCode ? (
            <FormField
              id="policy-code"
              label={policyText(locale, 'fieldCode')}
              value={draft.code}
              onChange={(value) => update('code', value)}
              {...errorProps('code')}
            />
          ) : null}
          <FormField
            id="policy-value"
            label={policyText(locale, 'proposedValue')}
            value={draft.value}
            onChange={(value) => update('value', value)}
            {...errorProps('value')}
          />
          <FormField
            id="policy-approver"
            label={policyText(locale, 'approverName')}
            value={draft.approver}
            onChange={(value) => update('approver', value)}
            {...errorProps('approver')}
          />
          <p>{policyText(locale, 'approverNote')}</p>
          <FormField
            id="policy-effectiveFrom"
            label={policyText(locale, 'effectiveFrom')}
            value={draft.effectiveFrom}
            onChange={(value) => update('effectiveFrom', value)}
            placeholder={policyText(locale, 'datePlaceholder')}
            {...errorProps('effectiveFrom')}
          />
          {showReleaseModes ? (
            <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
              <legend>{policyText(locale, 'releaseMode')}</legend>
              {RELEASE_MODES.map((mode) => (
                <Button
                  key={mode}
                  pressed={draft.mode === mode}
                  variant={draft.mode === mode ? 'primary' : 'secondary'}
                  onClick={() => {
                    setDraft((current) => ({ ...current, mode }));
                    setErrors({});
                    setStatusKey(undefined);
                  }}
                >
                  {policyText(locale, releaseModeMessageKey[mode])}
                </Button>
              ))}
            </fieldset>
          ) : null}
          {draft.mode === 'hold' ? (
            <FormField
              id="policy-reason"
              label={policyText(locale, 'holdReason')}
              value={draft.reason}
              onChange={(value) => update('reason', value)}
              {...errorProps('reason')}
            />
          ) : null}
          {timedOrHold ? (
            <FormField
              id="policy-expiry"
              label={policyText(locale, 'holdExpiry')}
              value={draft.expiry}
              onChange={(value) => update('expiry', value)}
              placeholder={policyText(locale, 'datePlaceholder')}
              {...errorProps('expiry')}
            />
          ) : null}
        </div>
      </Form>
      <p role="status" data-testid="policy-editor-status">
        {statusKey === undefined ? '' : policyText(locale, statusKey)}
      </p>
    </section>
  );
}
