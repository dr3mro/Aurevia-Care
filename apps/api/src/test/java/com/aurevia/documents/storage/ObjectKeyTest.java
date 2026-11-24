package com.aurevia.documents.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * Acceptance tests for tenant-scoped, patient-free object keys (ARC-06).
 */
class ObjectKeyTest {

    @Test
    void keyDoesNotRevealPatient() {
        String patientId = "patient-phi-8f3c1d2e";
        ObjectKey key = ObjectKey.generate("tenant-north", 1);

        assertFalse(key.value().contains(patientId),
                "a key must never carry the patient identifier in clear text");
        assertFalse(key.value().contains("phi"),
                "a key must never carry patient-derived text");
        assertTrue(key.value().startsWith("objects/tenant-north/"),
                "a key is namespaced by its tenant");
        assertNotEquals(patientId, key.objectId(),
                "the object alias must be opaque, never the patient identifier");
    }

    @Test
    void keysAreUniquePerTenant() {
        ObjectKey first = ObjectKey.generate("tenant-north", 1);
        ObjectKey second = ObjectKey.generate("tenant-south", 1);

        assertNotEquals(first.value(), second.value(),
                "the same local object in two tenants must not share a key");
        assertEquals("tenant-north", first.tenantId());
        assertEquals("tenant-south", second.tenantId());
    }

    @Test
    void generatedKeysAreUnique() {
        assertNotEquals(ObjectKey.generate("tenant-north", 1).value(),
                ObjectKey.generate("tenant-north", 1).value(),
                "each generated object gets its own opaque alias");
    }

    @Test
    void differentVersionsProduceDifferentKeys() {
        ObjectKey first = new ObjectKey("tenant-north", "export-1", 1);
        ObjectKey second = new ObjectKey("tenant-north", "export-1", 2);

        assertNotEquals(first.value(), second.value());
    }

    @Test
    void rejectsInvalidTenant() {
        assertThrows(ApiException.class, () -> new ObjectKey("bad tenant!", "export-1", 1));
    }

    @Test
    void rejectsNonPositiveVersion() {
        assertThrows(ApiException.class, () -> new ObjectKey("tenant-north", "export-1", 0));
    }
}
