/**
 * Design contract: docs/design/screens-and-navigation.md section 3 fixes the
 * four patient tabs and their order. Keeping the order as data makes it a
 * deterministic assertion rather than a fragile inspection of rendered native
 * views.
 */
import { appMessageKeys } from './messages';
import { TAB_DEFINITIONS } from './tabs';

describe('patient tab definitions', () => {
  it('declares exactly four tabs in the contract order', () => {
    expect(TAB_DEFINITIONS.map(tab => tab.id)).toEqual([
      'appointments',
      'reports',
      'messages',
      'account',
    ]);
  });

  it('resolves every label and accessibility label key in the message bundle', () => {
    expect(TAB_DEFINITIONS).toHaveLength(4);
    for (const tab of TAB_DEFINITIONS) {
      expect(appMessageKeys).toContain(tab.labelKey);
      expect(appMessageKeys).toContain(tab.accessibilityLabelKey);
      expect(tab.labelKey).not.toBe(tab.accessibilityLabelKey);
    }
  });
});
