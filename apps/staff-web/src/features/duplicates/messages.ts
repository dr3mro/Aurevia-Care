import { formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import type { ApprovalBlocker } from './domain';
import { fillMessagePlaceholders } from './messagePlaceholders';

/**
 * English and Arabic labels for duplicate review. The feature owns a small
 * parity-checked dictionary because the shared i18n bundle is outside this
 * story's scope. Machine codes from the API are translated here; response text
 * is never interpolated into a message.
 */
export const duplicatesKeys = [
  'title',
  'syntheticNotice',
  'worklistCaption',
  'colSurvivor',
  'colDuplicate',
  'colAction',
  'reviewAction',
  'reviewActionNamed',
  'back',
  'identitiesTitle',
  'survivor',
  'duplicate',
  'colField',
  'colSurvivorValue',
  'colDuplicateValue',
  'colMrnShort',
  'colDob',
  'colLanguage',
  'conflictsTitle',
  'noConflicts',
  'dependenciesTitle',
  'noDependencies',
  'depKindOrder',
  'depKindLink',
  'depKindGrant',
  'depKindOther',
  'depAmbiguous',
  'depResolved',
  'depUnresolved',
  'dryRunTitle',
  'reviewTitle',
  'reviewId',
  'openedBy',
  'openReview',
  'openFirst',
  'approvalTitle',
  'reasonLabel',
  'confirmTitle',
  'confirmHint',
  'alternateLabel',
  'alternateHint',
  'approve',
  'approved',
  'blockerSameReviewer',
  'blockerUnresolved',
  'blockerOpenFirst',
  'refusalSecondReviewer',
  'refusalUnresolved',
  'refusalGeneric',
  'reasonNotKnown',
  'reasonNotDisclosed',
  'reasonUnableToCollect',
  'dobUnknown',
] as const;

/** A single duplicate-review label key. */
export type DuplicatesMessageKey = (typeof duplicatesKeys)[number];

/** English and Arabic labels with identical key sets. */
export const duplicateMessages: Record<Locale, Record<DuplicatesMessageKey, string>> = {
  en: {
    title: 'Duplicate patient review',
    syntheticNotice: 'Synthetic data only. Reviews are read from and written to the connected API.',
    worklistCaption: 'Duplicate review worklist',
    colSurvivor: 'Surviving identity',
    colDuplicate: 'Duplicate identity',
    colAction: 'Action',
    reviewAction: 'Review pair',
    reviewActionNamed: 'Review pair {survivor} and {duplicate}',
    back: 'Back to worklist',
    identitiesTitle: 'Side-by-side identities',
    survivor: 'Surviving record',
    duplicate: 'Duplicate record',
    colField: 'Field',
    colSurvivorValue: 'Surviving value',
    colDuplicateValue: 'Duplicate value',
    colMrnShort: 'MRN',
    colDob: 'Date of birth',
    colLanguage: 'Language',
    conflictsTitle: 'Conflicting demographics',
    noConflicts: 'No conflicting demographics recorded.',
    dependenciesTitle: 'Dependencies',
    noDependencies: 'No dependent orders, links or grants.',
    depKindOrder: 'Order',
    depKindLink: 'Patient link',
    depKindGrant: 'Grant',
    depKindOther: 'Reference',
    depAmbiguous: 'Ambiguous',
    depResolved: 'Resolved',
    depUnresolved: 'Unresolved',
    dryRunTitle: 'Dry run (no data changed)',
    reviewTitle: 'Review detail',
    reviewId: 'Review ID',
    openedBy: 'Opened by',
    openReview: 'Open review',
    openFirst: 'Open a review before approving.',
    approvalTitle: 'Second-reviewer approval',
    reasonLabel: 'Reason for consolidation',
    confirmTitle: 'Confirm the two identities',
    confirmHint:
      'Tick both issuer-scoped identifiers, or give one approved alternate-process code (not both).',
    alternateLabel: 'Approved alternate-process code',
    alternateHint: 'Lowercase code, 2-32 letters or underscores.',
    approve: 'Approve consolidation',
    approved: 'Consolidation approved and chart visibility moved.',
    blockerSameReviewer:
      'The reviewer who opened this request cannot approve it. A different authorized reviewer must approve.',
    blockerUnresolved:
      'Unresolved dependencies block approval. Resolve the ambiguous orders, links or grants first.',
    blockerOpenFirst: 'Open a review before approving.',
    refusalSecondReviewer: 'Refused: a different authorized reviewer must approve this request.',
    refusalUnresolved: 'Refused: unresolved dependencies must be resolved before consolidation.',
    refusalGeneric: 'The consolidation could not be completed. Review the state and try again.',
    reasonNotKnown: 'Not known',
    reasonNotDisclosed: 'Not disclosed',
    reasonUnableToCollect: 'Unable to collect',
    dobUnknown: 'Date of birth not recorded',
  },
  ar: {
    title: 'مراجعة المرضى المكررين',
    syntheticNotice: 'بيانات توضيحية فقط؛ تُقرأ المراجعات من الخدمة المتصلة ويُكتب إليها.',
    worklistCaption: 'قائمة مراجعة التكرارات',
    colSurvivor: 'الهوية الباقية',
    colDuplicate: 'الهوية المكررة',
    colAction: 'الإجراء',
    reviewAction: 'مراجعة الزوج',
    reviewActionNamed: 'مراجعة الزوج {survivor} و{duplicate}',
    back: 'العودة إلى القائمة',
    identitiesTitle: 'الهويات جنبًا إلى جنب',
    survivor: 'السجل الباقي',
    duplicate: 'السجل المكرر',
    colField: 'الحقل',
    colSurvivorValue: 'قيمة السجل الباقي',
    colDuplicateValue: 'قيمة السجل المكرر',
    colMrnShort: 'رقم الملف',
    colDob: 'تاريخ الميلاد',
    colLanguage: 'اللغة',
    conflictsTitle: 'البيانات الديموغرافية المتعارضة',
    noConflicts: 'لا توجد بيانات ديموغرافية متعارضة مسجّلة.',
    dependenciesTitle: 'التبعيات',
    noDependencies: 'لا توجد أوامر أو روابط أو منح تابعة.',
    depKindOrder: 'طلب',
    depKindLink: 'رابط مريض',
    depKindGrant: 'منحة',
    depKindOther: 'مرجع',
    depAmbiguous: 'غامض',
    depResolved: 'تمت التسوية',
    depUnresolved: 'غير محسوم',
    dryRunTitle: 'تجربة جافة (لم يتغيّر أي بيانات)',
    reviewTitle: 'تفاصيل المراجعة',
    reviewId: 'معرّف المراجعة',
    openedBy: 'فتحها',
    openReview: 'فتح مراجعة',
    openFirst: 'افتح مراجعة قبل الموافقة.',
    approvalTitle: 'موافقة المراجع الثاني',
    reasonLabel: 'سبب الدمج',
    confirmTitle: 'أكّد الهويتين',
    confirmHint:
      'علّم المعرّفين المرتبطين بالجهة المُصدرة، أو أدخل رمز إجراء بديل معتمدًا واحدًا (ليس كلاهما معًا).',
    alternateLabel: 'رمز الإجراء البديل المعتمد',
    alternateHint: 'رمز صغير من ٢ إلى ٣٢ حرفًا أو شرطة سفلية.',
    approve: 'الموافقة على الدمج',
    approved: 'تمت الموافقة على الدمج ونقل ظهور الملف الطبي.',
    blockerSameReviewer:
      'لا يمكن للمراجع الذي فتح الطلب أن يوافق عليه. يجب أن يوافق مراجع مخول آخر.',
    blockerUnresolved:
      'التبعيات غير المحسومة تمنع الموافقة. اسوِ الطلبات أو الروابط أو المنح الغامضة أولًا.',
    blockerOpenFirst: 'افتح مراجعة قبل الموافقة.',
    refusalSecondReviewer: 'رُفض الطلب: يجب أن يوافق مراجع مخول آخر على هذا الطلب.',
    refusalUnresolved: 'رُفض الطلب: يجب تسوية التبعيات غير المحسومة قبل الدمج.',
    refusalGeneric: 'تعذّر إكمال الدمج. راجع الحالة ثم أعد المحاولة.',
    reasonNotKnown: 'غير معروف',
    reasonNotDisclosed: 'لم يُفصح عنه',
    reasonUnableToCollect: 'تعذّر الجمع',
    dobUnknown: 'لم يُسجّل تاريخ الميلاد',
  },
};

/** Look up one duplicate-review label for the active locale. */
export function duplicatesText(locale: Locale, key: DuplicatesMessageKey): string {
  return duplicateMessages[locale][key];
}

/** Fill named placeholders, leaving unknown ones visible rather than blank. */
export function duplicatesMessage(
  locale: Locale,
  key: DuplicatesMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return fillMessagePlaceholders(duplicatesText(locale, key), values);
}

/** Map a dependency kind to its localized label; unknown kinds stay generic. */
export function dependencyKindKey(kind: string): DuplicatesMessageKey {
  if (kind === 'ORDER') {
    return 'depKindOrder';
  }
  if (kind === 'PATIENT_LINK') {
    return 'depKindLink';
  }
  if (kind === 'GRANT') {
    return 'depKindGrant';
  }
  return 'depKindOther';
}

/** Map a dependency's resolved/ambiguous flags to its localized state label. */
export function dependencyStateKey(resolved: boolean, ambiguous: boolean): DuplicatesMessageKey {
  if (resolved) {
    return 'depResolved';
  }
  return ambiguous ? 'depAmbiguous' : 'depUnresolved';
}

/** Map a client-side approval blocker to the message shown to the reviewer. */
export function blockerMessageKey(blocker: ApprovalBlocker): DuplicatesMessageKey {
  if (blocker === 'same-reviewer') {
    return 'blockerSameReviewer';
  }
  if (blocker === 'unresolved-dependencies') {
    return 'blockerUnresolved';
  }
  return 'blockerOpenFirst';
}

/** Map a machine-readable server code to a refusal, never from response text. */
export function refusalKeyForCode(code: string | undefined): DuplicatesMessageKey {
  if (code === 'SECOND_REVIEWER_REQUIRED') {
    return 'refusalSecondReviewer';
  }
  if (code === 'UNRESOLVED_DEPENDENCY') {
    return 'refusalUnresolved';
  }
  return 'refusalGeneric';
}

/** Map a coded unknown-date reason, falling back for a missing or unknown code. */
export function dobReasonKey(code: string | null): DuplicatesMessageKey {
  if (code === 'not_known') {
    return 'reasonNotKnown';
  }
  if (code === 'not_disclosed') {
    return 'reasonNotDisclosed';
  }
  if (code === 'unable_to_collect') {
    return 'reasonUnableToCollect';
  }
  return 'dobUnknown';
}

/** Format a civil date of birth, or its coded unknown reason, for display. */
export function dobText(dob: string | null, reason: string | null, locale: Locale): string {
  if (dob !== null) {
    return formatCivilDate(dob, locale);
  }
  return duplicatesText(locale, dobReasonKey(reason));
}
