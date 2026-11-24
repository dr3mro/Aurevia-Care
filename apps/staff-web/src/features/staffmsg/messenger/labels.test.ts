import { describe, expect, it } from 'vitest';
import { messengerKeys, messengerMessages, messengerText, messengerTextWithValue } from './labels';

describe('messenger labels', () => {
  it('has a distinct key for every label', () => {
    expect(new Set(messengerKeys).size).toBe(messengerKeys.length);
    expect([...messengerKeys].sort()).toEqual(
      [
        'attachLabel',
        'attachmentTrayLabel',
        'bodyLabel',
        'composerLabel',
        'connectionLabel',
        'conversationsLabel',
        'deliveryDelivered',
        'deliveryFailed',
        'deliverySending',
        'emptyConversations',
        'emptyThread',
        'emergencyBanner',
        'emptyTray',
        'incomingLabel',
        'noConversation',
        'outgoingLabel',
        'pendingFiles',
        'reconnecting',
        'removeAttachment',
        'retry',
        'send',
        'sendFailed',
        'stageFileLabel',
        'streaming',
        'syntheticNotice',
        'threadLabel',
        'title',
        'unreadBadge',
        'polling',
      ].sort(),
    );
  });

  it('has a non-empty label for every key in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of messengerKeys) {
        expect(messengerMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('translates every key into Arabic letters (UX-01)', () => {
    for (const key of messengerKeys) {
      expect(messengerText('ar', key)).toMatch(/[\u0600-\u06FF]/);
    }
  });

  it('resolves each key from the active bundle', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of messengerKeys) {
        expect(messengerText(locale, key)).toBe(messengerMessages[locale][key]);
      }
    }
  });

  it('interpolates a named value without changing the key set', () => {
    expect(messengerTextWithValue('en', 'unreadBadge', 3)).toBe('3 unread');
    expect(messengerTextWithValue('en', 'pendingFiles', 'a.pdf, b.png')).toContain('a.pdf, b.png');
  });
});
