package com.aurevia.clinical.templates;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for versioned templates, dictionaries and audit facts.
 *
 * <p>Every operation is tenant scoped: the organization identifier is part of
 * the key, so a peer tenant can reuse the same local keys and version numbers
 * without seeing or overwriting this tenant's data (ARC-02).</p>
 */
public interface TemplateStore {

    /**
     * Read one field dictionary.
     *
     * @param organizationId active organization
     * @param key            tenant-local dictionary key
     * @return the dictionary when present in this tenant
     */
    Optional<FieldDictionary> dictionary(String organizationId, String key);

    /**
     * Store or replace a field dictionary within the tenant.
     *
     * @param organizationId active organization
     * @param dictionary     dictionary to store
     */
    void putDictionary(String organizationId, FieldDictionary dictionary);

    /**
     * Read one exact template version.
     *
     * @param organizationId active organization
     * @param kind           template family
     * @param version        version number
     * @return the version when present in this tenant
     */
    Optional<TemplateVersion> version(String organizationId, TemplateKind kind, int version);

    /**
     * List all versions of a template family, oldest first.
     *
     * @param organizationId active organization
     * @param kind           template family
     * @return versions in ascending order
     */
    List<TemplateVersion> versions(String organizationId, TemplateKind kind);

    /**
     * Store or replace one template version within the tenant.
     *
     * @param organizationId active organization
     * @param version        version to store
     */
    void putVersion(String organizationId, TemplateVersion version);

    /**
     * Atomically allocate a new pending version for a family.
     *
     * <p>Allocation and insertion are one operation so two concurrent callers
     * cannot claim the same version number. The organization identifier is part
     * of the key, so peer tenants have independent sequences.</p>
     *
     * @param organizationId active organization
     * @param kind           template family
     * @param fields         field definitions of the new version
     * @return the newly allocated pending version
     */
    TemplateVersion addVersion(String organizationId, TemplateKind kind, List<FieldDefinition> fields);

    /**
     * Persist a saved note revision with its field-meaning snapshot.
     *
     * @param organizationId active organization
     * @param revision       saved note and the meanings captured with it
     */
    void saveNoteRevision(String organizationId, SavedNoteRevision revision);

    /**
     * Read one saved note revision with the field meanings it was saved with.
     *
     * @param organizationId active organization
     * @param noteId         tenant-local note identifier
     * @return the saved revision when visible in this tenant
     */
    Optional<SavedNoteRevision> noteRevision(String organizationId, String noteId);

    /**
     * Read the tenant's append-only audit trail.
     *
     * @param organizationId active organization
     * @return audit facts in insertion order
     */
    List<TemplateAuditEvent> audit(String organizationId);

    /**
     * Append one audit fact to the tenant's trail.
     *
     * @param event fact to append
     */
    void appendAudit(TemplateAuditEvent event);
}
