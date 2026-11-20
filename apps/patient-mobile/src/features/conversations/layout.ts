import { I18nManager } from 'react-native';

import type { LayoutDirection } from './domain';

/**
 * Resolve the active layout direction for RTL-aware rendering (UX-01).
 *
 * This is the only module that reads the native RTL flag, so components stay
 * presentational and the two outcomes are directly testable.
 *
 * @return {@code 'rtl'} when the device uses a right-to-left locale, else {@code 'ltr'}
 */
export function activeLayoutDirection(): LayoutDirection {
  return I18nManager.isRTL ? 'rtl' : 'ltr';
}
