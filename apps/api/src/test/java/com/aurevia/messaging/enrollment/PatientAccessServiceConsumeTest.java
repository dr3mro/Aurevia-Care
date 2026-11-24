package com.aurevia.messaging.enrollment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;

/**
 * Deterministic coverage of the atomic-consume failure branch in
 * {@link com.aurevia.messaging.enrollment.PatientAccessService}:
 * a request that loses the compare-and-set must not create an account.
 */
class PatientAccessServiceConsumeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CODE = "opaque-code";
    private static final String FACTS = "site-verified-facts";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Test
    void aRequestThatLosesTheAtomicConsumeCannotEnroll() throws Exception {
        String factDigest = hex(MessageDigest.getInstance("SHA-256")
                .digest(FACTS.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Invitation pending = new Invitation(CODE, ORG, CHART, factDigest, NOW.plusSeconds(600),
                InvitationState.PENDING, 0, "reception", NOW);
        PatientAccessStore store = mock(PatientAccessStore.class);
        when(store.invitation(ORG, CODE)).thenReturn(Optional.of(pending));
        when(store.consumeInvitation(ORG, CODE)).thenReturn(false);
        PatientAccessService service = new PatientAccessService(store, new EnrollmentActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        PatientAccessActor patient = PatientAccessActor.of(ORG, "syn-patient", EnrollmentActions.PATIENT);

        ApiException rejected = assertThrows(ApiException.class,
                () -> service.enroll(patient, new EnrollRequest(CODE, FACTS)));
        assertEquals(409, rejected.status());
        assertEquals("INVITATION_REUSED", rejected.code());
        verify(store, never()).putAccount(anyString(), any());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            result.append(Character.forDigit((current >> 4) & 0xf, 16));
            result.append(Character.forDigit(current & 0xf, 16));
        }
        return result.toString();
    }
}
