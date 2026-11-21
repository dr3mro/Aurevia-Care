import type { TurnaroundBoard, TurnaroundOrder } from './domain';

/**
 * Synthetic laboratory turnaround snapshot. No patient, partner or real
 * identifier is represented; accessions are opaque and carry no patient data.
 */

/** Two orders in Ordered: one on track, one overdue by 25 minutes. */
const ordered: readonly TurnaroundOrder[] = [
  {
    id: 'SYN-LB-1001',
    accession: 'LB-1042',
    stage: 'ordered',
    ageMinutes: 15,
    targetMinutes: 30,
  },
  {
    id: 'SYN-LB-1002',
    accession: 'SYN-LB-1002',
    stage: 'ordered',
    ageMinutes: 55,
    targetMinutes: 30,
  },
];

/** Two orders in Collected: one on track, one overdue by 45 minutes. */
const collected: readonly TurnaroundOrder[] = [
  {
    id: 'SYN-LB-1003',
    accession: 'LB-1043',
    stage: 'collected',
    ageMinutes: 40,
    targetMinutes: 45,
  },
  {
    id: 'SYN-LB-1004',
    accession: 'LB-1044',
    stage: 'collected',
    ageMinutes: 90,
    targetMinutes: 45,
  },
];

/** One order in analysis, overdue by 10 minutes. */
const inAnalysis: readonly TurnaroundOrder[] = [
  {
    id: 'SYN-LB-1005',
    accession: 'LB-1045',
    stage: 'in-analysis',
    ageMinutes: 70,
    targetMinutes: 60,
  },
];

/** One order awaiting verification, on track. */
const awaitingVerification: readonly TurnaroundOrder[] = [
  {
    id: 'SYN-LB-1006',
    accession: 'LB-1046',
    stage: 'awaiting-verification',
    ageMinutes: 50,
    targetMinutes: 90,
  },
];

/** One released order at exactly its target, so it is not overdue. */
const released: readonly TurnaroundOrder[] = [
  {
    id: 'SYN-LB-1007',
    accession: 'LB-1047',
    stage: 'released',
    ageMinutes: 120,
    targetMinutes: 120,
  },
];

/** All synthetic orders in board order, across the five columns. */
export const syntheticOrders: readonly TurnaroundOrder[] = [
  ...ordered,
  ...collected,
  ...inAnalysis,
  ...awaitingVerification,
  ...released,
];

/** Snapshot with the partner interface down, used to exercise the outage route. */
export const outageBoard: TurnaroundBoard = {
  interfaceHealth: 'down',
  orders: syntheticOrders,
};

/** Identical orders with a healthy interface, used to prove the banner is conditional. */
export const healthyBoard: TurnaroundBoard = {
  interfaceHealth: 'healthy',
  orders: syntheticOrders,
};

/** A board with no orders, used to exercise every column's empty state. */
export const emptyBoard: TurnaroundBoard = {
  interfaceHealth: 'healthy',
  orders: [],
};
