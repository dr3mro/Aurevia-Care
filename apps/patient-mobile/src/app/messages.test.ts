/** READY-48: every application label must exist in English and Arabic (UX-01). */
import { appMessageKeys, appText } from './messages';

describe('application messages', () => {
  it('has a non-empty translation for every key in both languages', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of appMessageKeys) {
        expect(appText(locale, key)).toBeTruthy();
      }
    }
  });

  it.each(['en', 'ar'] as const)('localizes the signed-out and state labels in %s', (locale) => {
    expect(appText(locale, 'signedOutTitle')).toBeTruthy();
    expect(appText(locale, 'signedOutBody')).toBeTruthy();
    expect(appText(locale, 'loading')).toBeTruthy();
    expect(appText(locale, 'retry')).toBeTruthy();
  });
});
