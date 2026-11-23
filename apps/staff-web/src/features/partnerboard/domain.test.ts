import { describe, expect, it } from 'vitest';

import type { WaitingItemWire } from './domain';
import {
  fallbackFor,
  isStuck,
  scriptForName,
  STALE_WAIT_MINUTES,
  stuckCount,
  waitingCount,
} from './domain';
import { imagingBoard, labBoard, pharmacyBoard } from './fixtures';

/** A settled item; every test overrides only the axis it exercises. */
function settled(overrides: Partial<WaitingItemWire> = {}): WaitingItemWire {
  return {
    id: 'synthetic-item',
    reference: 'SYN-1',
    itemType: 'order',
    ageMinutes: 5,
    workflow: 'completed',
    transmission: 'delivered',
    review: 'resolved',
    recipientResponse: 'accepted',
    ...overrides,
  };
}

describe('partner board counts', () => {
  it('counts every waiting item and only the stuck ones for each partner', () => {
    expect(waitingCount(labBoard)).toBe(3);
    expect(stuckCount(labBoard)).toBe(2);
    expect(waitingCount(imagingBoard)).toBe(2);
    expect(stuckCount(imagingBoard)).toBe(1);
    expect(waitingCount(pharmacyBoard)).toBe(2);
    expect(stuckCount(pharmacyBoard)).toBe(1);
  });
});

describe('isStuck (XCH-02 and XCH-03)', () => {
  it('treats a failed transmission as stuck even when the workflow looks complete', () => {
    expect(
      isStuck(settled({ transmission: 'failed', workflow: 'completed', review: 'resolved' })),
    ).toBe(true);
  });

  it('treats a pending transmission at the stale threshold as stuck', () => {
    expect(
      isStuck(
        settled({ transmission: 'pending', ageMinutes: STALE_WAIT_MINUTES, review: 'resolved' }),
      ),
    ).toBe(true);
  });

  it('does not treat a young pending transmission as stuck', () => {
    expect(
      isStuck(
        settled({
          transmission: 'pending',
          ageMinutes: STALE_WAIT_MINUTES - 1,
          review: 'resolved',
        }),
      ),
    ).toBe(false);
  });

  it('does not take the pending branch for a sent transmission', () => {
    expect(isStuck(settled({ transmission: 'sent', ageMinutes: 999, review: 'resolved' }))).toBe(
      false,
    );
  });

  it('treats a rejection or a clarification request as stuck, but acceptance or no response as not stuck', () => {
    expect(isStuck(settled({ recipientResponse: 'rejected' }))).toBe(true);
    expect(isStuck(settled({ recipientResponse: 'clarification-requested' }))).toBe(true);
    expect(isStuck(settled({ recipientResponse: 'accepted' }))).toBe(false);
    expect(isStuck(settled({ recipientResponse: null }))).toBe(false);
  });

  it('keeps a delivered but unreviewed or action-pending clinical task stuck', () => {
    expect(isStuck(settled({ transmission: 'delivered', review: 'unreviewed' }))).toBe(true);
    expect(isStuck(settled({ transmission: 'delivered', review: 'action-pending' }))).toBe(true);
  });

  it('does not let a transmission acknowledgement close a clinical task on track', () => {
    expect(
      isStuck(
        settled({
          transmission: 'delivered',
          review: 'acknowledged',
          recipientResponse: 'accepted',
        }),
      ),
    ).toBe(false);
  });
});

describe('fallbackFor (PRD-02)', () => {
  it('maps each partner kind to its approved manual fallback', () => {
    expect(fallbackFor('lab')).toBe('manual-upload');
    expect(fallbackFor('imaging')).toBe('call');
    expect(fallbackFor('pharmacy')).toBe('print');
  });
});

describe('scriptForName', () => {
  it('tags Arabic names for RTL isolation and Latin names for English', () => {
    expect(scriptForName('مختبر النور')).toBe('ar');
    expect(scriptForName('Synthetic Imaging Partner')).toBe('en');
  });
});
