/** Append-only version history shared by all three policy screens. */

import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { ApprovalState, PolicyVersion } from './domain';
import { approvalMessageKey, policyMessage, policyText } from './messages';

/** A state label carried by a shape-distinct glyph, never by colour alone. */
function ApprovalBadge({ state, label }: { state: ApprovalState; label: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const glyph = state === 'approved' ? '✓' : '○';
  return (
    <span data-state={state} style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}>
      <span aria-hidden="true">{glyph}</span>
      <MixedScriptText text={label} lang={locale} />
    </span>
  );
}

/** Props for the pending-version approval list. */
export interface ApprovalActionsProps {
  versions: readonly PolicyVersion[];
  onApprove: (version: number) => void;
}

/** Offer an explicit approval trigger for each pending immutable version. */
export function ApprovalActions({ versions, onApprove }: ApprovalActionsProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const pending = versions.filter((row) => row.approval === 'pending');
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
      {pending.map((row) => (
        <Button key={`${row.organizationId}:${row.version}`} onClick={() => onApprove(row.version)}>
          {policyMessage(locale, 'approveVersion', { version: String(row.version) })}
        </Button>
      ))}
    </div>
  );
}

/** Props for the immutable version table. */
export interface VersionHistoryProps {
  /** Localized section and table caption. */
  title: string;
  /** Localized empty-state sentence. */
  emptyMessage: string;
  versions: readonly PolicyVersion[];
  /** Version currently in force, or `undefined` when nothing is effective. */
  effectiveVersionNumber: number | undefined;
}

/**
 * Show every immutable version of one business key, newest first. The table
 * keeps the approval, named approver and effective date beside each version so
 * a reviewer can interpret historical field meanings (CLN-04, ADM-01).
 */
export function VersionHistory({
  title,
  emptyMessage,
  versions,
  effectiveVersionNumber,
}: VersionHistoryProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section aria-label={title} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{title}</h3>
      <div role="region" aria-label={title} tabIndex={0} style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{title}</caption>
          <thead>
            <tr>
              <th scope="col">{policyText(locale, 'version')}</th>
              <th scope="col">{policyText(locale, 'value')}</th>
              <th scope="col">{policyText(locale, 'effectiveFrom')}</th>
              <th scope="col">{policyText(locale, 'approvalState')}</th>
              <th scope="col">{policyText(locale, 'recordedApprover')}</th>
            </tr>
          </thead>
          <tbody>
            {versions.length === 0 ? (
              <tr>
                <td colSpan={5}>{emptyMessage}</td>
              </tr>
            ) : (
              versions.map((row) => (
                <tr key={`${row.organizationId}:${row.version}`}>
                  <th scope="row">{row.version}</th>
                  <td>
                    <MixedScriptText text={row.value} lang="en" />
                    {row.proposedBy === undefined && row.reason === undefined && row.expiry === undefined ? null : (
                      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
                        {row.proposedBy === undefined ? null : (
                          <span style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
                            {policyText(locale, 'proposedBy')}
                            <MixedScriptText text={row.proposedBy} lang="en" />
                          </span>
                        )}
                        {row.reason === undefined ? null : (
                          <span style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
                            {policyText(locale, 'reasonLabel')}
                            <MixedScriptText text={row.reason} lang="en" />
                          </span>
                        )}
                        {row.expiry === undefined ? null : (
                          <span style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
                            {policyText(locale, 'reviewLabel')}
                            <time dateTime={row.expiry}>{formatCivilDate(row.expiry, locale)}</time>
                          </span>
                        )}
                      </div>
                    )}
                  </td>
                  <td>
                    {row.effectiveFrom === '' ? (
                      <span>{policyText(locale, 'notApplicable')}</span>
                    ) : (
                      <time dateTime={row.effectiveFrom}>
                        {formatCivilDate(row.effectiveFrom, locale)}
                      </time>
                    )}
                  </td>
                  <td>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}>
                      <ApprovalBadge
                        state={row.approval}
                        label={policyText(locale, approvalMessageKey[row.approval])}
                      />
                      {effectiveVersionNumber === row.version ? (
                        <span data-state="effective">{policyText(locale, 'effectiveBadge')}</span>
                      ) : null}
                    </span>
                  </td>
                  <td>
                    <MixedScriptText
                      text={row.approver ?? policyText(locale, 'unknown')}
                      lang={row.approver === undefined ? locale : 'en'}
                    />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
