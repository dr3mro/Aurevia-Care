/** Set and review patient release policies: immediate, timed or held (XCH-06). */

import { MixedScriptText } from '../../components/MixedScriptText';
import { formatCivilDate } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { type PolicyChangeDraft, type PolicyVersion, releaseAccess } from './domain';
import { policyText, releaseAccessMessageKey } from './messages';
import { ApprovalActions, VersionHistory } from './VersionHistory';
import { PolicyEditor } from './PolicyEditor';

/** Props for the patient release policy screen. */
export interface ReleasePolicyScreenProps {
  organizationId: string;
  versions: readonly PolicyVersion[];
  /** Approved version in force on the anchor date, if any. */
  currentVersion: PolicyVersion | undefined;
  anchorCivilDate: string;
  effectiveVersionNumber: number | undefined;
  onSet: (draft: PolicyChangeDraft) => Promise<boolean | void> | boolean | void;
  onApprove: (version: number) => void;
}

/** Show current patient access and an explicit, approver-gated policy form. */
export function ReleasePolicyScreen({
  organizationId,
  versions,
  currentVersion,
  anchorCivilDate,
  effectiveVersionNumber,
  onSet,
  onApprove,
}: ReleasePolicyScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const nextScheduled = [...versions]
    .filter(
      (row) =>
        row.organizationId === organizationId &&
        row.approval === 'approved' &&
        releaseAccess(row, anchorCivilDate) === 'scheduled',
    )
    .sort((left, right) => left.effectiveFrom.localeCompare(right.effectiveFrom))[0];
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <section aria-label={policyText(locale, 'currentAccess')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{policyText(locale, 'currentAccess')}</h3>
        {currentVersion === undefined ? (
          <p>{policyText(locale, 'noCurrentPolicy')}</p>
        ) : (
          <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <p style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
              <span data-state={releaseAccess(currentVersion, anchorCivilDate)}>
                {policyText(locale, releaseAccessMessageKey[releaseAccess(currentVersion, anchorCivilDate)])}
              </span>
              <MixedScriptText text={currentVersion.value} lang="en" />
            </p>
            {currentVersion.reason === undefined ? null : (
              <p style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
                {policyText(locale, 'reasonLabel')}
                <MixedScriptText text={currentVersion.reason} lang="en" />
              </p>
            )}
            {currentVersion.expiry === undefined ? null : (
              <p style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
                {policyText(locale, 'reviewLabel')}
                <time dateTime={currentVersion.expiry}>
                  {formatCivilDate(currentVersion.expiry, locale)}
                </time>
              </p>
            )}
          </div>
        )}
        {nextScheduled === undefined ? null : (
          <p style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
            {policyText(locale, 'nextScheduledLabel')}
            <time dateTime={nextScheduled.effectiveFrom}>
              {formatCivilDate(nextScheduled.effectiveFrom, locale)}
            </time>
          </p>
        )}
      </section>
      <VersionHistory
        title={policyText(locale, 'history')}
        emptyMessage={policyText(locale, 'noVersions')}
        versions={versions}
        effectiveVersionNumber={effectiveVersionNumber}
      />
      <ApprovalActions versions={versions} onApprove={onApprove} />
      <PolicyEditor
        title={policyText(locale, 'setPolicy')}
        submitLabel={policyText(locale, 'setPolicy')}
        requiresApprover
        requiresCode={false}
        showReleaseModes
        onSubmit={onSet}
      />
    </div>
  );
}
