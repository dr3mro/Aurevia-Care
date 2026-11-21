import type { Locale } from '../i18n/messages';

/**
 * Bilingual labels for the required screen states (WEB-09). The shared i18n
 * bundle is outside this component's ownership, so the component owns a small,
 * parity-checked en/ar dictionary, following the feature message convention.
 */
export const stateViewKeys = [
  'loading',
  'empty',
  'error',
  'errorDetail',
  'correlation',
  'correlationUnavailable',
  'retry',
  'forbidden',
  'forbiddenDetail',
  'conflict',
  'conflictDetail',
  'compare',
  'reload',
  'copyMyChanges',
  'sessionExpired',
  'sessionExpiredDetail',
  'signIn',
] as const;

/** A label key used by one of the required state views. */
export type StateViewMessageKey = (typeof stateViewKeys)[number];

/** English and Arabic state labels with identical key sets. */
export const stateViewMessages: Record<Locale, Record<StateViewMessageKey, string>> = {
  en: {
    loading: 'Loading…',
    empty: 'Nothing to show',
    error: 'Something went wrong',
    errorDetail: 'The request could not be completed. You can retry.',
    correlation: 'Correlation ID',
    correlationUnavailable: 'not available',
    retry: 'Retry',
    forbidden: 'You do not have access',
    forbiddenDetail: 'Your role cannot perform this action.',
    conflict: 'This record changed',
    conflictDetail: 'The information was updated since you opened it. Your changes are kept.',
    compare: 'Compare',
    reload: 'Reload',
    copyMyChanges: 'Copy my changes',
    sessionExpired: 'Your session has expired',
    sessionExpiredDetail: 'Sign in again to continue where you left off.',
    signIn: 'Sign in',
  },
  ar: {
    loading: 'جار التحميل…',
    empty: 'لا يوجد شيء لعرضه',
    error: 'حدث خطأ',
    errorDetail: 'تعذّر إكمال الطلب. يمكنك إعادة المحاولة.',
    correlation: 'معرّف الارتباط',
    correlationUnavailable: 'غير متاح',
    retry: 'إعادة المحاولة',
    forbidden: 'لا تملك صلاحية الوصول',
    forbiddenDetail: 'لا يمكن لدورك تنفيذ هذا الإجراء.',
    conflict: 'تغيّر هذا السجل',
    conflictDetail: 'تم تحديث المعلومات منذ فتحها. تم الاحتفاظ بتغييراتك.',
    compare: 'مقارنة',
    reload: 'إعادة تحميل',
    copyMyChanges: 'نسخ تغييراتي',
    sessionExpired: 'انتهت جلستك',
    sessionExpiredDetail: 'سجّل الدخول مرة أخرى للمتابعة من حيث توقفت.',
    signIn: 'تسجيل الدخول',
  },
};

/** Look up one state-view label for the active locale. */
export function stateViewText(locale: Locale, key: StateViewMessageKey): string {
  return stateViewMessages[locale][key];
}
