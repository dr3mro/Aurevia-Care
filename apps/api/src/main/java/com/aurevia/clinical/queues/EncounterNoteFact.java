package com.aurevia.clinical.queues;

import com.aurevia.clinical.notes.ClinicalNoteRevision;

/**
 * Tenant-scoped wrapper around an immutable clinical note revision.
 *
 * <p>A revision does not carry an organization id because it is already read
 * inside a tenant-guarded transaction. Wrapping it makes the tenant explicit for
 * the queue projection so a caller cannot accidentally mix two tenants' notes.</p>
 *
 * @param organizationId owning tenant
 * @param revision       note revision contributing its state and encounter id
 * @return the immutable fact
 */
public record EncounterNoteFact(String organizationId, ClinicalNoteRevision revision) {
}
