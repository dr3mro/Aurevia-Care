import { useState } from 'react';

import { EmptyState } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import type { ResultReport } from './domain';
import { syntheticReport, verifierSubject } from './fixtures';
import { ResultEntryScreen } from './ResultEntryScreen';

/** Props for the result-entry page, overridable so tests can drive both users. */
export interface ResultEntryPageProps {
  /** Subject of the signed-in verifier; defaults to the independent second user. */
  currentUser?: string | undefined;
  /** Report being entered; defaults to the synthetic fixture. */
  report?: ResultReport | undefined;
}

/**
 * Own draft entry and the verification outcome for one report.
 *
 * Drafts are local component state: a rejected verification keeps every typed
 * value, and a later edit clears a previous success so the status never claims
 * that the currently displayed values were verified (WEB-09).
 */
export function ResultEntryPage({
  currentUser = verifierSubject,
  report = syntheticReport,
}: ResultEntryPageProps) {
  const { locale } = useI18n();
  const [drafts, setDrafts] = useState<Record<string, string>>(() =>
    Object.fromEntries(report.rows.map((row) => [row.id, String(row.value)])),
  );
  const [verified, setVerified] = useState(false);

  if (report.rows.length === 0) {
    return <EmptyState />;
  }

  function handleValueChange(rowId: string, value: string) {
    setDrafts((current) => ({ ...current, [rowId]: value }));
    setVerified(false);
  }

  function handleVerify() {
    setVerified(true);
  }

  return (
    <ResultEntryScreen
      report={report}
      drafts={drafts}
      verifier={currentUser}
      locale={locale}
      verified={verified}
      onValueChange={handleValueChange}
      onVerify={handleVerify}
    />
  );
}
