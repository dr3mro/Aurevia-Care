import type { Locale } from '../../i18n/messages';
import { fillMessagePlaceholders } from '../duplicates/messagePlaceholders';
import type {
  FallbackKind,
  InterfaceHealth,
  RecipientResponse,
  ReviewState,
  TransmissionState,
  WorkflowState,
} from './domain';

/**
 * English and Arabic labels for the partner outage board.
 *
 * The file is deliberately not named `messages.ts`: the repository-wide
 * message parity registry discovers that name and cannot be changed from this
 * story's scope. This bundle enforces its own parity in `partnerMessages.test.ts`.
 */

/** Every label key used by the partner board, in both locales. */
export const partnerKeys = [
  'title',
  'syntheticNotice',
  'boardRegion',
  'healthLabel',
  'healthHealthy',
  'healthDegraded',
  'healthDown',
  'lastChecked',
  'waitingCount',
  'stuckCount',
  'waitingCaption',
  'colReference',
  'colType',
  'colWait',
  'colWorkflow',
  'colTransmission',
  'colReview',
  'colResponse',
  'colStatus',
  'itemTypeOrder',
  'itemTypePrescription',
  'transmissionPending',
  'transmissionSent',
  'transmissionDelivered',
  'transmissionFailed',
  'workflowRequested',
  'workflowAccepted',
  'workflowProcessing',
  'workflowCompleted',
  'workflowRejected',
  'workflowCancelled',
  'reviewUnreviewed',
  'reviewAcknowledged',
  'reviewActionPending',
  'reviewResolved',
  'responseAccepted',
  'responseRejected',
  'responseClarification',
  'responseNone',
  'waitAge',
  'stuck',
  'onTrack',
  'fallbackLink',
  'fallbackTitle',
  'fallbackRegion',
  'fallbackPrint',
  'fallbackCall',
  'fallbackManualUpload',
  'fallbackPrintDetail',
  'fallbackCallDetail',
  'fallbackManualUploadDetail',
  'evidenceNote',
  'noWaiting',
] as const;

/** One partner-board label key. */
export type PartnerMessageKey = (typeof partnerKeys)[number];

