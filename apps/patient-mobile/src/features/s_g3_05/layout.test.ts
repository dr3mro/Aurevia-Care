import { jest } from '@jest/globals';

import { activeLayoutDirection } from './layout';

let mockIsRTL = true;

jest.mock('react-native', () => ({
  I18nManager: {
    get isRTL(): boolean {
      return mockIsRTL;
    },
  },
}));

describe('MSG-02 layout direction', () => {
  it('returns rtl for a right-to-left device locale', () => {
    mockIsRTL = true;
    expect(activeLayoutDirection()).toBe('rtl');
  });

  it('returns ltr for a left-to-right device locale', () => {
    mockIsRTL = false;
    expect(activeLayoutDirection()).toBe('ltr');
  });
});
