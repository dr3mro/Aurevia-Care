import type { Role } from '../../../app/navigation';
import type { CodedEntry } from '../../chart/domain';

/** Business family of an orderable catalog item (LAB-01, RAD-01). */
export type OrderCategory = 'lab' | 'imaging';

/** Approved clinical priority vocabulary, reused from the exchange order domain. */
export type OrderPriority = 'routine' | 'urgent' | 'stat';

/** How a placed order reaches the performing partner. */
export type DeliveryMode = 'electronic' | 'print' | 'both';

/** One versioned, orderable lab test or imaging study for a facility. */
export interface CatalogItem {
  organizationId: string;
  id: string;
  code: string;
  name: string;
  /** Local-script display form; absence is not an unknown state. */
  nameLocal?: string | undefined;
  category: OrderCategory;
  /** Specimen for lab work or modality for imaging, shown as clinical context. */
  specimenOrModality: string;
  /** Default patient preparation text, editable by the clinician before saving. */
  preparationInstructions: string;
  defaultPriority: OrderPriority;
  partnerId: string;
  version: number;
  /** A retired item cannot be selected for a new order. */
  retired?: boolean | undefined;
}

/** Immutable snapshot of a catalog item captured into an order draft. */
export interface SelectedItem {
  itemId: string;
  version: number;
  code: string;
  name: string;
  category: OrderCategory;
}

/** A relevant active order already on the patient's chart, used for duplicate checks. */
export interface ActiveOrder {
  organizationId: string;
  id: string;
  catalogCode: string;
  display: string;
  status: 'draft' | 'placed' | 'delivered' | 'collected';
}

/** All editable content of an order draft, independent of its server version. */
export interface OrderDraftContent {
  patientId: string;
  encounterId: string;
  facilityId: string;
  items: SelectedItem[];
  clinicalQuestion: string;
  priority: OrderPriority;
  preparationInstructions: string;
  deliveryMode: DeliveryMode;
}

/** A saved draft together with the version a subsequent write must match. */
export interface SavedOrderDraft {
  organizationId: string;
  id: string;
  version: number;
  content: OrderDraftContent;
}

/** Roles allowed to place clinical orders; performing roles are deliberately excluded. */
export const ORDERING_ROLES: readonly Role[] = ['clinician'];

/** Clinical allergies shown alongside the order for safety context. */
export type OrderAllergy = CodedEntry;

/** Validation findings, keyed by the field the clinician must correct. */
export type OrderValidationKey = 'noItems' | 'clinicalQuestion';

/** Roles that may place an order: only a clinician makes the clinical decision (PRD-06). */
export function canOrder(role: Role): boolean {
  return ORDERING_ROLES.includes(role);
}

/** Raised when a command carries a version that no longer matches the saved draft. */
export class OrderConflict extends Error {
  /** The server's current draft, supplied so the UI can compare, reload or copy. */
  readonly server: SavedOrderDraft;

  /** Record the draft the server holds at the time of the rejected write. */
  constructor(server: SavedOrderDraft) {
    super(`Stale order draft: expected the caller's version, server holds ${server.version}`);
    this.name = 'OrderConflict';
    this.server = server;
  }
}

/** Build empty draft content for one patient, encounter and facility. */
export function emptyContent(
  patientId: string,
  encounterId: string,
  facilityId: string,
): OrderDraftContent {
  return {
    patientId,
    encounterId,
    facilityId,
    items: [],
    clinicalQuestion: '',
    priority: 'routine',
    preparationInstructions: '',
    deliveryMode: 'both',
  };
}

/** Copy a saved draft's content without sharing its mutable item array. */
export function contentFromDraft(draft: SavedOrderDraft): OrderDraftContent {
  return { ...draft.content, items: [...draft.content.items] };
}

/**
 * Search the orderable catalog for the active tenant.
 *
 * Retired items and other tenants' items are never returned. An empty query
 * returns every active item; otherwise code, Latin name and local name are
 * matched case-insensitively so an Arabic query works under either locale.
 */
export function searchCatalog(
  catalog: readonly CatalogItem[],
  organizationId: string,
  query: string,
): CatalogItem[] {
  const term = query.trim().toLowerCase();
  return catalog.filter((item) => {
    if (item.organizationId !== organizationId || item.retired === true) {
      return false;
    }
    if (term === '') {
      return true;
    }
    return (
      item.code.toLowerCase().includes(term) ||
      item.name.toLowerCase().includes(term) ||
      (item.nameLocal ?? '').toLowerCase().includes(term)
    );
  });
}

/** Capture one catalog item in the draft, prefilling preparation only when blank. */
export function selectCatalogItem(
  content: OrderDraftContent,
  item: CatalogItem,
): OrderDraftContent {
  if (content.items.some((selected) => selected.itemId === item.id)) {
    return content;
  }
  const selected: SelectedItem = {
    itemId: item.id,
    version: item.version,
    code: item.code,
    name: item.name,
    category: item.category,
  };
  return {
    ...content,
    items: [...content.items, selected],
    preparationInstructions:
      content.preparationInstructions.trim() === ''
        ? item.preparationInstructions
        : content.preparationInstructions,
  };
}

/** Remove one captured item by id; a missing id leaves the draft untouched. */
export function removeSelectedItem(content: OrderDraftContent, itemId: string): OrderDraftContent {
  const remaining = content.items.filter((item) => item.itemId !== itemId);
  return remaining.length === content.items.length ? content : { ...content, items: remaining };
}

/** True when at least one selected item is an imaging study (RAD-01). */
export function hasImaging(content: OrderDraftContent): boolean {
  return content.items.some((item) => item.category === 'imaging');
}

/**
 * Validate a draft at the UI boundary.
 *
 * An empty selection is reported first; imaging additionally requires a
 * clinical question, because a study cannot be performed without one (RAD-01).
 */
export function validateContent(content: OrderDraftContent): OrderValidationKey[] {
  const findings: OrderValidationKey[] = [];
  if (content.items.length === 0) {
    findings.push('noItems');
  }
  if (hasImaging(content) && content.clinicalQuestion.trim() === '') {
    findings.push('clinicalQuestion');
  }
  return findings;
}

/** Find active orders in the same tenant whose catalog code is already selected. */
export function duplicateActiveOrders(
  content: OrderDraftContent,
  activeOrders: readonly ActiveOrder[],
  organizationId: string,
): ActiveOrder[] {
  const selectedCodes = new Set(content.items.map((item) => item.code));
  return activeOrders.filter(
    (order) => order.organizationId === organizationId && selectedCodes.has(order.catalogCode),
  );
}