/** English and Arabic labels with identical key sets. */
export const partnerMessages: Record<Locale, Record<PartnerMessageKey, string>> = {
  en: {
    title: 'Partner interface and outage board',
    syntheticNotice:
      'Synthetic data only. No partner is connected; health and backlog are fixtures.',
    boardRegion: 'Partner board: {partner}',
    healthLabel: 'Interface health',
    healthHealthy: 'Healthy',
    healthDegraded: 'Degraded',
    healthDown: 'Down',
    lastChecked: 'Last checked: {instant}',
    waitingCount: 'Waiting: {count}',
    stuckCount: 'Stuck: {count}',
    waitingCaption: 'Waiting orders and prescriptions',
    colReference: 'Reference',
    colType: 'Type',
    colWait: 'How long',
    colWorkflow: 'Workflow',
    colTransmission: 'Transmission',
    colReview: 'Clinical review',
    colResponse: 'Recipient',
    colStatus: 'Status',
    itemTypeOrder: 'Order',
    itemTypePrescription: 'Prescription',
    transmissionPending: 'Pending',
    transmissionSent: 'Sent',
    transmissionDelivered: 'Delivered',
    transmissionFailed: 'Failed',
    workflowRequested: 'Requested',
    workflowAccepted: 'Accepted',
    workflowProcessing: 'Processing',
    workflowCompleted: 'Completed',
    workflowRejected: 'Rejected',
    workflowCancelled: 'Cancelled',
    reviewUnreviewed: 'Unreviewed',
    reviewAcknowledged: 'Acknowledged',
    reviewActionPending: 'Action pending',
    reviewResolved: 'Resolved',
    responseAccepted: 'Accepted',
    responseRejected: 'Rejected',
    responseClarification: 'Clarification requested',
    responseNone: 'No response yet',
    waitAge: 'Waiting {minutes} min',
    stuck: 'Stuck',
    onTrack: 'On track',
    fallbackLink: 'Manual fallback: {fallback}',
    fallbackTitle: 'Manual fallback procedure',
    fallbackRegion: 'Fallback procedure for {partner}',
    fallbackPrint: 'Print',
    fallbackCall: 'Call',
    fallbackManualUpload: 'Manual upload',
    fallbackPrintDetail:
      'Print the locally approved form and record delivery and verification evidence.',
    fallbackCallDetail:
      'Call the approved partner contact, record read-back and verification evidence.',
    fallbackManualUploadDetail:
      'Upload the partner file through the approved channel and record verification evidence before use.',
    evidenceNote: 'Manual workflows must record delivery and verification evidence.',
    noWaiting: 'No orders or prescriptions waiting for this partner.',
  },
  ar: {
    title: 'لوحة واجهات الشركاء وانقطاع الخدمة',
    syntheticNotice:
      'بيانات توضيحية فقط؛ لا يوجد شريك متصل، والحالة وقائمة الانتظار أمثلة توضيحية.',
    boardRegion: 'لوحة الشريك: {partner}',
    healthLabel: 'حالة الواجهة',
    healthHealthy: 'سليم',
    healthDegraded: 'متدنٍ',
    healthDown: 'معطل',
    lastChecked: 'آخر فحص: {instant}',
    waitingCount: 'قيد الانتظار: {count}',
    stuckCount: 'متعثر: {count}',
    waitingCaption: 'الطلبات والوصفات قيد الانتظار',
    colReference: 'المرجع',
    colType: 'النوع',
    colWait: 'المدة',
    colWorkflow: 'سير العمل',
    colTransmission: 'الإرسال',
    colReview: 'المراجعة السريرية',
    colResponse: 'الجهة المستلمة',
    colStatus: 'الحالة',
    itemTypeOrder: 'طلب',
    itemTypePrescription: 'وصفة',
    transmissionPending: 'قيد الانتظار',
    transmissionSent: 'أُرسل',
    transmissionDelivered: 'تم التسليم',
    transmissionFailed: 'فشل',
    workflowRequested: 'مطلوب',
    workflowAccepted: 'مقبول',
    workflowProcessing: 'قيد المعالجة',
    workflowCompleted: 'مكتمل',
    workflowRejected: 'مرفوض',
    workflowCancelled: 'ملغي',
    reviewUnreviewed: 'لم تُراجع',
    reviewAcknowledged: 'أُقرّ',
    reviewActionPending: 'إجراء مطلوب',
    reviewResolved: 'تم الحسم',
    responseAccepted: 'مقبول',
    responseRejected: 'مرفوض',
    responseClarification: 'طلب توضيح',
    responseNone: 'لا توجد استجابة بعد',
    waitAge: 'بانتظار {minutes} دقيقة',
    stuck: 'متعثر',
    onTrack: 'في المسار',
    fallbackLink: 'الإجراء اليدوي: {fallback}',
    fallbackTitle: 'إجراء الاحتياط اليدوي',
    fallbackRegion: 'إجراء الاحتياط لـ {partner}',
    fallbackPrint: 'طباعة',
    fallbackCall: 'اتصال',
    fallbackManualUpload: 'رفع يدوي',
    fallbackPrintDetail: 'اطبع النموذج المعتمد محليًا وسجّل دليل التسليم والتحقق.',
    fallbackCallDetail:
      'اتصل بجهة الاتصال المعتمدة لدى الشريك وسجّل القراءة المتبادلة ودليل التحقق.',
    fallbackManualUploadDetail:
      'ارفع ملف الشريك عبر القناة المعتمدة وسجّل دليل التحقق قبل استخدامه.',
    evidenceNote: 'يجب أن تسجّل الإجراءات اليدوية دليل التسليم والتحقق.',
    noWaiting: 'لا توجد طلبات أو وصفات قيد الانتظار لهذا الشريك.',
  },
};

/** Look up one partner-board label for the active locale. */
export function partnerText(locale: Locale, key: PartnerMessageKey): string {
  return partnerMessages[locale][key];
}

