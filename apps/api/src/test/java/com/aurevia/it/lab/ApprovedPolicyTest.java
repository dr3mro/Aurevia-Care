package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabPolicyCatalog.CriticalPolicy;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;

/** LAB-02 and ADM-01: only approved, effective, versioned ranges and critical policies apply. */
class ApprovedPolicyTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static ReferenceRange range(int version, boolean approved, Instant from, Instant to) {
        return new ReferenceRange("GLU", "mg/dL", new BigDecimal("70"), new BigDecimal("110"),
                version, approved, from, to);
    }

    private static CriticalPolicy critical(int version, boolean approved) {
        return new CriticalPolicy("GLU", version, new BigDecimal("20"), new BigDecimal("400"),
                approved, FROM, TO, "synthetic-owner", "synthetic-coverage-queue",
                Duration.ofMinutes(15), "synthetic-escalation");
    }

    @Test
    void referenceRangeFlagsValuesAgainstApprovedBounds() {
        ReferenceRange effective = range(1, true, FROM, TO);
        assertEquals(AbnormalFlag.LOW, effective.flag(new BigDecimal("69.9")));
        assertEquals(AbnormalFlag.HIGH, effective.flag(new BigDecimal("110.1")));
        assertEquals(AbnormalFlag.NONE, effective.flag(new BigDecimal("90")));
        assertEquals(new BigDecimal("70"), effective.low());
        assertEquals(new BigDecimal("110"), effective.high());
    }

    @Test
    void rangeIsEffectiveOnlyWhenApprovedAndInsideItsWindow() {
        assertTrue(range(1, true, FROM, TO).effectiveAt(AT));
        assertTrue(range(1, true, FROM, null).effectiveAt(AT));
        assertEquals(false, range(1, false, FROM, TO).effectiveAt(AT));
        assertEquals(false, range(1, true, AT.plusSeconds(1), TO).effectiveAt(AT));
        assertEquals(false, range(1, true, FROM, AT).effectiveAt(AT));
        assertEquals(true, range(1, true, FROM, AT.plusSeconds(1)).effectiveAt(AT));
    }

    @Test
    void criticalPolicyIsVersionedEffectiveAndSourcedFromConfiguration() {
        CriticalPolicy policy = critical(3, true);
        assertTrue(policy.effectiveAt(AT));
        assertTrue(new CriticalPolicy("GLU", 3, new BigDecimal("20"), new BigDecimal("400"),
                true, FROM, null, "synthetic-owner", "synthetic-coverage-queue",
                Duration.ofMinutes(15), "synthetic-escalation").effectiveAt(AT));
        assertTrue(policy.isCritical(new BigDecimal("19.9")));
        assertTrue(policy.isCritical(new BigDecimal("400")));
        assertEquals(false, policy.isCritical(new BigDecimal("90")));
        assertEquals(false, critical(3, false).effectiveAt(AT));
        assertEquals(false, new CriticalPolicy("GLU", 3, new BigDecimal("20"), new BigDecimal("400"),
                true, AT.plusSeconds(1), TO, "synthetic-owner", "synthetic-coverage-queue",
                Duration.ofMinutes(15), "synthetic-escalation").effectiveAt(AT));
        assertTrue(new CriticalPolicy("GLU", 3, new BigDecimal("20"), new BigDecimal("400"),
                true, FROM, AT, "synthetic-owner", "synthetic-coverage-queue",
                Duration.ofMinutes(15), "synthetic-escalation").effectiveAt(FROM));
        assertEquals(false, new CriticalPolicy("GLU", 3, new BigDecimal("20"), new BigDecimal("400"),
                true, FROM, AT, "synthetic-owner", "synthetic-coverage-queue",
                Duration.ofMinutes(15), "synthetic-escalation").effectiveAt(AT));
        assertEquals("synthetic-owner", policy.owner());
        assertEquals("synthetic-coverage-queue", policy.queue());
        assertEquals(Duration.ofMinutes(15), policy.dueAfter());
        assertEquals("synthetic-escalation", policy.escalation());
    }

    @Test
    void catalogSelectsTheNewestApprovedEffectiveRangeForTheExactTestAndUnit() {
        LabPolicyCatalog catalog = new LabPolicyCatalog(
                List.of(range(1, true, FROM, TO), range(3, true, FROM, TO),
                        range(4, false, FROM, TO), range(5, true, AT.plusSeconds(1), TO),
                        new ReferenceRange("CREAT", "mg/dL", new BigDecimal("50"), new BigDecimal("120"),
                                7, true, FROM, TO),
                        new ReferenceRange("GLU", "mmol/L", BigDecimal.ONE, BigDecimal.TEN,
                                8, true, FROM, TO)),
                List.of(critical(2, true), critical(5, false)));

        Optional<ReferenceRange> resolved = catalog.effectiveRange("GLU", "mg/dL", AT);
        assertTrue(resolved.isPresent());
        assertEquals(3, resolved.orElseThrow().version());
        assertTrue(catalog.effectiveRange("GLU", "mg/L", AT).isEmpty());
        assertTrue(catalog.effectiveRange("UNKNOWN", "mg/dL", AT).isEmpty());
        assertEquals(7, catalog.effectiveRange("CREAT", "mg/dL", AT).orElseThrow().version());
        assertEquals(2, catalog.effectiveCritical("GLU", AT).orElseThrow().version());
        assertTrue(catalog.effectiveCritical("CREAT", AT).isEmpty());
    }

    @Test
    void emptyCatalogFailsClosedInsteadOfApplyingDefaultThresholds() {
        LabPolicyCatalog empty = new LabPolicyCatalog(List.of(), List.of());
        assertTrue(empty.effectiveRange("GLU", "mg/dL", AT).isEmpty());
        assertTrue(empty.effectiveCritical("GLU", AT).isEmpty());
    }

    @Test
    void invalidPolicyInputsAreRejected() {
        assertThrows(ApiException.class, () -> new ReferenceRange(null, "mg/dL", BigDecimal.ONE,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", null, BigDecimal.ONE,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", "mg/dL", BigDecimal.ONE,
                null, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange(" ", "mg/dL", BigDecimal.ONE,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", " ", BigDecimal.ONE,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", "mg/dL", null,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", "mg/dL", BigDecimal.TEN,
                BigDecimal.TEN, 1, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", "mg/dL", BigDecimal.ONE,
                BigDecimal.TEN, 0, true, FROM, TO));
        assertThrows(ApiException.class, () -> new ReferenceRange("GLU", "mg/dL", BigDecimal.ONE,
                BigDecimal.TEN, 1, true, null, TO));

        assertThrows(ApiException.class, () -> new CriticalPolicy(null, 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, null,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                null, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, null, "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", null, Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy(" ", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 0, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.TEN,
                BigDecimal.ONE, true, FROM, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, null, TO, "owner", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, " ", "queue", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", " ", Duration.ofMinutes(1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ZERO, "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", Duration.ofSeconds(-1), "escalation"));
        assertThrows(ApiException.class, () -> new CriticalPolicy("GLU", 1, BigDecimal.ONE,
                BigDecimal.TEN, true, FROM, TO, "owner", "queue", null, "escalation"));
        assertThrows(ApiException.class, () -> new LabPolicyCatalog(null, List.of()));
        assertThrows(ApiException.class, () -> new LabPolicyCatalog(List.of(), null));
    }

    @Test
    void catalogDefensivelyCopiesPolicyInputs() {
        java.util.List<ReferenceRange> ranges = new java.util.ArrayList<>();
        ranges.add(range(1, true, FROM, TO));
        LabPolicyCatalog catalog = new LabPolicyCatalog(ranges, new java.util.ArrayList<>());
        ranges.clear();
        assertTrue(catalog.effectiveRange("GLU", "mg/dL", AT).isPresent());
    }
}
