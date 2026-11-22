package com.aurevia.smartclinic.tenant.export;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.smartclinic.documents.storage.FileSystemObjectStore;
import com.aurevia.smartclinic.documents.storage.ObjectStorePort;

/**
 * Object-store binding for tenant export artifacts (OPS-07).
 *
 * <p>The default is the private local-filesystem adapter used by development
 * and tests. A managed object-store adapter supplied by the deployment binds
 * the same port and replaces this bean, so no domain code changes between
 * environments. Keys are tenant-scoped and free of patient-derived values.</p>
 */
@Configuration
public class ExportStorageConfiguration {

    /**
     * Create the default private object store for exported artifacts.
     *
     * @param storagePath directory under which private objects are written
     * @return the object-store port used by the export service
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStorePort.class)
    ObjectStorePort exportObjectStore(
            @Value("${smartclinic.export.storage-path:${java.io.tmpdir}/smartclinic-exports}")
            String storagePath) {
        return new FileSystemObjectStore(Path.of(storagePath));
    }
}
