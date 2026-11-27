/**
 * Clinical order release, authorization and receiver work-item projection.
 *
 * <p>This package owns the trust-boundary validation, the sending organization's
 * bounded authorization, the tenant-guarded SQL boundary, the immutable release
 * snapshot and fingerprint, the disclosure ledger, and the receiver's one-shot
 * patient verification. Tenant and actor come from the transaction context, never
 * from the request body: membership and role are re-derived for every command, the
 * patient is resolved inside the sender tenant, and the receiver is granted only a
 * bounded work-item projection rather than chart access.</p>
 *
 * <p>The package must not derive permissions from client input, widen an
 * authorization beyond its order code, recipient, purpose, duration or validity
 * window, or place a second persistence boundary beside the repository.
 */
package com.aurevia.exchange.order;
