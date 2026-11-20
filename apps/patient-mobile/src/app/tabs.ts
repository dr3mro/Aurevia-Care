/**
 * The four patient tabs are a design contract
 * (docs/design/screens-and-navigation.md section 3). They are kept as data so
 * order and localization keys are asserted directly instead of by inspecting
 * native tab views.
 */
import type { AppMessageKey } from './messages';

/** Stable tab identifier; also used as the navigation route name. */
export type TabId = 'appointments' | 'reports' | 'messages' | 'account';

/** One tab in the patient app shell. */
export interface TabDefinition {
  /** Stable route name. */
  readonly id: TabId;
  /** Message key for the visible label. */
  readonly labelKey: AppMessageKey;
  /** Message key for the accessibility label announced on the tab button. */
  readonly accessibilityLabelKey: AppMessageKey;
}

/** The four tabs, in the exact order required by the specification. */
export const TAB_DEFINITIONS: readonly TabDefinition[] = [
  {
    id: 'appointments',
    labelKey: 'tabAppointments',
    accessibilityLabelKey: 'tabAppointmentsAccessibility',
  },
  { id: 'reports', labelKey: 'tabReports', accessibilityLabelKey: 'tabReportsAccessibility' },
  { id: 'messages', labelKey: 'tabMessages', accessibilityLabelKey: 'tabMessagesAccessibility' },
  { id: 'account', labelKey: 'tabAccount', accessibilityLabelKey: 'tabAccountAccessibility' },
];
