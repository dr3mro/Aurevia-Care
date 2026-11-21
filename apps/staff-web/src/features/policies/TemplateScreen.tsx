/** Review approved template versions and propose a governed new one (CLN-04). */

import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { PolicyChangeDraft, PolicyVersion } from './domain';
import { policyText } from './messages';
import { ApprovalActions, VersionHistory } from './VersionHistory';
import { PolicyEditor } from './PolicyEditor';

/** Props for the template version review screen. */
export interface TemplateScreenProps {
  versions: readonly PolicyVersion[];
  effectiveVersionNumber: number | undefined;
  onPropose: (draft: PolicyChangeDraft) => Promise<boolean | void> | boolean | void;
  onApprove: (version: number) => void;
}

/** Show an append-only template history beside a named-approval change form. */
export function TemplateScreen({
  versions,
  effectiveVersionNumber,
  onPropose,
  onApprove,
}: TemplateScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <VersionHistory
        title={policyText(locale, 'history')}
        emptyMessage={policyText(locale, 'noVersions')}
        versions={versions}
        effectiveVersionNumber={effectiveVersionNumber}
      />
      <ApprovalActions versions={versions} onApprove={onApprove} />
      <PolicyEditor
        title={policyText(locale, 'proposeVersion')}
        submitLabel={policyText(locale, 'proposeVersion')}
        requiresApprover
        requiresCode={false}
        showReleaseModes={false}
        onSubmit={onPropose}
      />
    </div>
  );
}
