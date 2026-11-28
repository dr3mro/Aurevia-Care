/**
 * Instruction acknowledgment facts: publication, notification dispatch, first
 * in-app view, explicit receipt acknowledgment and overdue follow-up routing.
 *
 * <p>This package owns the fact vocabulary, the bounded action matrix, the
 * tenant-scoped fact store and the HTTP routes that record those facts. A first
 * view and an acknowledgment are separate append-only facts; a receipt confirms
 * one exact revision and never carries clinical content or consent.</p>
 *
 * <p>It must not depend on the conversation, attachment or appointment stores,
 * on clinical result or prescribing workflows, or on a delivery outbox: dispatch
 * is recorded as a fact here, not enqueued as a message.</p>
 */
package com.aurevia.messaging.acknowledgment;
