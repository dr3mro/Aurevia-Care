import { useSyncExternalStore } from 'react';

/**
 * In-memory read receipts shared between the inbox and the conversation
 * screen. A read is an ephemeral UI acknowledgement of a successful server
 * `read` command; the durable unread watermark stays in the API. Nothing here
 * is written to `localStorage` or `sessionStorage` and every key carries the
 * active organization so two tenants cannot observe each other's receipts.
 */

/** Current receipt overrides, keyed by organization and conversation. */
const receipts = new Map<string, number>();

/** Subscribers notified when a receipt changes. */
const listeners = new Set<() => void>();

/** Build a tenant-scoped key without ever exposing the raw identifiers. */
function receiptKey(organizationId: string, conversationId: string): string {
  return `${organizationId}\u0000${conversationId}`;
}

/** Notify every mounted subscriber after a receipt change. */
function notify(): void {
  for (const listener of listeners) {
    listener();
  }
}

/**
 * Record the unread count left by a successful `read` command.
 *
 * @param organizationId active organization that owns the read
 * @param conversationId opaque conversation identifier
 * @param unreadCount the count the server left after the read
 */
export function markConversationRead(
  organizationId: string,
  conversationId: string,
  unreadCount: number,
): void {
  receipts.set(receiptKey(organizationId, conversationId), unreadCount);
  notify();
}

/** Clear all receipts; used by deterministic tests between scenarios. */
export function resetReadReceipts(): void {
  receipts.clear();
  notify();
}

/** Subscribe to receipt changes; the returned callback unsubscribes. */
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Read the visible unread count for one row. A receipt from a successful read
 * replaces the server count; otherwise the count from the API is shown.
 */
export function useUnreadCount(
  organizationId: string,
  conversationId: string,
  serverCount: number,
): number {
  const receipt = useSyncExternalStore(
    subscribe,
    () => receipts.get(receiptKey(organizationId, conversationId)),
  );
  return receipt ?? serverCount;
}
