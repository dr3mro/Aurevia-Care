package com.aurevia.messaging.enrollment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

/**
 * Platform SHA-256 digest factory for patient-access credentials.
 *
 * <p>The factory is a Spring bean so the JDBC adapter can hash opaque invitation
 * codes and access tokens before persisting them. It is also the extension point
 * used by the service to digest site-verified facts (IAM-05).</p>
 */
@Component
public final class Sha256DigestFactory implements PatientAccessService.DigestFactory {

    /**
     * Create a fresh platform SHA-256 digest.
     *
     * @return a new message digest
     * @throws NoSuchAlgorithmException when the platform has no SHA-256 provider
     */
    @Override
    public MessageDigest create() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }
}
