package com.aurevia.smartclinic.staffmsg.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.MessageRule;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.MessagingPolicySettings;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyScope;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;

/** Value-contract acceptance for the messaging policy kernel (MSG-07). */
class MessagingPolicyContractTest {

    private static final Instant EFFECTIVE = Instant.parse("2027-01-01T00:00:00Z");

    private static MessagingPolicySettings settings(Set<MediaType> types, long retentionDays) {
        return new MessagingPolicySettings(MessageRule.ALL_STAFF, types, retentionDays, false, EFFECTIVE, 1L);
    }

    @Test
    void acceptsEveryDocumentedMediaType() {
        for (MediaType type : MediaType.values()) {
            MessagingPolicySettings value = new MessagingPolicySettings(MessageRule.CARE_TEAM,
                    EnumSet.of(type), MessagingPolicySettings.MIN_RETENTION_DAYS, true, EFFECTIVE, 2L);
            assertEquals(Set.of(type), value.allowedTypes());
            assertEquals(MessageRule.CARE_TEAM, value.messageRule());
            assertTrue(value.crossFacilityEnabled());
        }
    }

    @Test
    void rejectsNullAllowedTypes() {
        ApiException error = assertThrows(ApiException.class, () -> settings(null, 90L));
        assertEquals(422, error.status());
        assertEquals("INVALID_ALLOWED_TYPES", error.code());
    }

    @Test
    void rejectsEmptyAllowedTypes() {
        ApiException error = assertThrows(ApiException.class, () -> settings(Set.of(), 90L));
        assertEquals(422, error.status());
        assertEquals("INVALID_ALLOWED_TYPES", error.code());
    }

    @Test
    void rejectsZeroRetentionDays() {
        ApiException error = assertThrows(ApiException.class,
                () -> settings(EnumSet.allOf(MediaType.class), 0L));
        assertEquals(422, error.status());
        assertEquals("INVALID_RETENTION_DAYS", error.code());
    }

    @Test
    void rejectsNegativeRetentionDays() {
        ApiException error = assertThrows(ApiException.class,
                () -> settings(EnumSet.allOf(MediaType.class), -1L));
        assertEquals("INVALID_RETENTION_DAYS", error.code());
    }

    @Test
    void rejectsRetentionDaysAboveMaximum() {
        ApiException error = assertThrows(ApiException.class,
                () -> settings(EnumSet.allOf(MediaType.class), MessagingPolicySettings.MAX_RETENTION_DAYS + 1));
        assertEquals("INVALID_RETENTION_DAYS", error.code());
    }

    @Test
    void acceptsRetentionDaysAtBounds() {
        assertEquals(MessagingPolicySettings.MIN_RETENTION_DAYS,
                settings(EnumSet.allOf(MediaType.class), MessagingPolicySettings.MIN_RETENTION_DAYS).retentionDays());
        assertEquals(MessagingPolicySettings.MAX_RETENTION_DAYS,
                settings(EnumSet.allOf(MediaType.class), MessagingPolicySettings.MAX_RETENTION_DAYS).retentionDays());
    }

    @Test
    void freezesTheAllowedTypesAccessor() {
        MessagingPolicySettings value = settings(EnumSet.allOf(MediaType.class), 90L);
        assertThrows(UnsupportedOperationException.class, () -> value.allowedTypes().add(MediaType.PDF));
    }

    @Test
    void freezesAllowedTypesInput() {
        Set<MediaType> input = EnumSet.allOf(MediaType.class);
        MessagingPolicySettings value = settings(input, 90L);
        input.clear();
        assertEquals(EnumSet.allOf(MediaType.class), value.allowedTypes());
    }

    @Test
    void rejectsNullMessageRuleAndEffectiveFrom() {
        Set<MediaType> types = EnumSet.allOf(MediaType.class);
        assertThrows(NullPointerException.class,
                () -> new MessagingPolicySettings(null, types, 90L, false, EFFECTIVE, 1L));
        assertThrows(NullPointerException.class,
                () -> new MessagingPolicySettings(MessageRule.ALL_STAFF, types, 90L, false, null, 1L));
    }

    @Test
    void retentionShapeMatchesTheMessagingAuditContract() {
        MessagingPolicySettings value = settings(EnumSet.allOf(MediaType.class), 90L);
        RetentionPolicy retention = new RetentionPolicy("org-a", "staff_message", value.retentionDays());
        assertEquals(90L, retention.retentionDays());
    }

    @Test
    void revisionIsAnImmutableHistoryEntry() {
        MessagingPolicySettings value = settings(EnumSet.allOf(MediaType.class), 90L);
        PolicyRevision revision = new PolicyRevision("rev-1", "org-a", PolicyScope.FACILITY,
                "fac-a", value, "admin-a", EFFECTIVE);
        assertEquals("rev-1", revision.id());
        assertEquals("org-a", revision.organizationId());
        assertEquals(PolicyScope.FACILITY, revision.scope());
        assertEquals("fac-a", revision.facilityCode());
        assertEquals(value, revision.settings());
        assertEquals("admin-a", revision.changedBy());
        assertEquals(EFFECTIVE, revision.recordedAt());
    }
}
