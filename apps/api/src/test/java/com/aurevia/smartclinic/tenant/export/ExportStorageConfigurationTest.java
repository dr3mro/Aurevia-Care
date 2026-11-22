package com.aurevia.smartclinic.tenant.export;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.documents.storage.FileSystemObjectStore;
import com.aurevia.smartclinic.documents.storage.ObjectStorePort;

/** OPS-07: the default storage bean binds the private object-store port. */
class ExportStorageConfigurationTest {

    @Test
    void defaultBindingUsesThePrivateFilesystemAdapter() {
        ObjectStorePort port = new ExportStorageConfiguration().exportObjectStore("/tmp/opencode/export-test");
        assertNotNull(port);
        assertInstanceOf(FileSystemObjectStore.class, port);
    }
}
