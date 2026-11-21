import { useState } from 'react';

import { EmptyState } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import type { CorrectionValues, ReleaseState, ReportRevision, WithdrawalDetail } from './domain';
import {
  correctorSubject,
  correctedRevision,
  originalRevision,
  proposedChange,
  WITHDRAWN_AT,
} from './fixtures';
import { releaseMessage } from './labReleaseMessages';
import { ReleaseScreen } from './ReleaseScreen';

/** Props for the release page, overridable so tests can drive each state. */
export interface ReleasePageProps {
  /** Original revision; defaults to the synthetic fixture. */
  before?: ReportRevision | undefined;
  /** Proposed corrected revision; defaults to the synthetic fixture. */
  after?: ReportRevision | undefined;
  /** Initial release state; defaults to an unreleased final report. */
  state?: ReleaseState | undefined;
  /** Existing withdrawal record, when the report is already withdrawn. */
  withdrawal?: WithdrawalDetail | null | undefined;
  /** Subject recording the correction or withdrawal. */
  actor?: string | undefined;
  /** UTC instant used for a withdrawal recorded on this page. */
  withdrawalAt?: string | undefined;
}

/**
 * Own the correction and withdrawal drafts and the resulting release state.
 *
 * Drafts are local component state: a disabled action keeps every typed word,
 * and editing after a successful correction clears the success so the status
 * never claims that the currently displayed text was recorded (WEB-09).
 */
export function ReleasePage({
  before = originalRevision,
  after = correctedRevision,
  state = 'final',
  withdrawal = null,
  actor = correctorSubject,
  withdrawalAt = WITHDRAWN_AT,
}: ReleasePageProps) {
  const { locale } = useI18n();
  const [releaseState, setReleaseState] = useState<ReleaseState>(state);
  const [correction, setCorrection] = useState<CorrectionValues>(() => ({
    changeSummary: proposedChange,
    reason: '',
  }));
  const [withdrawReason, setWithdrawReason] = useState('');
  const [currentWithdrawal, setCurrentWithdrawal] = useState<WithdrawalDetail | null>(withdrawal);
  const [correctionNotice, setCorrectionNotice] = useState<string | null>(null);

  if (after.observations.length === 0) {
    return <EmptyState />;
  }

  function handleCorrectionChange(values: CorrectionValues) {
    setCorrection(values);
    setCorrectionNotice(null);
  }

  function handleCorrect() {
    setReleaseState('corrected');
    setCorrectionNotice(
      releaseMessage(locale, 'correctedStatus', { subject: actor, reason: correction.reason }),
    );
  }

  function handleWithdraw() {
    setReleaseState('withdrawn');
    setCurrentWithdrawal({ reason: withdrawReason, actor, recordedAt: withdrawalAt });
  }

  return (
    <ReleaseScreen
      before={before}
      after={after}
      state={releaseState}
      withdrawal={currentWithdrawal}
      locale={locale}
      correction={correction}
      withdrawReason={withdrawReason}
      correctionNotice={correctionNotice}
      onCorrectionChange={handleCorrectionChange}
      onWithdrawReasonChange={setWithdrawReason}
      onCorrect={handleCorrect}
      onWithdraw={handleWithdraw}
    />
  );
}
