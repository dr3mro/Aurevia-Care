/**
 * Pure domain model for the performing-site imaging worklist (RAD-01).
 *
 * The screen shows one forward-only chain — received, scheduled, performed,
 * reported — scoped to an active tenant and one performing site. A study is
 * linked only after the patient and accession labels supplied by the partner
 * workstation agree with the order; this module states that rule without ever
 * fetching images or contacting a network.
 */

/** The four forward-only steps of an order on the performing-site worklist. */
export const STATUS_FLOW = ['received', 'scheduled', 'performed', 'reported'] as const;

/** Worklist filter options: one per status plus the unfiltered view. */
export const STATUS_FILTERS = ['all', ...STATUS_FLOW] as const;

/** Approved imaging modalities captured at scheduling. */
export const MODALITIES = ['ct', 'mri', 'xray', 'ultrasound', 'pet'] as const;

/** One step of the performing-site worklist. */
export type WorklistStatus = (typeof STATUS_FLOW)[number];

/** A status filter, or `all` for every step. */
export type StatusFilter = (typeof STATUS_FILTERS)[number];

/** An approved imaging modality in wire form. */
export type Modality = (typeof MODALITIES)[number];

/** Clinical urgency recorded when the order was received. */
export type Urgency = 'routine' | 'urgent' | 'stat';

/** One tenant-scoped order row of the performing-site worklist. */
export interface ImagingWorklistOrder {
  /** Organization identifier; always the first component of a row identity. */
  organizationId: string;
  /** Tenant-local order identifier; two tenants may use the same value. */
  orderId: string;
  /** Performing site the row belongs to; a second scope beyond the tenant. */
  performingSiteId: string;
  /** Opaque matched patient identifier shown alongside the row. */
  patientId: string;
  /** Local order code issued by the requesting organization. */
  orderCode: string;
  urgency: Urgency;
  status: WorklistStatus;
  /** Optimistic version, advanced by every command. */
  version: number;
  /** ISO-8601 scheduled instant, absent until scheduling. */
  scheduledAt: string | null;
  modality: Modality | null;
  accession: string | null;
  accessionIssuer: string | null;
  /** External DICOM Study Instance UID, absent until a verified link. */
  studyInstanceUid: string | null;
}

/** Outcome of comparing the workstation labels with the order. */
export interface LabelCheckResult {
  /** True only when both labels match; gates the link action. */
  ok: boolean;
  patientMatches: boolean;
  accessionMatches: boolean;
}

/**
 * Return the immediate next status, or undefined at the terminal step.
 *
 * @param status current worklist status
 * @returns the next forward status, if any
 */
export function nextStatus(status: WorklistStatus): WorklistStatus | undefined {
  return STATUS_FLOW[STATUS_FLOW.indexOf(status) + 1];
}

/**
 * Compare the labels entered by the radiology staff with the order. The server
 * repeats this check on link; showing it first prevents a wrong-patient attempt
 * from ever being sent (RAD-01).
 *
 * @param order current tenant-scoped order
 * @param patientId patient label read from the partner workstation
 * @param accession accession label read from the partner workstation
 * @returns both comparison flags and their combined verdict
 */
export function checkPatientAndAccession(
  order: ImagingWorklistOrder,
  patientId: string,
  accession: string,
): LabelCheckResult {
  const patientMatches = order.patientId === patientId;
  const accessionMatches = order.accession === accession;
  return { ok: patientMatches && accessionMatches, patientMatches, accessionMatches };
}

/** An order can be scheduled only while it is received. */
export function canSchedule(order: ImagingWorklistOrder): boolean {
  return order.status === 'received';
}

/** An order can be linked only while scheduled and after an accession exists. */
export function canLinkStudy(order: ImagingWorklistOrder): boolean {
  return order.status === 'scheduled' && order.accession !== null;
}

/** The performed study can be handed off to the referrer only once. */
export function canHandoff(order: ImagingWorklistOrder): boolean {
  return order.status === 'performed';
}

/**
 * Scope orders to one tenant and one performing site. Identical local ids in a
 * different tenant or site are deliberately never returned.
 *
 * @param orders full synthetic pool
 * @param organizationId active organization
 * @param siteId performing site acting on the worklist
 * @returns the scoped rows in the order supplied
 */
export function ordersForSite(
  orders: readonly ImagingWorklistOrder[],
  organizationId: string,
  siteId: string,
): ImagingWorklistOrder[] {
  return orders.filter(
    (order) => order.organizationId === organizationId && order.performingSiteId === siteId,
  );
}

/**
 * Narrow a scoped worklist to one status, or keep every row for `all`.
 *
 * @param orders tenant- and site-scoped orders
 * @param filter selected status filter
 * @returns the rows visible under the filter
 */
export function filterOrders(
  orders: readonly ImagingWorklistOrder[],
  filter: StatusFilter,
): ImagingWorklistOrder[] {
  return filter === 'all' ? [...orders] : orders.filter((order) => order.status === filter);
}
