import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  ORDER_STATES,
  availableActions,
  currentState,
  idempotencyKeyFor,
  isConflict,
  validateReason,
  versionFor,
} from './domain';
import type { TaskEvent } from './domain';
import {
  orderStateKey,
  orderStateLabel,
  ordersKeys,
  ordersText,
  priorityMessageKey,
  timelineActionLabel,
} from './messages';

describe('order recipient action policy', () => {
  it('lists all eight wire states in a stable order', () => {
    expect(ORDER_STATES).toEqual([
      'requested',
      'accepted',
      'rejected',
      'clarification_requested',
      'change_pending',
      'cancellation_pending',
      'cancelled',
      'revoked',
    ]);
  });

  it('offers all three responses to a recipient role while the order is requested', () => {
    for (const role of ['clinician', 'lab', 'radiology'] as const) {
      expect(availableActions('requested', role)).toEqual(['accept', 'reject', 'clarify']);
    }
  });

  it('offers no response after the requested state or to a non-recipient role', () => {
    for (const state of ORDER_STATES) {
      if (state !== 'requested') {
        expect(availableActions(state, 'clinician')).toEqual([]);
      }
    }
    expect(availableActions('requested', 'tenant_admin')).toEqual([]);
    expect(availableActions('requested', 'reception')).toEqual([]);
  });

  it('accepts a non-blank reason and rejects a blank one', () => {
    expect(validateReason('   ')).toBe(false);
    expect(validateReason('  needs the specimen type  ')).toBe(true);
  });

  it('treats both 409 and 412 failures as a stale-version conflict', () => {
    expect(isConflict('conflict')).toBe(true);
    expect(isConflict('precondition-required')).toBe(true);
    expect(isConflict('invalid')).toBe(false);
    expect(isConflict('server')).toBe(false);
  });
});

describe('task state and idempotency', () => {
  const event = (toState: string, orderVersion = 4): TaskEvent => ({
    id: 'event-1',
    orderVersion,
    action: 'accept',
    fromState: 'requested',
    toState,
    reason: null,
    actorId: 'synthetic.clinician',
    occurredAt: '2026-11-21T10:00:00Z',
  });

  it('treats an empty history as a still-requested order', () => {
    expect(currentState([])).toBe('requested');
  });

  it('reads the newest transition as the current task state', () => {
    expect(currentState([event('requested'), event('accepted')])).toBe('accepted');
  });

  it('uses the newest history version for If-Match and falls back to the released version', () => {
    expect(versionFor([event('requested', 4), event('accepted', 5)], 1)).toBe(5);
    expect(versionFor([], 3)).toBe(3);
  });

  it('reuses the key while retrying the same action with the same payload', () => {
    let count = 0;
    const generate = (): string => {
      count += 1;
      return `key-${count}`;
    };
    const first = idempotencyKeyFor(undefined, 'reject', 'missing specimen', generate);
    expect(first).toEqual({ action: 'reject', payload: 'missing specimen', key: 'key-1' });
    expect(idempotencyKeyFor(first, 'reject', 'missing specimen', generate)).toBe(first);
    expect(count).toBe(1);
  });

  it('issues a new key when a different action starts', () => {
    let count = 0;
    const generate = (): string => {
      count += 1;
      return `key-${count}`;
    };
    const first = idempotencyKeyFor(undefined, 'reject', 'missing specimen', generate);
    const second = idempotencyKeyFor(first, 'clarify', 'missing specimen', generate);
    expect(second).toEqual({ action: 'clarify', payload: 'missing specimen', key: 'key-2' });
    expect(second.key).not.toBe(first.key);
  });

  it('issues a new key when the typed payload changes for the same action', () => {
    let count = 0;
    const generate = (): string => {
      count += 1;
      return `key-${count}`;
    };
    const first = idempotencyKeyFor(undefined, 'reject', 'first reason', generate);
    const second = idempotencyKeyFor(first, 'reject', 'corrected reason', generate);
    expect(second.key).not.toBe(first.key);
    expect(second.payload).toBe('corrected reason');
  });
});

describe('order labels and RTL-safe layout', () => {
  it('provides a non-empty label in both bundles for every order key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of ordersKeys) {
        expect(ordersText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(ordersText('ar', 'orders')).toMatch(/[\u0600-\u06FF]/);
  });

  it('maps every state and priority to a defined message key', () => {
    for (const state of ORDER_STATES) {
      expect(ordersKeys).toContain(orderStateKey[state]);
    }
    for (const priority of ['routine', 'urgent', 'stat'] as const) {
      expect(ordersKeys).toContain(priorityMessageKey[priority]);
    }
  });

  it('localises timeline states and actions and keeps unknown wire tokens verbatim', () => {
    expect(orderStateLabel('en', 'requested')).toBe('Requested');
    expect(orderStateLabel('ar', 'requested')).toBe(ordersText('ar', 'stateRequested'));
    expect(orderStateLabel('en', 'unexpected_state')).toBe('unexpected_state');
    expect(timelineActionLabel('en', 'accept')).toBe('accepted');
    expect(timelineActionLabel('ar', 'accept')).toBe(ordersText('ar', 'actionAccept'));
    expect(timelineActionLabel('en', 'future_action')).toBe('future_action');
  });

  it('uses logical styling properties in every feature component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const componentFiles = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(componentFiles.length).toBeGreaterThan(0);
    for (const name of componentFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
