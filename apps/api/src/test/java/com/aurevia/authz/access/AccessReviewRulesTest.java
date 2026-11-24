package com.aurevia.authz.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded validation tests for the IAM-06 review-closure rules. The Jackson
 * assertion proves the backward-compatible request shape remains deserializable
 * after the explicit outcome was added. Fixtures are synthetic strings only.
 */
class AccessReviewRulesTest {

    @Test
    void outcomeValidationAcceptsEveryClosedReviewOutcome() {
        AccessRules.validateReviewOutcome("approved");
        AccessRules.validateReviewOutcome("concerns_noted");
        AccessRules.validateReviewOutcome("action_required");
        assertTrue(AccessRules.isReviewOutcome("approved"));
    }

    @Test
    void outcomeValidationRejectsAbsentAndUnknownValues() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateReviewOutcome(null)).status());
        ApiException unknown = assertThrows(ApiException.class,
                () -> AccessRules.validateReviewOutcome("unknown"));
        assertEquals(422, unknown.status());
        assertEquals("INVALID_OUTCOME", unknown.code());
        assertTrue(AccessRules.isReviewOutcome(AccessRules.DEFAULT_REVIEW_OUTCOME));
    }

    @Test
    void legacyReviewJsonStillDeserializesWithoutAnOutcome() throws Exception {
        ReviewRequest request = new ObjectMapper().readValue("{\"note\":\"checked\"}", ReviewRequest.class);

        assertEquals("checked", request.note());
        assertNull(request.outcome());
        assertEquals("recorded", AccessRules.DEFAULT_REVIEW_OUTCOME);
    }

    @Test
    void explicitReviewOutcomeIsDeserialized() throws Exception {
        ReviewRequest request = new ObjectMapper().readValue(
                "{\"note\":\"checked\",\"outcome\":\"approved\"}", ReviewRequest.class);

        assertEquals("approved", request.outcome());
    }
}
