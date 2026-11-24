package com.aurevia.it.exchangeorder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.order.AuthorizationRequest;
import com.aurevia.exchange.order.AuthorizationView;
import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.exchange.order.VerifyPatientRequest;
import com.aurevia.exchange.order.WorkItemView;

/** Generated record contracts for the XCH-01 value types. */
class OrderRecordsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static void contract(Object first, Object second) {
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void requestAndValueRecordsHaveValueSemantics() {
        contract(new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", ORG, "dr-a", "lab-queue", 30),
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", ORG, "dr-a", "lab-queue", 30));
        contract(new AuthorizationRequest("CBC", DEST, "lab review", 60, 7),
                new AuthorizationRequest("CBC", DEST, "lab review", 60, 7));
        contract(new VerifyPatientRequest(PATIENT, "MRN-1", "CLINIC", "attested"),
                new VerifyPatientRequest(PATIENT, "MRN-1", "CLINIC", "attested"));
        contract(new AuthorizationView(ORG, ORG, "CBC", DEST, "lab review", 60,
                        NOW.minusSeconds(60), NOW.plusSeconds(60), true),
                new AuthorizationView(ORG, ORG, "CBC", DEST, "lab review", 60,
                        NOW.minusSeconds(60), NOW.plusSeconds(60), true));
        contract(new OrderView(ORG, 1, PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "clinician-a", "dr-a", "lab-queue", ORG, "s1", "f1", "d1", "w1", NOW),
                new OrderView(ORG, 1, PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "clinician-a", "dr-a", "lab-queue", ORG, "s1", "f1", "d1", "w1", NOW));
        contract(new WorkItemView("w1", ORG, DEST, ORG, PATIENT, "MRN-1", "CLINIC", null, false,
                        "routine", "question", "lab-queue", "f1", "awaiting_local_patient", NOW),
                new WorkItemView("w1", ORG, DEST, ORG, PATIENT, "MRN-1", "CLINIC", null, false,
                        "routine", "question", "lab-queue", "f1", "awaiting_local_patient", NOW));
    }
}
