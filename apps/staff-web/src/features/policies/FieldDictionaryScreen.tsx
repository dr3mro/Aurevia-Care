/** Review field meanings by version and define a governed new field (CLN-04). */

import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { PolicyChangeDraft, PolicyVersion } from './domain';
import { policyText } from './messages';
import { VersionHistory } from './VersionHistory';
import { PolicyEditor } from './PolicyEditor';

/** Props for the field dictionary review screen. */
export interface FieldDictionaryScreenProps {
  versions: readonly PolicyVersion[];
  effectiveVersionNumber: number | undefined;
  onDefine: (draft: PolicyChangeDraft) => Promise<boolean | void> | boolean | void;
}

/**
 * Show historical field meanings beside a code-and-meaning definition form.
 * The catalog list endpoint exposes effective approved entries, so this screen
 * reviews history and appends pending versions; approval happens in the
 * configuration history screen.
 */
export function FieldDictionaryScreen({
  versions,
  effectiveVersionNumber,
  onDefine,
}: FieldDictionaryScreenProps) {
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
      <PolicyEditor
        title={policyText(locale, 'defineField')}
        submitLabel={policyText(locale, 'defineField')}
        requiresApprover
        requiresCode
        showReleaseModes={false}
        onSubmit={onDefine}
      />
    </div>
  );
}
