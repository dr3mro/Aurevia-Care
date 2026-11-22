import type { Locale } from '../../i18n/messages';
import type { ClarificationParty, SubstitutionReason, SubstitutionState } from './types';

/**
 * Prescriber-only label keys. The shared bundle is outside this story's scope,
 * so the feature owns one en/ar dictionary with identical keys and a parity
 * test (UX-01). Every visible string in this feature comes from this dictionary.
 */
export const prescriberKeys = [
  'timelineTitle',
  'factId',
  'factType',
  'factDetail',
  'factActor',
  'factOccurredAt',
  'dispensingEvidence',
  'dispensedBy',
  'dispensedAt',
  'handedOver',
  'notRecorded',
  'noEvents',
  'eventSubmitted',
  'eventDeliveryConfirmed',
  'eventAccepted',
  'eventRejected',
  'eventClarification',
  'eventDispensed',
  'eventOther',
  'clarificationTitle',
  'threadEntries',
  'authorPharmacy',
  'authorPrescriber',
  'answerLabel',
  'revisionLabel',
  'sendAnswer',
  'answerRequired',
  'answered',
  'overdue',
  'linkedRevision',
  'substitutionTitle',
  'original',
  'proposed',
  'reason',
  'reasonStock',
  'reasonBrand',
  'reasonFormulation',
  'activeMedication',
  'approve',
  'decline',
  'attestation',
  'note',
  'attestationRequired',
  'statePending',
  'stateApproved',
  'stateDeclined',
  'revisionLink',
  'originalRetained',
  'signedAt',
] as const;

/** One prescriber label key. */
export type PrescriberMessageKey = (typeof prescriberKeys)[number];

/** English and Arabic prescriber labels with identical key sets. */
export const prescriberMessages: Record<Locale, Record<PrescriberMessageKey, string>> = {
  en: {
    timelineTitle: 'Pharmacy status timeline',
    factId: 'Fact id',
    factType: 'Status',
    factDetail: 'Detail',
    factActor: 'Recorded by',
    factOccurredAt: 'Occurred at',
    dispensingEvidence: 'Dispensing evidence',
    dispensedBy: 'Dispensed by',
    dispensedAt: 'Dispensed at',
    handedOver: 'Handed over',
    notRecorded: 'Not recorded',
    noEvents: 'No status facts recorded yet.',
    eventSubmitted: 'Submitted',
    eventDeliveryConfirmed: 'Delivery confirmed',
    eventAccepted: 'Accepted by pharmacy',
    eventRejected: 'Rejected by pharmacy',
    eventClarification: 'Clarification requested',
    eventDispensed: 'Dispensed',
    eventOther: 'Status change',
    clarificationTitle: 'Clarification from the pharmacy',
    threadEntries: 'Thread',
    authorPharmacy: 'Pharmacy',
    authorPrescriber: 'Prescriber',
    answerLabel: 'Answer (text)',
    revisionLabel: 'Amended revision id',
    sendAnswer: 'Send answer',
    answerRequired: 'Enter an answer or an amended revision id.',
    answered: 'Answered',
    overdue: 'Overdue',
    linkedRevision: 'Linked revision',
    substitutionTitle: 'Pharmacy substitution proposal',
    original: 'Original',
    proposed: 'Proposed',
    reason: 'Reason',
    reasonStock: 'Out of stock',
    reasonBrand: 'Different brand',
    reasonFormulation: 'Different formulation',
    activeMedication: 'Medication on the prescription',
    approve: 'Approve',
    decline: 'Decline',
    attestation: 'Prescriber attestation (required)',
    note: 'Note (optional)',
    attestationRequired: 'Confirm the attestation before approving.',
    statePending: 'Pending review',
    stateApproved: 'Approved',
    stateDeclined: 'Declined',
    revisionLink: 'Signed revision',
    originalRetained: 'Original medication retained',
    signedAt: 'Signed at',
  },
  ar: {
    timelineTitle: 'الجدول الزمني لحالة الصيدلية',
    factId: 'معرف الحدث',
    factType: 'الحالة',
    factDetail: 'التفاصيل',
    factActor: 'سُجّل بواسطة',
    factOccurredAt: 'وقت الحدث',
    dispensingEvidence: 'بيانات الصرف',
    dispensedBy: 'تم الصرف بواسطة',
    dispensedAt: 'وقت الصرف',
    handedOver: 'ما تم تسليمه',
    notRecorded: 'غير مسجل',
    noEvents: 'لا توجد أحداث حالة مسجلة بعد.',
    eventSubmitted: 'مُرسلة',
    eventDeliveryConfirmed: 'تم تأكيد التسليم',
    eventAccepted: 'مقبولة من الصيدلية',
    eventRejected: 'مرفوضة من الصيدلية',
    eventClarification: 'طلب توضيح',
    eventDispensed: 'مُصروفة',
    eventOther: 'تغيير الحالة',
    clarificationTitle: 'توضيح من الصيدلية',
    threadEntries: 'المحادثة',
    authorPharmacy: 'الصيدلية',
    authorPrescriber: 'الواصف',
    answerLabel: 'الإجابة (نص)',
    revisionLabel: 'معرف المراجعة المعدّلة',
    sendAnswer: 'إرسال الإجابة',
    answerRequired: 'أدخل إجابة أو معرف مراجعة معدّلة.',
    answered: 'تمت الإجابة',
    overdue: 'متأخرة',
    linkedRevision: 'المراجعة المرتبطة',
    substitutionTitle: 'اقتراح الصيدلية بالاستبدال',
    original: 'الأصل',
    proposed: 'المقترح',
    reason: 'السبب',
    reasonStock: 'نفد المخزون',
    reasonBrand: 'علامة تجارية مختلفة',
    reasonFormulation: 'تركيب دوائي مختلف',
    activeMedication: 'الدواء الموجود في الوصفة',
    approve: 'موافقة',
    decline: 'رفض',
    attestation: 'إقرار الواصف (مطلوب)',
    note: 'ملاحظة (اختيارية)',
    attestationRequired: 'أكد الإقرار قبل الموافقة.',
    statePending: 'بانتظار المراجعة',
    stateApproved: 'موافق عليه',
    stateDeclined: 'مرفوض',
    revisionLink: 'المراجعة المُوقّعة',
    originalRetained: 'الاحتفاظ بالدواء الأصلي',
    signedAt: 'وقت التوقيع',
  },
};

