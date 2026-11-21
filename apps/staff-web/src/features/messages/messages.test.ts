import { describe, expect, it } from 'vitest';
import {
  messagesKeys,
  messagesMessage,
  messagesText,
  senderMessageKey,
  stateMessageKey,
  unitMessageKey,
  workEventMessageKey,
} from './messages';
import type { ConversationState, SenderKind, UnitKey, WorkEventType } from './types';

describe('messages labels', () => {
  it('gives every key a non-empty label in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of messagesKeys) {
        expect(messagesText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(messagesText('ar', 'messages')).toMatch(/[\u0600-\u06FF]/);
    expect(messagesText('ar', 'messages')).not.toBe(messagesText('en', 'messages'));
  });

  it('fills named placeholders and leaves unknown placeholders visible', () => {
    expect(messagesMessage('en', 'unreadCount', { count: '3' })).toBe('3 unread');
    expect(messagesMessage('ar', 'unreadCount', { count: '3' })).toContain('3');
    expect(messagesMessage('en', 'unreadCount')).toBe('{count} unread');
  });

  it('maps every domain value to a defined label key', () => {
    for (const state of ['awaiting_staff', 'awaiting_patient', 'resolved', 'closed'] as ConversationState[]) {
      expect(messagesKeys).toContain(stateMessageKey[state]);
    }
    for (const unit of ['clinic', 'lab', 'radiology', 'pt', 'pharmacy'] as UnitKey[]) {
      expect(messagesKeys).toContain(unitMessageKey[unit]);
    }
    for (const sender of ['patient', 'staff'] as SenderKind[]) {
      expect(messagesKeys).toContain(senderMessageKey[sender]);
    }
    for (const event of ['created', 'reopened', 'assigned', 'transferred', 'resolved', 'closed'] as WorkEventType[]) {
      expect(messagesKeys).toContain(workEventMessageKey[event]);
    }
  });
});
