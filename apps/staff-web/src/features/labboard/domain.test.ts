import { describe, expect, it } from 'vitest';

import type { TurnaroundOrder } from './domain';
import {
  isOutage,
  isOverdue,
  minutesBeyondTarget,
  ordersByStage,
  TURNAROUND_STAGES,
} from './domain';
import { emptyBoard, syntheticOrders } from './fixtures';

/** A settled card; every test overrides only the axis it exercises. */
function order(overrides: Partial<TurnaroundOrder> = {}): TurnaroundOrder {
  return {
    id: 'synthetic-order',
    accession: 'SYN-LB-0001',
    stage: 'ordered',
    ageMinutes: 10,
    targetMinutes: 30,
    ...overrides,
  };
}

describe('turnaround board stages', () => {
  it('declares exactly the five columns in reading order', () => {
    expect(TURNAROUND_STAGES).toEqual([
      'ordered',
      'collected',
      'in-analysis',
      'awaiting-verification',
      'released',
    ]);
  });
});

describe('isOverdue (LAB-02)', () => {
  it('marks an order overdue only when its age exceeds the target', () => {
    expect(isOverdue(order({ ageMinutes: 31, targetMinutes: 30 }))).toBe(true);
    expect(isOverdue(order({ ageMinutes: 30, targetMinutes: 30 }))).toBe(false);
    expect(isOverdue(order({ ageMinutes: 29, targetMinutes: 30 }))).toBe(false);
  });
});

describe('minutesBeyondTarget (LAB-02)', () => {
  it('reports the positive overrun and floors a not-yet-overdue order at zero', () => {
    expect(minutesBeyondTarget(order({ ageMinutes: 55, targetMinutes: 30 }))).toBe(25);
    expect(minutesBeyondTarget(order({ ageMinutes: 30, targetMinutes: 30 }))).toBe(0);
    expect(minutesBeyondTarget(order({ ageMinutes: 10, targetMinutes: 30 }))).toBe(0);
  });
});

describe('isOutage', () => {
  it('shows the banner only when the partner interface is down', () => {
    expect(isOutage('down')).toBe(true);
    expect(isOutage('degraded')).toBe(false);
    expect(isOutage('healthy')).toBe(false);
  });
});

describe('ordersByStage', () => {
  it('groups every synthetic order under its own column and keeps column order', () => {
    const grouped = ordersByStage(syntheticOrders);
    expect(TURNAROUND_STAGES.map((stage) => grouped[stage].length)).toEqual([2, 2, 1, 1, 1]);
    expect(grouped.ordered.map((item) => item.id)).toEqual(['SYN-LB-1001', 'SYN-LB-1002']);
    const total = TURNAROUND_STAGES.reduce((sum, stage) => sum + grouped[stage].length, 0);
    expect(total).toBe(syntheticOrders.length);
  });

  it('returns an empty group per column when the board has no orders', () => {
    const grouped = ordersByStage(emptyBoard.orders);
    for (const stage of TURNAROUND_STAGES) {
      expect(grouped[stage]).toEqual([]);
    }
  });
});
