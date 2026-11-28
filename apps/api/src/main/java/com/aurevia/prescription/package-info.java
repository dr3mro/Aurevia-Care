/**
 * Prescription drafts, the effective-dated drug catalog and allergy support.
 *
 * <p>This package owns draft creation and medication selection with a version
 * precondition, a documented allergy override bound to the exact revision,
 * versioned catalog search and the write that applies an approved substitution.</p>
 *
 * <p>The package must not perform controlled transmission, render handouts or
 * accept a clinician identity from a request payload.</p>
 */
package com.aurevia.prescription;
