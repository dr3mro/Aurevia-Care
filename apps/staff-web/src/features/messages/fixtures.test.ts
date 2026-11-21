import { describe, expect, it } from 'vitest';
import {
  alphaOrganizationId,
  betaOrganizationId,
  conversationId,
  patientId,
  syntheticConversation,
  syntheticInbox,
  syntheticInboxEntry,
  syntheticMessage,
  syntheticView,
  syntheticWorkItemEvent,
  syntheticWorkQueue,
} from './fixtures';

describe('synthetic messages fixtures', () => {
  it('builds every projection with default values', () => {
    expect(syntheticConversation().id).toBe(conversationId);
    expect(syntheticMessage().body).toContain('Synthetic');
    expect(syntheticWorkItemEvent().eventType).toBe('created');
    expect(syntheticView().messages).toHaveLength(2);
    expect(syntheticInboxEntry().unreadCount).toBe(2);
    expect(syntheticInbox().entries).toHaveLength(1);
    expect(syntheticWorkQueue().entries).toHaveLength(1);
  });

  it('builds every projection with explicit synthetic overrides', () => {
    expect(syntheticConversation({ patientId: 'SYN-PAT-0002' }).patientId).toBe('SYN-PAT-0002');
    expect(syntheticMessage({ sequence: 9 }).sequence).toBe(9);
    expect(syntheticWorkItemEvent({ eventType: 'closed' }).eventType).toBe('closed');
    expect(syntheticView({ messages: [] }).messages).toHaveLength(0);
    expect(syntheticInboxEntry(0, syntheticConversation({ state: 'closed' })).unreadCount).toBe(0);
    expect(syntheticInbox([]).entries).toHaveLength(0);
    expect(syntheticWorkQueue([]).entries).toHaveLength(0);
  });

  it('keeps the two tenant fixtures distinct and synthetic', () => {
    expect(alphaOrganizationId).not.toBe(betaOrganizationId);
    expect(patientId).toContain('SYN-');
  });
});
