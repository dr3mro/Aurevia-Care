/**
 * Single native-direction boundary for the patient app (UX-01).
 *
 * `I18nManager.forceRTL` is a restart-time platform flag, not a per-screen
 * style. It is called only here so there is exactly one place to audit and one
 * unit test that proves both locale branches. No layout is mirrored by hand:
 * React Navigation mirrors the tab order from the native direction.
 */
import { I18nManager } from 'react-native';

import type { Locale } from './messages';

/**
 * Request the native layout direction for the active locale.
 *
 * @param locale active display locale; Arabic requests RTL, English requests LTR
 */
export function applyDirection(locale: Locale): void {
  I18nManager.forceRTL(locale === 'ar');
}
