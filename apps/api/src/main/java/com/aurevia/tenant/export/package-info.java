/**
 * Tenant export manifest values, checksums, permission guard, durable job store and orchestration with auditable retention.
 *
 * <p>Authorization is re-derived from the guard, digests are recomputed before publication, and the manifest audit records why each source was included. It must not export across tenants, trust a client-supplied checksum, or delete an object before its approved retention ends.</p>
 */
package com.aurevia.tenant.export;