/** Map a status fact wire type to its label key, with a total fallback. */
const eventLabels: Record<string, PrescriberMessageKey> = {
  submitted: 'eventSubmitted',
  delivery_confirmed: 'eventDeliveryConfirmed',
  accepted: 'eventAccepted',
  rejected: 'eventRejected',
  clarification: 'eventClarification',
  dispensed: 'eventDispensed',
};

/** Map a proposal state to its bilingual label key. */
const stateLabels: Record<SubstitutionState, PrescriberMessageKey> = {
  pending: 'statePending',
  approved: 'stateApproved',
  declined: 'stateDeclined',
};

/** Map a bounded substitution reason to its bilingual label key. */
const reasonLabels: Record<SubstitutionReason, PrescriberMessageKey> = {
  stock: 'reasonStock',
  brand: 'reasonBrand',
  formulation: 'reasonFormulation',
};

/** Resolve a status fact type to a specific or generic label key. */
export function statusRowLabel(type: string): PrescriberMessageKey {
  return eventLabels[type] ?? 'eventOther';
}

/** Look up one prescriber label for the active locale. */
export function prescriberText(locale: Locale, key: PrescriberMessageKey): string {
  return prescriberMessages[locale][key];
}

/** Map a proposal state to its label key. */
export function stateMessageKey(state: SubstitutionState): PrescriberMessageKey {
  return stateLabels[state];
}

/** Map a substitution reason to its label key. */
export function reasonMessageKey(reason: SubstitutionReason): PrescriberMessageKey {
  return reasonLabels[reason];
}

/** Map a thread author to its party label key. */
export function partyMessageKey(party: ClarificationParty): PrescriberMessageKey {
  return party === 'pharmacy' ? 'authorPharmacy' : 'authorPrescriber';
}
