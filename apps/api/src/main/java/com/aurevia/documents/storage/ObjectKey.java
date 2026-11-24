package com.aurevia.documents.storage;

import com.aurevia.documents.ArtifactInputs;
import com.aurevia.documents.UploadValidation;

/**
 * Tenant-scoped, versioned, opaque key for one stored object (ARC-06).
 *
 * <p>A key contains only the owning tenant, an opaque object alias and an
 * immutable version. No patient identifier and no patient-derived value ever
 * reaches the key, so it is safe to log, use as a path or hand to a download
 * handle. Patient ownership stays with the domain store above this port. The
 * tenant namespace keeps two tenants with identical local aliases apart, and
 * the version is part of the key so a changed version addresses a different
 * object and is rejected.</p>
 *
 * @param tenantId owning organization identifier
 * @param objectId opaque, tenant-local object alias
 * @param version  immutable positive object version
 */
public record ObjectKey(String tenantId, String objectId, int version) {

    /**
     * Validate the key components so only a bounded, path-free value can reach
     * the storage adapter.
     */
    public ObjectKey {
        tenantId = UploadValidation.key(tenantId, "tenantId");
        objectId = UploadValidation.key(objectId, "objectId");
        version = ArtifactInputs.positiveVersion(version, "version");
    }

    /**
     * Build a key with a fresh, random, opaque object alias.
     *
     * <p>The alias comes from the platform random source, not from any patient
     * field, so the key cannot be linked back to a patient by enumeration.</p>
     *
     * @param tenantId owning organization identifier
     * @param version  immutable positive object version
     * @return a tenant-scoped key with a new opaque alias
     */
    public static ObjectKey generate(String tenantId, int version) {
        return new ObjectKey(tenantId, UploadValidation.newId(), version);
    }

    /**
     * Return the private, path-free key used as the object's storage identity.
     *
     * @return opaque key containing only the tenant, alias and version
     */
    public String value() {
        return "objects/" + tenantId + "/" + objectId + "/v" + version;
    }
}