/** Fill named placeholders, leaving unknown ones visible rather than blank. */
export function partnerMessage(
  locale: Locale,
  key: PartnerMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return fillMessagePlaceholders(partnerText(locale, key), values);
}

/** Render a wait duration with Latin digits in both locales. */
export function waitMinutesText(ageMinutes: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, { numberingSystem: 'latn' }).format(ageMinutes);
}

/** Interface health labels, indexed by state. */
const HEALTH_KEYS: Readonly<Record<InterfaceHealth, PartnerMessageKey>> = {
  healthy: 'healthHealthy',
  degraded: 'healthDegraded',
  down: 'healthDown',
};

/** Resolve the label for an interface health state. */
export function healthKey(health: InterfaceHealth): PartnerMessageKey {
  return HEALTH_KEYS[health];
}

/** Manual fallback labels, indexed by fallback kind. */
const FALLBACK_LABEL_KEYS: Readonly<Record<FallbackKind, PartnerMessageKey>> = {
  print: 'fallbackPrint',
  call: 'fallbackCall',
  'manual-upload': 'fallbackManualUpload',
};

/** Resolve the label for a manual fallback kind. */
export function fallbackLabelKey(kind: FallbackKind): PartnerMessageKey {
  return FALLBACK_LABEL_KEYS[kind];
}

/** Manual fallback procedure details, indexed by fallback kind. */
const FALLBACK_DETAIL_KEYS: Readonly<Record<FallbackKind, PartnerMessageKey>> = {
  print: 'fallbackPrintDetail',
  call: 'fallbackCallDetail',
  'manual-upload': 'fallbackManualUploadDetail',
};

/** Resolve the procedure detail for a manual fallback kind. */
export function fallbackDetailKey(kind: FallbackKind): PartnerMessageKey {
  return FALLBACK_DETAIL_KEYS[kind];
}

/** Transmission labels, indexed by state. */
const TRANSMISSION_KEYS: Readonly<Record<TransmissionState, PartnerMessageKey>> = {
  pending: 'transmissionPending',
  sent: 'transmissionSent',
  delivered: 'transmissionDelivered',
  failed: 'transmissionFailed',
};

/** Resolve the label for a transmission state. */
export function transmissionKey(state: TransmissionState): PartnerMessageKey {
  return TRANSMISSION_KEYS[state];
}

/** Workflow labels, indexed by state. */
const WORKFLOW_KEYS: Readonly<Record<WorkflowState, PartnerMessageKey>> = {
  requested: 'workflowRequested',
  accepted: 'workflowAccepted',
  processing: 'workflowProcessing',
  completed: 'workflowCompleted',
  rejected: 'workflowRejected',
  cancelled: 'workflowCancelled',
};

/** Resolve the label for a workflow state. */
export function workflowKey(state: WorkflowState): PartnerMessageKey {
  return WORKFLOW_KEYS[state];
}

/** Clinical review labels, indexed by state. */
const REVIEW_KEYS: Readonly<Record<ReviewState, PartnerMessageKey>> = {
  unreviewed: 'reviewUnreviewed',
  acknowledged: 'reviewAcknowledged',
  'action-pending': 'reviewActionPending',
  resolved: 'reviewResolved',
};

/** Resolve the label for a clinical review state. */
export function reviewKey(state: ReviewState): PartnerMessageKey {
  return REVIEW_KEYS[state];
}

/** Recipient response labels, indexed by response. */
const RESPONSE_KEYS: Readonly<Record<RecipientResponse, PartnerMessageKey>> = {
  accepted: 'responseAccepted',
  rejected: 'responseRejected',
  'clarification-requested': 'responseClarification',
};

/** Resolve the label for a recipient response, including the absent state. */
export function responseKey(response: RecipientResponse | null): PartnerMessageKey {
  return response === null ? 'responseNone' : RESPONSE_KEYS[response];
}

/** Worded status for a stuck or on-track row; never colour alone. */
export function stuckKey(stuck: boolean): PartnerMessageKey {
  return stuck ? 'stuck' : 'onTrack';
}
