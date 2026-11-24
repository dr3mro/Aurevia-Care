package com.aurevia.clinical.templates;

/**
 * One append-only audit fact for a template command (OPS-02).
 *
 * @param organizationId tenant that owns the fact
 * @param actorSubject   acting subject
 * @param action         command action key
 * @param resourceType   audited resource type
 * @param resourceId     tenant-local resource identifier
 * @param outcome        success, denied, not-found, conflict or invalid outcome
 * @return this record
 */
public record TemplateAuditEvent(String organizationId, String actorSubject, String action,
                                 String resourceType, String resourceId, String outcome) {
}
