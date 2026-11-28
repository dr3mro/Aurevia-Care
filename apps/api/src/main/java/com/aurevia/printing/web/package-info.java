/**
 * HTTP-facing handout printing and its sanitized audit trail.
 *
 * <p>This package owns the print controller, default-deny role policy, facility
 * scope check, reprint marker, mediated download and audit facts. Denials are
 * appended inside the guarded transaction before the error is returned.</p>
 *
 * <p>The package must not log clinical text or rendered bytes, infer a caller's
 * role from the request, or claim a content download route that does not exist.</p>
 */
package com.aurevia.printing.web;
