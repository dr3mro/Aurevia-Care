import type { InboxItemResponse, StatusFilter } from './types';

/**
 * Compose the dosing instructions printed on a pharmacy card from the bounded
 * snapshot. The route and frequency are clinician data from the routed
 * prescription, never text typed by pharmacy staff.
 */
export function dosingInstructions(item: InboxItemResponse): string {
  return `${item.strength}; ${item.route}; ${item.frequency}`;
}

/** Translate a UI filter into the API status query; `all` sends no filter. */
export function filterQuery(filter: StatusFilter): string | undefined {
  return filter === 'all' ? undefined : filter;
}

/** A reason or clarification question must survive trimming before it is sent. */
export function hasReason(value: string): boolean {
  return value.trim().length > 0;
}
