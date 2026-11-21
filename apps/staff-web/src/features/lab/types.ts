/**
 * Wire types for the laboratory accession screens (LAB-01).
 *
 * Field names mirror `LabAccession`, `AccessionEvent`, `AccessionView` and
 * `AccessionPage` in `com.aurevia.smartclinic.lab.accession`. No patient data,
 * credential or real identifier ever appears in a fixture built from these
 * types.
 */

/** The five authoritative lifecycle states of one accession (LAB-01). */
export type AccessionState =
  | 'accessioned'
  | 'collected'
  | 'received'
  | 'rejected'
  | 'recollected';

/** A command that can be issued against an accession (LAB-01). */
export type AccessionAction = 'collect' | 'receive' | 'reject' | 'recollect';

/** One tenant-owned accession projection returned by the API. */
export interface LabAccession {
  id: string;
  version: number;
  accessionNo: string;
  accessionIssuer: string;
  patientId: string;
  sourceOrderRef: string;
  state: AccessionState;
  specimenType: string | null;
  siteCode: string | null;
  collector: string | null;
  collectedAt: string | null;
  receivedAt: string | null;
  quality: string | null;
  rejectionReason: string | null;
  rejectionNotification: string | null;
  replacesAccessionId: string | null;
}

/** One immutable step in an accession's history. */
export interface AccessionEvent {
  id: string;
  accessionId: string;
  accessionVersion: number;
  action: string;
  fromState: string | null;
  toState: string;
  reason: string | null;
  matchedPatientId: string | null;
  occurredAt: string;
}

/** One accession together with its immutable event history. */
export interface AccessionView {
  accession: LabAccession;
  events: AccessionEvent[];
}

/** One cursor-paginated page of accession views. */
export interface AccessionPage {
  items: AccessionView[];
  nextCursor: string | null;
}

/** Body of `POST /api/v1/lab/accessions`. */
export type AccessionBody = {
  accessionNo: string;
  accessionIssuer: string;
  patientId: string;
  sourceOrderRef: string;
};

/** Body of `POST /api/v1/lab/accessions/{id}/collect`. */
export type CollectBody = {
  patientId: string;
  accessionNo: string;
  accessionIssuer: string;
  specimenType: string;
  siteCode: string;
  collector: string;
  collectedAt: string;
  expectedVersion: number;
};

/** Body of `POST /api/v1/lab/accessions/{id}/receive`. */
export type ReceiveBody = {
  patientId: string;
  accessionNo: string;
  accessionIssuer: string;
  quality: string;
  receivedAt: string;
  expectedVersion: number;
};

/** Body of `POST /api/v1/lab/accessions/{id}/reject`. */
export type RejectBody = {
  reason: string;
  notification: string;
  expectedVersion: number;
};

/** Body of `POST /api/v1/lab/accessions/{id}/recollect`. */
export type RecollectBody = {
  accessionNo: string;
  accessionIssuer: string;
  expectedVersion: number;
};

/** A resolved command carrying the body each endpoint expects. */
export type ActionPayload =
  | { action: 'collect'; body: CollectBody }
  | { action: 'receive'; body: ReceiveBody }
  | { action: 'reject'; body: RejectBody }
  | { action: 'recollect'; body: RecollectBody };

/** Query supported by `GET /api/v1/lab/accessions`. */
export interface AccessionListQuery {
  cursor?: string | undefined;
  state?: AccessionState | undefined;
  limit?: number | undefined;
}
