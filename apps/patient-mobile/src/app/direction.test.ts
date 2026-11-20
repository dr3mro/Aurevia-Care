/**
 * UX-01: the single direction helper is the only place that asks the native
 * layout to mirror. Both locale branches are exercised here so a second,
 * untested `forceRTL` call cannot creep into a screen.
 */
import { jest } from '@jest/globals';
import { I18nManager } from 'react-native';

import { applyDirection } from './direction';

describe('applyDirection', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('requests RTL for Arabic', () => {
    const forceRTL = jest.spyOn(I18nManager, 'forceRTL').mockImplementation(() => undefined);

    applyDirection('ar');

    expect(forceRTL).toHaveBeenCalledTimes(1);
    expect(forceRTL).toHaveBeenCalledWith(true);
  });

  it('requests LTR for English', () => {
    const forceRTL = jest.spyOn(I18nManager, 'forceRTL').mockImplementation(() => undefined);

    applyDirection('en');

    expect(forceRTL).toHaveBeenCalledTimes(1);
    expect(forceRTL).toHaveBeenCalledWith(false);
  });
});
