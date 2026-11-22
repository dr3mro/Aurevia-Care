package com.aurevia.smartclinic.staffmsg.attachments;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.smartclinic.documents.storage.ObjectStorePort;
import com.aurevia.smartclinic.documents.storage.FileSystemObjectStore;

/**
 * Wiring for the staff-attachment private object store (MSG-03, ARC-06).
 *
 * <p>The default adapter keeps originals beneath a configurable local directory
 * for development and synthetic verification; a managed object-store adapter can
 * replace this bean in a deployed profile without changing the domain service.</p>
 */
@Configuration
public class StaffAttachmentConfiguration {

    /**
     * Provide the private object-store port for original attachment bytes.
     *
     * @param root directory under which original bytes are written
     * @return the local filesystem adapter
     */
    @Bean
    public ObjectStorePort staffAttachmentObjectStore(
            @Value("${smartclinic.attachments.root:${java.io.tmpdir}/smartclinic-staff-attachments}") String root) {
        return new FileSystemObjectStore(Path.of(root));
    }

    /**
     * Provide the size and count bounds injected into the domain service.
     *
     * <p>Without this bean the application context cannot construct
     * {@link com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService}
     * and every entry point that loads the context fails.</p>
     *
     * @return the default attachment bounds
     */
    @Bean
    public StaffAttachmentLimits staffAttachmentLimits() {
        return new StaffAttachmentLimits();
    }
}
