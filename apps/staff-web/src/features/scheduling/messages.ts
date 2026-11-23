import type { Locale } from '../../i18n/messages';
import type { VisitState } from './domain';

/**
 * Scheduling-only label keys. The shared i18n bundle is outside this story's
 * scope, so the feature owns a small, parity-checked en/ar dictionary.
 */
export const schedulingKeys = [
  'scheduling',
  'syntheticNotice',
  'status',
  'commandSent',
  'worklistRange',
  'appointments',
  'day',
  'week',
  'patient',
  'time',
  'resource',
  'start',
  'state',
  'assignee',
  'actions',
  'checkIn',
  'assign',
  'unassigned',
  'noAppointments',
  'bookingForm',
  'facilityCode',
  'patientId',
  'visitType',
  'resourceHint',
  'conflictHint',
  'book',
  'loadMore',
  'stateBooked',
  'stateCheckedIn',
  'stateInProgress',
  'stateCompleted',
  'stateCancelled',
  'stateNoShow',
] as const;

/** A scheduling label key. */
export type SchedulingMessageKey = (typeof schedulingKeys)[number];

/** Bilingual scheduling labels with identical key sets. */
const schedulingMessages: Record<Locale, Record<SchedulingMessageKey, string>> = {
  en: {
    scheduling: 'Scheduling',
    syntheticNotice:
      'Synthetic data only. Appointments are read from and written to the connected API.',
    status: 'Scheduling status',
    commandSent: 'Command sent to the appointment API',
    worklistRange: 'Worklist range',
    appointments: 'Appointments by scheduled time',
    day: 'Day',
    week: 'Week',
    patient: 'Patient',
    time: 'Time',
    resource: 'Protected resource',
    start: 'Start instant (UTC ISO)',
    state: 'Visit state',
    assignee: 'Assign to',
    actions: 'Actions',
    checkIn: 'Check in',
    assign: 'Assign to',
    unassigned: 'Unassigned',
    noAppointments: 'No appointments in this range.',
    bookingForm: 'Book an appointment',
    facilityCode: 'Facility code',
    patientId: 'Patient identifier',
    visitType: 'Visit type',
    resourceHint: 'Enter the clinician resource code. The room is not selected in this screen.',
    conflictHint: 'Protected resource is already occupied in this organization at this time.',
    book: 'Book',
    loadMore: 'Load more',
    stateBooked: 'Booked',
    stateCheckedIn: 'Checked in',
    stateInProgress: 'In progress',
    stateCompleted: 'Completed',
    stateCancelled: 'Cancelled',
    stateNoShow: 'No-show',
  },
  ar: {
    scheduling: 'جدول المواعيد',
    syntheticNotice: 'بيانات توضيحية فقط؛ تُقرأ المواعيد من الخدمة المتصلة ويُكتب إليها.',
    status: 'حالة جدول المواعيد',
    commandSent: 'تم إرسال الأمر إلى خدمة المواعيد',
    worklistRange: 'نطاق قائمة العمل',
    appointments: 'المواعيد مرتبة حسب موعدها',
    day: 'يوم',
    week: 'أسبوع',
    patient: 'المريض',
    time: 'الوقت',
    resource: 'المورد المحمي',
    start: 'وقت البداية (بتوقيت UTC)',
    state: 'حالة الزيارة',
    assignee: 'إسناد إلى',
    actions: 'إجراءات',
    checkIn: 'تسجيل الوصول',
    assign: 'إسناد إلى',
    unassigned: 'غير مسند',
    noAppointments: 'لا توجد مواعيد في هذا النطاق.',
    bookingForm: 'حجز موعد',
    facilityCode: 'رمز المرفق',
    patientId: 'معرّف المريض',
    visitType: 'نوع الزيارة',
    resourceHint: 'أدخل رمز مورد الطبيب. لم يتم اختيار الغرفة في هذه الشاشة.',
    conflictHint: 'المورد المحمي مشغول بالفعل في هذه المؤسسة في هذا الوقت.',
    book: 'حجز',
    loadMore: 'تحميل المزيد',
    stateBooked: 'محجوز',
    stateCheckedIn: 'تم تسجيل الوصول',
    stateInProgress: 'قيد التنفيذ',
    stateCompleted: 'مكتمل',
    stateCancelled: 'ملغي',
    stateNoShow: 'لم يحضر',
  },
};

/** Map each visit state to its bilingual badge label key. */
export const visitStateMessageKey: Record<VisitState, SchedulingMessageKey> = {
  booked: 'stateBooked',
  'checked-in': 'stateCheckedIn',
  'in-progress': 'stateInProgress',
  completed: 'stateCompleted',
  cancelled: 'stateCancelled',
  'no-show': 'stateNoShow',
};

/** Look up one scheduling label for the active locale. */
export function schedulingText(locale: Locale, key: SchedulingMessageKey): string {
  return schedulingMessages[locale][key];
}
