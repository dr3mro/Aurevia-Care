import type { PartnerBoardWire, WaitingItemWire } from './domain';

/**
 * Synthetic partner backlog for the outage board. No partner, patient or real
 * identifier is represented. Identifiers are opaque and carry no patient data.
 */

/** Observation instants are fixed so rendered strings are deterministic. */
const OBSERVED_AT = '2026-11-21T10:15:00Z';

/** A lab item whose transmission failed while the workflow looks complete. */
const labFailed: WaitingItemWire = {
  id: 'lab-wait-1',
  reference: 'LAB-1042',
  itemType: 'order',
  ageMinutes: 47,
  workflow: 'accepted',
  transmission: 'failed',
  review: 'unreviewed',
  recipientResponse: null,
};

/** A lab order sent to a partner whose clinical review is still unreviewed. */
const labUnreviewed: WaitingItemWire = {
  id: 'lab-wait-2',
  reference: 'LAB-1043',
  itemType: 'order',
  ageMinutes: 12,
  workflow: 'requested',
  transmission: 'sent',
  review: 'unreviewed',
  recipientResponse: null,
};

/** A settled lab order that is waiting but not stuck. */
const labOnTrack: WaitingItemWire = {
  id: 'lab-wait-3',
  reference: 'LAB-1044',
  itemType: 'order',
  ageMinutes: 6,
  workflow: 'completed',
  transmission: 'delivered',
  review: 'resolved',
  recipientResponse: 'accepted',
};

/** The degraded lab partner: three waiting, two stuck. */
export const labBoard: PartnerBoardWire = {
  kind: 'lab',
  displayName: 'مختبر النور',
  health: 'degraded',
  lastCheckedAt: OBSERVED_AT,
  timeZone: 'UTC',
  waiting: [labFailed, labUnreviewed, labOnTrack],
};

/** An imaging order held by a clarification request and a settled study. */
export const imagingBoard: PartnerBoardWire = {
  kind: 'imaging',
  displayName: 'Synthetic Imaging Partner',
  health: 'down',
  lastCheckedAt: OBSERVED_AT,
  timeZone: 'UTC',
  waiting: [
    {
      id: 'img-wait-1',
      reference: 'RAD-2201',
      itemType: 'order',
      ageMinutes: 95,
      workflow: 'processing',
      transmission: 'pending',
      review: 'action-pending',
      recipientResponse: 'clarification-requested',
    },
    {
      id: 'img-wait-2',
      reference: 'RAD-2202',
      itemType: 'order',
      ageMinutes: 8,
      workflow: 'accepted',
      transmission: 'delivered',
      review: 'acknowledged',
      recipientResponse: 'accepted',
    },
  ],
};

/**
 * A pharmacy prescription delivered to the partner but left unreviewed — a
 * transmission acknowledgement must not close the clinical task (XCH-02) —
 * and one that is fully settled.
 */
export const pharmacyBoard: PartnerBoardWire = {
  kind: 'pharmacy',
  displayName: 'Synthetic Pharmacy Partner',
  health: 'healthy',
  lastCheckedAt: OBSERVED_AT,
  timeZone: 'UTC',
  waiting: [
    {
      id: 'rx-wait-1',
      reference: 'RX-3301',
      itemType: 'prescription',
      ageMinutes: 33,
      workflow: 'accepted',
      transmission: 'delivered',
      review: 'unreviewed',
      recipientResponse: 'accepted',
    },
    {
      id: 'rx-wait-2',
      reference: 'RX-3302',
      itemType: 'prescription',
      ageMinutes: 4,
      workflow: 'processing',
      transmission: 'delivered',
      review: 'acknowledged',
      recipientResponse: 'accepted',
    },
  ],
};

/** All three partners, in board order. */
export const syntheticBoards: readonly PartnerBoardWire[] = [labBoard, imagingBoard, pharmacyBoard];

/** A partner with an empty backlog, used to exercise the explicit empty state. */
export const emptyBoard: PartnerBoardWire = {
  ...labBoard,
  displayName: 'Synthetic Empty Lab',
  health: 'healthy',
  waiting: [],
};
