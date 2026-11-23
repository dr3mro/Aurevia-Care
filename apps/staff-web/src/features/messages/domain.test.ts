import { describe, expect, it } from 'vitest';
import {
  applyInboundMessage,
  canOpenAttachment,
  hasDraftContent,
  hasReason,
  scanWordKey,
  stateTone,
  stateWordKey,
} from './domain';
import { alphaInboxView } from './fixtures';
import { SCAN_STATES, THREAD_STATES } from './types';
import type { InboundMessage, ScanState, ThreadState } from './types';

const inbound: InboundMessage = {
  threadId: 'thread-009',
  patientName: { latinName: 'Synthetic Nour', localName: 'نور تجريبية' },
  patientId: 'synthetic-patient-009',
  topic: 'New question',
  message: {
    id: 'message-009',
    senderRole: 'patient',
    senderName: 'Synthetic Nour',
    body: 'Is the clinic open on Saturday?',
    sentAt: '2026-03-09T17:00:00Z',
    timeZone: 'America/New_York',
    attachments: [],
  },
};

describe('unit inbox state and scan mapping', () => {
  it('publishes the four work states and three scan states', () => {
    expect(THREAD_STATES).toEqual(['new', 'replied', 'waiting', 'resolved']);
    expect(SCAN_STATES).toEqual(['clean', 'unscanned', 'failed']);
  });

  it.each<readonly [ThreadState, string]>([
    ['new', 'stateNew'],
    ['replied', 'stateReplied'],
    ['waiting', 'stateWaiting'],
    ['resolved', 'stateResolved'],
  ])('maps the %s state to its %s word', (state, key) => {
    expect(stateWordKey(state)).toBe(key);
  });

  it('maps thread states to a tone', () => {
    expect(stateTone('new')).toBe('urgent');
    expect(stateTone('waiting')).toBe('waiting');
    expect(stateTone('replied')).toBe('waiting');
    expect(stateTone('resolved')).toBe('calm');
  });

  it.each<readonly [ScanState, string]>([
    ['clean', 'scanClean'],
    ['unscanned', 'scanUnscanned'],
    ['failed', 'scanFailed'],
  ])('maps the %s scan state to its %s word', (scan, key) => {
    expect(scanWordKey(scan)).toBe(key);
  });

  it('allows only a clean attachment to be opened', () => {
    expect(canOpenAttachment('clean')).toBe(true);
    expect(canOpenAttachment('unscanned')).toBe(false);
    expect(canOpenAttachment('failed')).toBe(false);
  });

  it('treats blank composer content as no draft', () => {
    expect(hasDraftContent('')).toBe(false);
    expect(hasDraftContent(' \n ')).toBe(false);
    expect(hasDraftContent('Thank you')).toBe(true);
    expect(hasReason('')).toBe(false);
    expect(hasReason('   ')).toBe(false);
    expect(hasReason('handover recorded')).toBe(true);
  });
});

describe('inbound work item creation', () => {
  it('creates a new unread work item for an unknown thread', () => {
    const next = applyInboundMessage(alphaInboxView, inbound);
    expect(next.threads).toHaveLength(alphaInboxView.threads.length + 1);
    expect(next.threads[0]?.id).toBe('thread-009');
    expect(next.threads[0]?.state).toBe('new');
    expect(next.threads[0]?.unread).toBe(true);
    expect(next.threads[0]?.owner).toBe('');
  });

  it('reopens a resolved thread as new unread work and keeps other threads', () => {
    const next = applyInboundMessage(alphaInboxView, { ...inbound, threadId: 'thread-004' });
    const reopened = next.threads.find((thread) => thread.id === 'thread-004');
    const untouched = next.threads.find((thread) => thread.id === 'thread-001');
    expect(reopened?.state).toBe('new');
    expect(reopened?.unread).toBe(true);
    expect(reopened?.messages.at(-1)?.id).toBe('message-009');
    expect(untouched?.state).toBe('replied');
  });
});
