import { describe, expect, it } from 'vitest';
import {
  canOpenAttachment,
  hasDraftContent,
  hasReason,
  scanWordKey,
  stateTone,
  stateWordKey,
} from './domain';
import { SCAN_STATES, THREAD_STATES } from './types';
import type { ScanState, ThreadState } from './types';

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

