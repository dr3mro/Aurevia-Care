/**
 * Versioned onboarding manifests, deterministic dry runs, controlled batches, provenance, digests and authorized sign-off.
 *
 * <p>The dry run is read-only, checkpoints make a batch resumable, and the named approver is bound to the authenticated subject. It must not create a new clinical attestation, change the source of truth before sign-off, or invent provenance that the manifest does not carry.</p>
 */
package com.aurevia.tenant.importing;
