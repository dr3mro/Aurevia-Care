import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import type { DifferenceKind, ReleaseState, ReleaseVisibility } from './domain';

/**
 * English and Arabic labels for report release, correction and withdrawal.
 *
 * The file is deliberately not named `messages.ts`: the repository-wide message
 * parity registry discovers that name and is owned outside this feature's scope.
 * This bundle enforces its own key, placeholder and script parity in
 * `labReleaseMessages.test.ts`.
 */

/** Every label key used by the release screen, in both locales. */
export const releaseKeys = [
  'title',
  'syntheticNotice',
  'comparisonCaption',
  'colTest',
  'colBefore',
  'colAfter',
  'colChange',
  'changeChanged',
  'changeUnchanged',
  'changeAdded',
  'changeRemoved',
  'noDifferences',
  'valueNotPresent',
  'correctionRegion',
  'changeLabel',
  'reasonLabel',
  'reasonRequiredError',
  'correctButton',
  'correctedStatus',
  'withdrawalRegion',
  'withdrawalConsequence',
  'withdrawReasonLabel',
  'withdrawReasonError',
  'withdrawButton',
  'withdrawnHeading',
  'withdrawnDetail',
  'withdrawnReason',
  'withdrawnActor',
  'withdrawnAt',
  'statusRegion',
  'statusState',
  'statusClinician',
  'statusPatient',
  'visibilityReleased',
  'visibilityWithheld',
  'statePreliminary',
  'stateFinal',
  'stateCorrected',
  'stateWithdrawn',
] as const;

/** One release label key. */
export type ReleaseMessageKey = (typeof releaseKeys)[number];

/** English and Arabic labels with identical key sets. */
export const releaseMessages: Record<Locale, Record<ReleaseMessageKey, string>> = {
  en: {
    title: 'Laboratory report release and corrections',
    syntheticNotice: 'Synthetic data only. No patient record or analyser is connected.',
    comparisonCaption: 'Side-by-side revision comparison for accession {accession}',
    colTest: 'Test',
    colBefore: 'Original (v{version})',
    colAfter: 'Corrected (v{version})',
    colChange: 'Change',
    changeChanged: 'Changed',
    changeUnchanged: 'Unchanged',
    changeAdded: 'Added',
    changeRemoved: 'Removed',
    noDifferences: 'No observations changed between these revisions.',
    valueNotPresent: 'Not present in this revision',
    correctionRegion: 'Correction',
    changeLabel: 'What changed',
    reasonLabel: 'Reason for correction (required)',
    reasonRequiredError: 'State why this correction is needed before recording it.',
    correctButton: 'Record correction',
    correctedStatus: 'Corrected by {subject}. Reason: {reason}',
    withdrawalRegion: 'Withdrawal',
    withdrawalConsequence:
      'Withdrawing removes the report from both clinician and patient view and records an entered-in-error state.',
    withdrawReasonLabel: 'Reason for withdrawal (required)',
    withdrawReasonError: 'State why this report is being withdrawn.',
    withdrawButton: 'Withdraw report',
    withdrawnHeading: 'Report withdrawn',
    withdrawnDetail: 'This report was withdrawn and entered in error.',
    withdrawnReason: 'Reason',
    withdrawnActor: 'Withdrawn by',
    withdrawnAt: 'Recorded at',
    statusRegion: 'Release status',
    statusState: 'Report state',
    statusClinician: 'Clinician',
    statusPatient: 'Patient',
    visibilityReleased: 'Released',
    visibilityWithheld: 'Not visible',
    statePreliminary: 'Preliminary',
    stateFinal: 'Final',
    stateCorrected: 'Corrected',
    stateWithdrawn: 'Withdrawn',
  },
  ar: {
    title: 'إصدار تقارير المختبر والتصحيحات',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يوجد سجل مريض أو جهاز تحليل متصل.',
    comparisonCaption: 'مقارنة المراجعات جنبًا إلى جنب للوصول {accession}',
    colTest: 'الاختبار',
    colBefore: 'الأصلي (إصدار {version})',
    colAfter: 'المُصحّح (إصدار {version})',
    colChange: 'التغيّر',
    changeChanged: 'مُغيّر',
    changeUnchanged: 'بدون تغيّر',
    changeAdded: 'مُضاف',
    changeRemoved: 'مُحذوف',
    noDifferences: 'لم تتغيّر أي ملاحظة بين هاتين المراجعتين.',
    valueNotPresent: 'غير موجود في هذه المراجعة',
    correctionRegion: 'التصحيح',
    changeLabel: 'ما الذي تغيّر',
    reasonLabel: 'سبب التصحيح (مطلوب)',
    reasonRequiredError: 'اذكر سبب التصحيح قبل تسجيله.',
    correctButton: 'تسجيل التصحيح',
    correctedStatus: 'تم التصحيح بواسطة {subject}. السبب: {reason}',
    withdrawalRegion: 'السحب',
    withdrawalConsequence: 'يؤدي السحب إلى إخفاء التقرير عن الطبيب والمريض وتسجيل حالة خطأ طبي.',
    withdrawReasonLabel: 'سبب السحب (مطلوب)',
    withdrawReasonError: 'اذكر سبب سحب هذا التقرير.',
    withdrawButton: 'سحب التقرير',
    withdrawnHeading: 'تم سحب التقرير',
    withdrawnDetail: 'تم سحب هذا التقرير وإدخاله في حالة خطأ.',
    withdrawnReason: 'السبب',
    withdrawnActor: 'تم السحب بواسطة',
    withdrawnAt: 'وقت التسجيل',
    statusRegion: 'حالة الإصدار',
    statusState: 'حالة التقرير',
    statusClinician: 'الطبيب',
    statusPatient: 'المريض',
    visibilityReleased: 'تم الإصدار',
    visibilityWithheld: 'غير ظاهر',
    statePreliminary: 'أولي',
    stateFinal: 'نهائي',
    stateCorrected: 'مُصحّح',
    stateWithdrawn: 'مُسحوب',
  },
};

