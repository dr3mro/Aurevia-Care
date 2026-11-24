package com.aurevia.staffmsg.attachments;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.documents.storage.ObjectStorePort;
import com.aurevia.documents.storage.FileSystemObjectStore;
import com.aurevia.staff.StaffRepository;

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
            @Value("${aurevia.attachments.root:${java.io.tmpdir}/aurevia-staff-attachments}") String root) {
        return new FileSystemObjectStore(Path.of(root));
    }

    /**
     * Provide the size and count bounds injected into the domain service.
     *
     * <p>Without this bean the application context cannot construct
     * {@link com.aurevia.staffmsg.attachments.StaffAttachmentService}
     * and every entry point that loads the context fails.</p>
     *
     * @return the default attachment bounds
     */
    @Bean
    public StaffAttachmentLimits staffAttachmentLimits() {
        return new StaffAttachmentLimits();
    }

    /**
     * Provide the single publication seam used by both staff message sends.
     *
     * <p>Re-deriving active roles in the guarded transaction on every publication
     * keeps a revoked membership from widening the audience between a mail or
     * instant send's preflight and its commit.</p>
     *
     * @param attachments attachment domain service
     * @param staff       authoritative active-membership role source
     * @param transactions tenant-guarded transaction template
     * @return the production publication seam
     */
    @Bean
    public StaffAttachmentPublication staffAttachmentPublication(StaffAttachmentService attachments,
                                                                 StaffRepository staff,
                                                                 TransactionTemplate transactions) {
        return new StaffAttachmentPublicationService(attachments, staff, transactions);
    }
}
