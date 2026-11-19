import type { UiElementDescriptor } from './types';

/** Synthetic bilingual label used by the Arabic RTL fixture (no patient data). */
export const MIXED_SCRIPT_LABEL = 'تحديد الموعد · Open appointment';

/** PAT-01 English "Open appointments" button: conforms to every UX-01 check. */
export const EN_BUTTON: UiElementDescriptor = {
  id: 'pat01-appointments-open',
  accessibilityLabel: 'Open appointments',
  accessibilityRole: 'button',
  interactive: true,
  supportsRtl: false,
  direction: 'ltr',
  screenReaderFlow: true,
  dynamicType: true,
  locale: 'en',
};

/** MSG-03 Arabic "Send file" button with a mixed-script accessible name. */
export const AR_BUTTON: UiElementDescriptor = {
  id: 'msg03-send-file',
  accessibilityLabel: MIXED_SCRIPT_LABEL,
  accessibilityRole: 'button',
  interactive: true,
  supportsRtl: true,
  direction: 'rtl',
  screenReaderFlow: true,
  dynamicType: true,
  locale: 'ar',
};

/** Non-interactive static text: blank label and null role are legal. */
export const STATIC_TEXT: UiElementDescriptor = {
  id: 'pat02-report-revision',
  accessibilityLabel: '',
  accessibilityRole: null,
  interactive: false,
  supportsRtl: false,
  direction: 'ltr',
  screenReaderFlow: true,
  dynamicType: true,
  locale: 'en',
};

/** Conforming set spanning both locales and both interactive and static elements. */
export const CONFORMING_ELEMENTS: readonly UiElementDescriptor[] = [
  EN_BUTTON,
  AR_BUTTON,
  STATIC_TEXT,
];
