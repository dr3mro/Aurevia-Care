/**
 * Patient handout policy, deterministic rendering and the handout archive.
 *
 * <p>This package owns the allowed source states, the language-labelled A4
 * handout, the tenant-scoped archive and the version rule that reuses bytes for
 * an unchanged source revision. Print channel approval here is membership in
 * the declared channel vocabulary, not a tenant policy lookup.</p>
 *
 * <p>The package must not mutate clinical sources, decide caller permissions or
 * embed patient data in diagnostics.</p>
 */
package com.aurevia.printing;
