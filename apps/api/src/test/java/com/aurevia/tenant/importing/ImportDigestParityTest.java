package com.aurevia.tenant.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/** OPS-08: both import callers share one digest rule and one failure contract. */
class ImportDigestParityTest {

    private static final byte[] FIXED = HexFormat.of().parseHex("5a".repeat(32));
    private static final byte[] DATA = "synthetic-parity-input".getBytes(StandardCharsets.UTF_8);

    @Test
    void bothCallersProduceTheSameLowercaseHexDigest() {
        ImportDigests digests = new ImportDigests(FixedDigest::new);
        ControlledImportService controlled = new ControlledImportService(digests);
        OnboardingImportService onboarding = new OnboardingImportService(digests);
        String expected = HexFormat.of().formatHex(FIXED);

        String controlledDigest = controlled.digest(DATA);
        String onboardingDigest = onboarding.digest(DATA);

        assertEquals(expected, controlledDigest);
        assertEquals(expected, onboardingDigest);
        assertEquals(controlledDigest, onboardingDigest);
    }

    @Test
    void bothCallersFailClosedWithTheSameError() {
        ImportDigests digests = new ImportDigests(() -> {
            throw new NoSuchAlgorithmException("missing");
        });
        ControlledImportService controlled = new ControlledImportService(digests);
        OnboardingImportService onboarding = new OnboardingImportService(digests);

        IllegalStateException fromControlled = assertThrows(IllegalStateException.class,
                () -> controlled.digest(DATA));
        IllegalStateException fromOnboarding = assertThrows(IllegalStateException.class,
                () -> onboarding.digest(DATA));

        assertEquals(fromControlled.getMessage(), fromOnboarding.getMessage());
        assertEquals("SHA-256 is unavailable", fromControlled.getMessage());
    }

    /** A digest stub that always yields the same fixed bytes, so parity is deterministic. */
    private static final class FixedDigest extends MessageDigest {

        FixedDigest() {
            super("SHA-256");
        }

        @Override
        protected void engineUpdate(byte input) {
            // No state needed: the digest is fixed.
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            // No state needed: the digest is fixed.
        }

        @Override
        protected byte[] engineDigest() {
            return FIXED.clone();
        }

        @Override
        protected void engineReset() {
            // No state needed: the digest is fixed.
        }
    }
}
