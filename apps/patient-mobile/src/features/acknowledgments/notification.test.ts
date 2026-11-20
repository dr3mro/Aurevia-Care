import { buildGenericNotification } from './notification';

describe('MSG-05 generic notification', () => {
  it('uses fixed, non-clinical text and an authenticated deep link', () => {
    expect(buildGenericNotification()).toEqual({
      title: 'New message',
      body: 'You have a new message. Open the app to read it.',
      deepLink: 'aurevia://messages/inbox',
    });
  });

  it('reveals no patient, unit, instruction, identifier or clinical detail', () => {
    const notification = buildGenericNotification();
    const serialized = `${notification.title} ${notification.body} ${notification.deepLink}`;
    const sensitive = [
      'patient', 'unit', 'instruction', 'collection', 'glucose', 'lab',
      'aaaaaaaa', 'cccccccc', '2026-', 'receipt', 'acknowledge',
    ];
    for (const token of sensitive) {
      expect(serialized.toLowerCase()).not.toContain(token);
    }
  });

  it('returns a fresh value so callers cannot mutate the shared constant', () => {
    const first = buildGenericNotification();
    (first as { title: string }).title = 'changed';
    expect(buildGenericNotification().title).toBe('New message');
  });
});
