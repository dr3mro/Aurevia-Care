/**
 * Staff-message governance contracts, role matrix, append-only audit facts, legal holds, retention policy view and HTTP surface.
 *
 * <p>Audit facts are non-sensitive and append-only, and role decisions re-derive effective roles from the tenant-guarded membership fold. It must not put message content in an audit fact, treat an ungated read as an administrator action, or bypass tenant scope.</p>
 */
package com.aurevia.staffmsg.governance;
