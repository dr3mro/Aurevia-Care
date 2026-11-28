/**
 * Identifier-only conversation events, the event hub, bounded cursor pages, live subscriptions and tenant-safe rebinding.
 *
 * <p>Both polling and streaming apply the same visibility filter; polling is bounded and the stream closes when authorization or visibility is lost. It must not carry message content, leak identifiers from a removed conversation, or let an async callback keep the appending thread context.</p>
 */
package com.aurevia.staffmsg.realtime;
