import { describe, expect, it } from 'vitest';
import { stateViewMessages, stateViewText } from './stateViewMessages';

describe('state view message bundles', () => {
  it('keeps identical English and Arabic key sets', () => {
    expect(Object.keys(stateViewMessages.en).sort()).toEqual(
      Object.keys(stateViewMessages.ar).sort(),
    );
  });

  it('resolves a translated string for the active locale', () => {
    expect(stateViewText('en', 'retry')).toBe('Retry');
    expect(stateViewText('ar', 'retry')).toBe('إعادة المحاولة');
  });
});
