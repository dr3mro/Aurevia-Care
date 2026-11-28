/**
 * Recipient-side order response workflow, append-only ledgers and HTTP adapter.
 *
 * <p>This package owns the state machine that accepts, rejects, clarifies, proposes
 * and resolves changes, requests and resolves cancellations, and revokes a release.
 * Only an authorized command moves state: a transmission fact never does. Every
 * command locks the order, checks the caller's version, validates the transition and
 * commits the state change, event and audit together; a retained copy is added on
 * acceptance and a downstream notification only on revocation. Retained
 * copies are created only after acceptance, under the purpose and policy captured
 * then; a proposed revision stays in the revision ledger and retrieval keeps serving
 * the last accepted revision until the recipient responds.</p>
 *
 * <p>The controller only parses identifiers, bodies and the version precondition.
 * The package must not serve unretained content, treat a terminal order as writable,
 * or let a cached view bypass the revocation check on retrieval.
 */
package com.aurevia.exchange.response;
