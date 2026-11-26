import type { Locale } from '../../i18n/messages';
import { fillMessagePlaceholders } from '../duplicates/messagePlaceholders';
import type { TurnaroundStage } from './domain';

/**
 * English and Arabic labels for the laboratory turnaround board.
 *
 * The file is deliberately not named `messages.ts`: the repository-wide
 * message parity registry discovers that name and cannot be changed from this
 * story's scope. This bundle enforces its own parity in `labBoardMessages.test.ts`.
 */

/** Every label key used by the turnaround board, in both locales. */
export const labBoardKeys = [
  'title',
  'syntheticNotice',
  'colOrdered',
  'colCollected',
  'colInAnalysis',
  'colAwaitingVerification',
  'colReleased',
  'ageTarget',
  'overdue',
  'onTrack',
  'orderCount',
  'noOrders',
  'outageRegion',
  'outageTitle',
  'outageDetail',
  'outageLink',
  'outageProcedure',
  'outageProcedureDetail',
] as const;

/** One turnaround-board label key. */
export type LabBoardMessageKey = (typeof labBoardKeys)[number];

/** English and Arabic labels with identical key sets. */
export const labBoardMessages: Record<Locale, Record<LabBoardMessageKey, string>> = {
  en: {
    title: 'Laboratory turnaround board',
    syntheticNotice: 'Synthetic data only. No partner is connected; ages and targets are fixtures.',
    colOrdered: 'Ordered',
    colCollected: 'Collected',
    colInAnalysis: 'In analysis',
    colAwaitingVerification: 'Awaiting verification',
    colReleased: 'Released',
    ageTarget: 'Age {age} min · target {target} min',
    overdue: 'Overdue by {minutes} min',
    onTrack: 'On track',
    orderCount: '{count} orders',
    noOrders: 'No orders in this column.',
    outageRegion: 'Partner interface outage',
    outageTitle: 'Partner interface down',
    outageDetail:
      'Electronic transmission to the partner is unavailable. Use the manual route and record verification evidence.',
    outageLink: 'Open the manual route',
    outageProcedure: 'Manual route for laboratory orders',
    outageProcedureDetail:
      'Print the locally approved request form, send it through the approved channel and record delivery and verification evidence.',
  },
  ar: {
    title: 'لوحة زمن إنجاز التحاليل',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يوجد شريك متصل، والأعمار والمستهدفات أمثلة توضيحية.',
    colOrdered: 'مطلوبة',
    colCollected: 'مجمّعة',
    colInAnalysis: 'قيد التحليل',
    colAwaitingVerification: 'بانتظار التحقق',
    colReleased: 'صادرة',
    ageTarget: 'العمر {age} دقيقة · المستهدف {target} دقيقة',
    overdue: 'متأخرة بـ {minutes} دقيقة',
    onTrack: 'في الموعد',
    orderCount: '{count} طلبات',
    noOrders: 'لا توجد طلبات في هذا العمود.',
    outageRegion: 'انقطاع واجهة الشريك',
    outageTitle: 'واجهة الشريك معطّلة',
    outageDetail: 'الإرسال الإلكتروني إلى الشريك غير متاح. استخدم المسار اليدوي وسجّل دليل التحقق.',
    outageLink: 'فتح المسار اليدوي',
    outageProcedure: 'المسار اليدوي لطلبات التحاليل',
    outageProcedureDetail:
      'اطبع النموذج المعتمد محليًا، أرسله عبر القناة المعتمدة وسجّل دليل التسليم والتحقق.',
  },
};

/** Look up one turnaround-board label for the active locale. */
export function labBoardText(locale: Locale, key: LabBoardMessageKey): string {
  return labBoardMessages[locale][key];
}

/** Fill named placeholders, leaving unknown ones visible rather than blank. */
export function labBoardMessage(
  locale: Locale,
  key: LabBoardMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return fillMessagePlaceholders(labBoardText(locale, key), values);
}

/** Render a whole number with Latin digits in both locales (UX-01, D4). */
export function numberText(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, { numberingSystem: 'latn' }).format(value);
}

/** Column labels, indexed by stage. */
const STAGE_KEYS: Readonly<Record<TurnaroundStage, LabBoardMessageKey>> = {
  ordered: 'colOrdered',
  collected: 'colCollected',
  'in-analysis': 'colInAnalysis',
  'awaiting-verification': 'colAwaitingVerification',
  released: 'colReleased',
};

/** Resolve the column label for a workflow stage. */
export function stageColumnKey(stage: TurnaroundStage): LabBoardMessageKey {
  return STAGE_KEYS[stage];
}