/** Look up one release label for the active locale. */
export function releaseText(locale: Locale, key: ReleaseMessageKey): string {
  return releaseMessages[locale][key];
}

/** Fill named placeholders, leaving unknown ones visible rather than blank. */
export function releaseMessage(
  locale: Locale,
  key: ReleaseMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return releaseText(locale, key).replace(
    /\{(\w+)\}/g,
    (whole, name: string) => values[name] ?? whole,
  );
}

/** Difference-kind label keys, indexed by kind. */
const DIFFERENCE_KEYS: Readonly<Record<DifferenceKind, ReleaseMessageKey>> = {
  changed: 'changeChanged',
  unchanged: 'changeUnchanged',
  added: 'changeAdded',
  removed: 'changeRemoved',
};

/** Resolve the word for one kind of difference. */
export function differenceKey(kind: DifferenceKind): ReleaseMessageKey {
  return DIFFERENCE_KEYS[kind];
}

/** Report-state label keys, indexed by state. */
const STATE_KEYS: Readonly<Record<ReleaseState, ReleaseMessageKey>> = {
  preliminary: 'statePreliminary',
  final: 'stateFinal',
  corrected: 'stateCorrected',
  withdrawn: 'stateWithdrawn',
};

/** Resolve the word for a report state. */
export function stateKey(state: ReleaseState): ReleaseMessageKey {
  return STATE_KEYS[state];
}

/** Visibility label keys, indexed by visibility. */
const VISIBILITY_KEYS: Readonly<Record<ReleaseVisibility, ReleaseMessageKey>> = {
  released: 'visibilityReleased',
  withheld: 'visibilityWithheld',
};

/** Resolve the word for an audience's visibility. */
export function visibilityKey(visibility: ReleaseVisibility): ReleaseMessageKey {
  return VISIBILITY_KEYS[visibility];
}

/** Format a numeric observation with Latin digits, keeping unit and value together. */
export function formatValue(value: number, unit: string, locale: Locale): string {
  const number = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 3,
    numberingSystem: 'latn',
  }).format(value);
  return `${number}\u00a0${unit}`;
}

/** Format a recorded withdrawal instant at UTC with Latin digits. */
export function formatWithdrawalTime(iso: string, locale: Locale): string {
  return formatInstant(iso, 'UTC', locale);
}
