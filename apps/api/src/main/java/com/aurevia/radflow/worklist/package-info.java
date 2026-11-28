/**
 * Performing-site imaging worklist and its scheduling facts.
 *
 * <p>This package owns the forward-only order revision chain, the patient and
 * accession label check, refusal facts for mismatches, site-scoped reads and
 * the hand-off fact that follows a performed study. Completion is terminal.</p>
 *
 * <p>The package must not interpret images, persist its in-process revisions or
 * claim that a read checks a role or an approved-modality catalog.</p>
 */
package com.aurevia.radflow.worklist;
