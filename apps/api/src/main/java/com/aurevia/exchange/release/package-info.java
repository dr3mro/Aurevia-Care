/**
 * Patient release decisions, visibility rules and thin HTTP adapters.
 *
 * <p>This package owns release authorization (immediate, timed or held), the
 * append-only decision history, the notification outbox, withdrawal and correction,
 * and the patient-scoped revision query. Every command re-derives membership and
 * roles inside one tenant-guarded transaction. Authorization is a fresh insert;
 * lift, withdraw and correct additionally check a caller version precondition and
 * a legal state transition. State, history, notification and audit commit together.
 * A hold expires only as a review deadline: visibility never opens by elapsed time
 * alone. A correction inherits the original policy and effective instant, points at
 * a different, never-released revision, and supersedes the prior row.</p>
 *
 * <p>The controller only parses the path, body and version header. The package must
 * not trust a client-supplied identity, auto-lift a hold, or write an audit event
 * after a unique-key violation has already aborted the transaction.
 */
package com.aurevia.exchange.release;
