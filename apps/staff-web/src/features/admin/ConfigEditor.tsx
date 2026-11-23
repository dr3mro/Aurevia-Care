import { useState } from 'react';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../../theme/useBreakpoint';
import { AdminTableRegion } from './AdminTableRegion';
import type { ConfigurationDraft, ConfigurationVersion } from './domain';
import { effectiveVersion, isValidCivilDate, versionsFor } from './domain';
import { adminText } from './messages';
import { StateBadge } from './StateBadge';

/** Props for the tenant-scoped configuration version editor. */
export interface ConfigurationEditorProps {
  entries: readonly ConfigurationVersion[];
  organizationId: string;
  configKey: string;
  anchorCivilDate: string;
  /**
   * Propose one version. Return `false` (or a promise resolving to `false`)
   * when the command failed so the typed draft is kept for the conflict state.
   */
  onPropose: (draft: ConfigurationDraft) => unknown;
  /** When supplied, pending rows offer an approve trigger; the owner confirms. */
  onApprove?: (version: number) => void;
}

interface ConfigVersionRowProps {
  entry: ConfigurationVersion;
  isEffective: boolean;
  onApprove: ((version: number) => void) | undefined;
}

function ConfigVersionRow({ entry, isEffective, onApprove }: ConfigVersionRowProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);
  return (
    <tr>
      <th scope="row">{entry.version}</th>
      <td>
        <MixedScriptText text={entry.value} lang="en" />
      </td>
      <td>
        {entry.effectiveFrom === '' ? (
          <span>{text('notApplicable')}</span>
        ) : (
          <time dateTime={entry.effectiveFrom}>{formatCivilDate(entry.effectiveFrom, locale)}</time>
        )}
      </td>
      <td>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}>
          {entry.approved ? (
            <StateBadge glyph="✓" label={text('approved')} tone="approved" />
          ) : (
            <>
              <StateBadge glyph="○" label={text('pending')} tone="pending" />
              {onApprove === undefined ? null : (
                <Button variant="secondary" onClick={() => onApprove(entry.version)}>
                  {text('approve')}
                </Button>
              )}
            </>
          )}
          {isEffective ? (
            <StateBadge glyph="*" label={text('effectiveBadge')} tone="effective" />
          ) : null}
        </span>
      </td>
      <td>
        <MixedScriptText
          text={entry.approver ?? text('unknown')}
          lang={(entry.approver ?? '') === '' ? locale : 'en'}
        />
      </td>
    </tr>
  );
}

/**
 * Show versioned, effective-dated configuration and collect a local draft.
 *
 * Layout is presentation only and keeps one DOM and accessibility tree at every
 * width (WEB-07, UX-01): the shared `stack` class lays the heading, prose, table
 * and form out in one column, the table keeps all five clinical columns inside
 * its labelled scroll region, and no field or column is hidden. Styling uses
 * only logical properties so the same markup mirrors under right-to-left;
 * `data-breakpoint` and `data-coarse-pointer` expose the resolved layout to
 * structural tests without changing the data, actions, text or public props.
 */
export function ConfigEditor({
  entries,
  organizationId,
  configKey,
  anchorCivilDate,
  onPropose,
  onApprove,
}: ConfigurationEditorProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  // Presentation-only observation seams; never used to add, remove or reorder
  // a clinical column, field or action at a breakpoint.
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const [value, setValue] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [invalidField, setInvalidField] = useState<'value' | 'effectiveFrom' | null>(null);
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);

  const versions = versionsFor(entries, organizationId, configKey);
  const effective = effectiveVersion(entries, organizationId, configKey, anchorCivilDate);

  function submit() {
    const proposedValue = value.trim();
    const proposedDate = effectiveFrom.trim();
    if (proposedValue.length === 0) {
      setInvalidField('value');
      return;
    }
    if (!isValidCivilDate(proposedDate)) {
      setInvalidField('effectiveFrom');
      return;
    }
    setInvalidField(null);
    const outcome = onPropose({ value: proposedValue, effectiveFrom: proposedDate });
    if (outcome instanceof Promise) {
      // A rejected command resolves to false; the draft stays for comparison.
      void outcome.then((accepted) => {
        if (accepted !== false) {
          setValue('');
          setEffectiveFrom('');
        }
      });
    } else if (outcome !== false) {
      setValue('');
      setEffectiveFrom('');
    }
  }

  return (
    <section
      aria-label={text('configurationEditor')}
      className="stack"
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      style={{ gap: tokens.spacing.md }}
    >
      <h3>{text('configurationEditor')}</h3>
      <p>
        {text('configKey')}: <MixedScriptText text={configKey} lang="en" />
      </p>
      <AdminTableRegion label={text('configurationVersions')}>
        <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }}>
          <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
            {text('configurationVersions')}
          </caption>
          <thead>
            <tr>
              <th scope="col">{text('version')}</th>
              <th scope="col">{text('configuredValue')}</th>
              <th scope="col">{text('effectiveFrom')}</th>
              <th scope="col">{text('approvalState')}</th>
              <th scope="col">{text('approver')}</th>
            </tr>
          </thead>
          <tbody>
            {versions.length === 0 ? (
              <tr>
                <td colSpan={5}>{text('noVersions')}</td>
              </tr>
            ) : (
              versions.map((entry) => (
                <ConfigVersionRow
                  key={`${entry.organizationId}:${entry.version}`}
                  entry={entry}
                  isEffective={entry.version === effective?.version}
                  onApprove={onApprove}
                />
              ))
            )}
          </tbody>
        </table>
      </AdminTableRegion>
      <Form label={text('configurationEditor')} submitLabel={text('propose')} onSubmit={submit}>
        <FormField
          id="admin-config-value"
          label={text('proposedValue')}
          value={value}
          onChange={(next) => {
            setValue(next);
            setInvalidField(null);
          }}
          error={invalidField === 'value' ? text('invalidDraft') : ''}
        />
        <FormField
          id="admin-config-effective"
          label={text('effectiveFrom')}
          value={effectiveFrom}
          onChange={(next) => {
            setEffectiveFrom(next);
            setInvalidField(null);
          }}
          placeholder="2026-07-01"
          error={invalidField === 'effectiveFrom' ? text('invalidDraft') : ''}
        />
      </Form>
    </section>
  );
}
