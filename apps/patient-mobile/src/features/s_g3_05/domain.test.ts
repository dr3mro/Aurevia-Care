import {
  applyReply,
  canReply,
  CLOSED_THREAD_MESSAGE,
  markRead,
  MESSAGING_NOT_PERMITTED_MESSAGE,
  unreadCount,
  unreadTotal,
} from './domain';
import type { Conversation, Message, SenderRole, ReplyInput } from './domain';

const SENT_AT = '2026-11-19T10:00:00Z';

function message(id: string, sequence: number, senderRole: SenderRole): Message {
  return {
    id,
    conversationId: 'conv-1',
    senderId: senderRole === 'unit' ? 'unit-a' : 'patient-a',
    senderRole,
    body: `message ${sequence}`,
    sentAt: SENT_AT,
    sequence,
  };
}

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    organizationId: 'org-1',
    unitId: 'lab',
    unitName: 'Synthetic Lab',
    topic: 'follow up',
    state: 'awaiting_staff',
    messagingPermitted: true,
    messages: [],
    lastReadSequence: 0,
    ...overrides,
  };
}

function reply(): ReplyInput {
  return {
    messageId: 'client-1',
    senderId: 'patient-a',
    sentAt: SENT_AT,
    body: 'one more question',
  };
}

describe('MSG-02 unread derivation', () => {
  it('counts only unread unit messages beyond the read watermark', () => {
    const withUnread = conversation({
      messages: [message('m1', 1, 'patient'), message('m2', 2, 'unit'), message('m3', 3, 'unit')],
      lastReadSequence: 2,
    });
    expect(unreadCount(withUnread)).toBe(1);

    const allRead = conversation({ messages: withUnread.messages, lastReadSequence: 3 });
    expect(unreadCount(allRead)).toBe(0);

    expect(unreadCount(conversation())).toBe(0);
  });

  it('sums unread counts across an inbox', () => {
    expect(unreadTotal([])).toBe(0);
    const first = conversation({ messages: [message('m1', 1, 'unit')] });
    const second = conversation({
      id: 'conv-2',
      messages: [message('m2', 1, 'patient'), message('m3', 2, 'unit')],
      lastReadSequence: 2,
    });
    expect(unreadTotal([first, second])).toBe(1);
  });
});

describe('MSG-02 read watermark', () => {
  it('advances to the newest message without mutating the input', () => {
    const original = conversation({ messages: [message('m1', 1, 'unit'), message('m2', 4, 'unit')] });
    const updated = markRead(original);
    expect(updated.lastReadSequence).toBe(4);
    expect(original.lastReadSequence).toBe(0);
    expect(updated.messages).toBe(original.messages);
  });

  it('never moves the watermark backwards and handles an empty thread', () => {
    expect(markRead(conversation({ lastReadSequence: 9, messages: [message('m1', 2, 'unit')] }))
      .lastReadSequence).toBe(9);
    expect(markRead(conversation()).lastReadSequence).toBe(0);
  });
});

describe('MSG-02 reply permission', () => {
  it('allows replies only on a writable, permitted thread', () => {
    expect(canReply(conversation({ state: 'awaiting_staff' }))).toBe(true);
    expect(canReply(conversation({ state: 'awaiting_patient' }))).toBe(true);
    expect(canReply(conversation({ state: 'resolved' }))).toBe(true);
    expect(canReply(conversation({ state: 'closed' }))).toBe(false);
    expect(canReply(conversation({ messagingPermitted: false }))).toBe(false);
  });
});

describe('MSG-02 apply reply', () => {
  it('refuses a closed thread and a revoked relationship', () => {
    expect(() => applyReply(conversation({ state: 'closed' }), reply())).toThrow(CLOSED_THREAD_MESSAGE);
    expect(() => applyReply(conversation({ messagingPermitted: false }), reply()))
      .toThrow(MESSAGING_NOT_PERMITTED_MESSAGE);
  });

  it('appends a patient message and reopens a resolved thread', () => {
    const original = conversation({ state: 'resolved', messages: [message('m1', 3, 'unit')] });
    const updated = applyReply(original, reply());
    expect(updated.state).toBe('awaiting_staff');
    expect(updated.messages).toHaveLength(2);
    const appended = updated.messages[1];
    expect(appended?.id).toBe('client-1');
    expect(appended?.conversationId).toBe('conv-1');
    expect(appended?.senderId).toBe('patient-a');
    expect(appended?.senderRole).toBe('patient');
    expect(appended?.body).toBe('one more question');
    expect(appended?.sentAt).toBe(SENT_AT);
    expect(appended?.sequence).toBe(4);
    expect(original.messages).toHaveLength(1);
    expect(original.state).toBe('resolved');
  });

  it('starts at sequence one on an empty thread and keeps non-resolved states awaiting staff', () => {
    const first = applyReply(conversation(), reply());
    expect(first.messages[0]?.sequence).toBe(1);
    expect(first.state).toBe('awaiting_staff');
    const fromAwaitingPatient = applyReply(conversation({ state: 'awaiting_patient' }), reply());
    expect(fromAwaitingPatient.state).toBe('awaiting_staff');
  });
});
