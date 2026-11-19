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
  'resourceHint',
  'conflictHint',
  'book',
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
    syntheticNotice: 'Synthetic fixtures only. No real clinic, patient or endpoint is contacted.',
    status: 'Scheduling status',
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
    bookingForm: 'Synthetic booking form',
    resourceHint: 'Holds and active bookings occupy a protected resource in this organization only.',
    conflictHint: 'Protected resource is already occupied in this organization at this time.',
    book: 'Book',
    stateBooked: 'Booked',
    stateCheckedIn: 'Checked in',
    stateInProgress: 'In progress',
    stateCompleted: 'Completed',
    stateCancelled: 'Cancelled',
    stateNoShow: 'No-show',
  },
  ar: {
    scheduling: 'جدول المواعيد',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يتم الاتصال بأي عيادة أو مريض أو خدمة حقيقية.',
    status: 'حالة جدول المواعيد',
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
    bookingForm: 'نموذج حجز توضيحي',
    resourceHint: 'الحجوزات النشطة تشغل المورد المحمي في هذه المؤسسة فقط.',
    conflictHint: 'المورد المحمي مشغول بالفعل في هذه المؤسسة في هذا الوقت.',
    book: 'حجز',
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
