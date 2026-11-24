package com.aurevia.it.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.finance.FinanceActions;
import com.aurevia.finance.FinanceContract;
import com.aurevia.finance.FinanceInputs;
import com.aurevia.finance.FinanceRepository;

/**
 * Fast, database-free acceptance for the pure FIN-01 contract: invoice state
 * machine, entry vocabulary, bounded money/identity inputs, the role/action
 * matrix and value semantics. Deliberately not tagged {@code integration} so it
 * runs in the database-less swarm fast gate; PostgreSQL and HTTP behaviour is
 * covered by {@code FinanceFlowTest}.
 */
class FinanceContractTest {

    @Test
    void invoiceStateWireAndParseRoundTrip() {
        for (FinanceContract.InvoiceState state : FinanceContract.InvoiceState.values()) {
            assertEquals(state, FinanceContract.InvoiceState.from(state.wire()));
        }
        assertEquals("draft", FinanceContract.InvoiceState.DRAFT.wire());
        assertEquals("issued", FinanceContract.InvoiceState.ISSUED.wire());
        assertEquals("partially_paid", FinanceContract.InvoiceState.PARTIALLY_PAID.wire());
        assertEquals("paid", FinanceContract.InvoiceState.PAID.wire());
        assertEquals("void", FinanceContract.InvoiceState.VOID.wire());
        assertThrows(IllegalArgumentException.class, () -> FinanceContract.InvoiceState.from("bogus"));
    }

    @Test
    void invoiceStateAllowsOnlyTheDocumentedTransitions() {
        Map<FinanceContract.InvoiceState, Map<FinanceContract.InvoiceState, Boolean>> expected = Map.of(
                FinanceContract.InvoiceState.DRAFT,
                Map.of(FinanceContract.InvoiceState.DRAFT, true,
                        FinanceContract.InvoiceState.ISSUED, true,
                        FinanceContract.InvoiceState.PARTIALLY_PAID, false,
                        FinanceContract.InvoiceState.PAID, false,
                        FinanceContract.InvoiceState.VOID, true),
                FinanceContract.InvoiceState.ISSUED,
                Map.of(FinanceContract.InvoiceState.DRAFT, false,
                        FinanceContract.InvoiceState.ISSUED, true,
                        FinanceContract.InvoiceState.PARTIALLY_PAID, true,
                        FinanceContract.InvoiceState.PAID, true,
                        FinanceContract.InvoiceState.VOID, true),
                FinanceContract.InvoiceState.PARTIALLY_PAID,
                Map.of(FinanceContract.InvoiceState.DRAFT, false,
                        FinanceContract.InvoiceState.ISSUED, true,
                        FinanceContract.InvoiceState.PARTIALLY_PAID, true,
                        FinanceContract.InvoiceState.PAID, true,
                        FinanceContract.InvoiceState.VOID, false),
                FinanceContract.InvoiceState.PAID,
                Map.of(FinanceContract.InvoiceState.DRAFT, false,
                        FinanceContract.InvoiceState.ISSUED, true,
                        FinanceContract.InvoiceState.PARTIALLY_PAID, true,
                        FinanceContract.InvoiceState.PAID, true,
                        FinanceContract.InvoiceState.VOID, false),
                FinanceContract.InvoiceState.VOID,
                Map.of(FinanceContract.InvoiceState.DRAFT, false,
                        FinanceContract.InvoiceState.ISSUED, false,
                        FinanceContract.InvoiceState.PARTIALLY_PAID, false,
                        FinanceContract.InvoiceState.PAID, false,
                        FinanceContract.InvoiceState.VOID, true));
        for (FinanceContract.InvoiceState from : FinanceContract.InvoiceState.values()) {
            for (FinanceContract.InvoiceState to : FinanceContract.InvoiceState.values()) {
                assertEquals(expected.get(from).get(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void entryTypeWireAndParseRoundTrip() {
        for (FinanceContract.EntryType type : FinanceContract.EntryType.values()) {
            assertEquals(type, FinanceContract.EntryType.from(type.wire()));
        }
        assertEquals("payment", FinanceContract.EntryType.PAYMENT.wire());
        assertEquals("refund", FinanceContract.EntryType.REFUND.wire());
        assertEquals("credit", FinanceContract.EntryType.CREDIT.wire());
        assertThrows(IllegalArgumentException.class, () -> FinanceContract.EntryType.from("bogus"));
    }

    @Test
    void uuidValidationIsBounded() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, FinanceInputs.uuid(valid, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> FinanceInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void currencyIsThreeUppercaseLetters() {
        assertEquals("USD", FinanceInputs.currency("usd"));
        assertEquals("SAR", FinanceInputs.currency(" SAR "));
        assertEquals(422, assertThrows(ApiException.class, () -> FinanceInputs.currency(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> FinanceInputs.currency("US")).status());
        assertEquals("INVALID_CURRENCY", assertThrows(ApiException.class,
                () -> FinanceInputs.currency("USD1")).code());
    }

    @Test
    void textAndOptionalTextTrimAndBoundLength() {
        assertEquals("consultation fee", FinanceInputs.text("  consultation fee  ", "description", 120));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.text(null, "description", 120)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.text("   ", "description", 120)).status());
        assertEquals("INVALID_DESCRIPTION", assertThrows(ApiException.class,
                () -> FinanceInputs.text("x".repeat(121), "description", 120)).code());
        assertEquals(null, FinanceInputs.optionalText(null, "serviceReference", 200));
        assertEquals(null, FinanceInputs.optionalText("   ", "serviceReference", 200));
        assertEquals("ENCOUNTER-1", FinanceInputs.optionalText(" ENCOUNTER-1 ", "serviceReference", 200));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.optionalText("x".repeat(201), "serviceReference", 200)).status());
    }

    @Test
    void subjectValidationMatchesSyntheticSubjectShape() {
        assertEquals("finance-a", FinanceInputs.subject("finance-a", "approverSubject"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.subject(null, "approverSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.subject("A", "approverSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.subject("Bad_Case", "approverSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.subject("a".repeat(65), "approverSubject")).status());
    }

    @Test
    void moneyAndQuantityValidationRejectsNonPositiveValues() {
        assertEquals(150, FinanceInputs.positiveAmount(150L, "amountMinor"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.positiveAmount(0L, "amountMinor")).status());
        assertEquals("INVALID_AMOUNTMINOR", assertThrows(ApiException.class,
                () -> FinanceInputs.positiveAmount(-1L, "amountMinor")).code());
        assertEquals(3, FinanceInputs.positiveQuantity(3));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.positiveQuantity(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.positiveQuantity(0)).status());
        assertEquals(0, FinanceInputs.nonNegativeAmount(0L, "unitAmountMinor"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.nonNegativeAmount(null, "unitAmountMinor")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.nonNegativeAmount(-1L, "unitAmountMinor")).status());
    }

    @Test
    void reasonValidationSeparatesRequiredAndOptional() {
        assertEquals("duplicate charge", FinanceInputs.requiredReason(" duplicate charge ", "reason"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.requiredReason(null, "reason")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.requiredReason("  ", "reason")).status());
        assertEquals(null, FinanceInputs.optionalReason(null, "reason"));
        assertEquals(null, FinanceInputs.optionalReason("  ", "reason"));
        assertEquals("paid at desk", FinanceInputs.optionalReason(" paid at desk ", "reason"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.requiredReason("x".repeat(501), "reason")).status());
    }

    @Test
    void referenceValidationIsBounded() {
        assertEquals("PAY-1", FinanceInputs.reference(" PAY-1 "));
        assertEquals(422, assertThrows(ApiException.class, () -> FinanceInputs.reference(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> FinanceInputs.reference("  ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.reference("x".repeat(129))).status());
    }

    @Test
    void amountArithmeticRejectsOverflow() {
        assertEquals(6L, FinanceInputs.multiply(2L, 3L, "amountMinor"));
        assertEquals(5L, FinanceInputs.add(2L, 3L));
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.multiply(Long.MAX_VALUE, 2L, "amountMinor")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> FinanceInputs.add(Long.MAX_VALUE, 1L)).status());
    }

    @Test
    void digestIsDeterministicAndCoversTheWholePayload() {
        String id = "11111111-1111-1111-1111-111111111111";
        String other = "22222222-2222-2222-2222-222222222222";
        String payment = FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 1000L, "USD", null, null);
        assertEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 1000L, "USD", null, null));
        assertNotEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.REFUND, 1000L, "USD", null, null));
        assertNotEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 999L, "USD", null, null));
        assertNotEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 1000L, "SAR", null, null));
        assertNotEquals(payment,
                FinanceInputs.digest(other, FinanceContract.EntryType.PAYMENT, 1000L, "USD", null, null),
                "the same reference on another invoice is a different payload");
        assertNotEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 1000L, "USD", "desk", null));
        assertNotEquals(payment,
                FinanceInputs.digest(id, FinanceContract.EntryType.PAYMENT, 1000L, "USD", null, "admin-a"));
        assertEquals(64, payment.length());
        assertEquals(payment.length(), FinanceInputs.digest(
                id, FinanceContract.EntryType.PAYMENT, 1000L, "USD", null, null).length());
    }

    @Test
    void digestReportsAnUnavailableDigestAlgorithmExplicitly() {
        ApiException failure = assertThrows(ApiException.class, () -> FinanceInputs.digest(
                "11111111-1111-1111-1111-111111111111", FinanceContract.EntryType.PAYMENT, 100L,
                "USD", null, null, algorithm -> {
                    throw new java.security.NoSuchAlgorithmException("synthetic absence");
                }));
        assertEquals(500, failure.status());
        assertEquals("DIGEST_UNAVAILABLE", failure.code());
    }

    @Test
    void digestIsFixedLengthEvenForMaximumLengthPayloads() {
        String id = "11111111-1111-1111-1111-111111111111";
        String reason = "r".repeat(500);
        String approver = "a".repeat(64);
        String digest = FinanceInputs.digest(id, FinanceContract.EntryType.REFUND, 1300L, "USD",
                reason, approver);
        assertTrue(digest.length() <= 128,
                "the replay fingerprint must fit its ledger storage column, was " + digest.length());
        assertEquals(64, digest.length(), "a name-based digest has a fixed 128-bit width");
        assertTrue(digest.chars().allMatch(character -> (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')), "the fingerprint is lowercase hex");
        assertEquals(digest, FinanceInputs.digest(id, FinanceContract.EntryType.REFUND, 1300L, "USD",
                reason, approver));
        assertNotEquals(digest, FinanceInputs.digest(id, FinanceContract.EntryType.REFUND, 1301L, "USD",
                reason, approver), "a differing payload must still produce a different fingerprint");
    }

    @Test
    void replayRecordValueSemanticsAreStable() {
        String digest = "digest";
        FinanceRepository.Replay replay = new FinanceRepository.Replay(digest, entry());
        assertEquals(digest, replay.digest());
        assertEquals(entry(), replay.entry());
        assertEquals(replay, new FinanceRepository.Replay(digest, entry()));
        assertNotEquals(replay, new FinanceRepository.Replay("other", entry()));
        assertEquals(replay.hashCode(), new FinanceRepository.Replay(digest, entry()).hashCode());
        assertNotNull(replay.toString());
    }

    @Test
    void newIdIsOpaque() {
        String id = FinanceInputs.newId();
        assertNotNull(id);
        assertEquals(java.util.UUID.fromString(id).toString(), id);
    }

    @Test
    void actionMatrixSeparatesFinanceDutiesFromClinicalRoles() {
        FinanceActions actions = new FinanceActions();
        Map<String, List<String>> allowed = Map.of(
                FinanceActions.CREATE, List.of("finance"),
                FinanceActions.ISSUE, List.of("finance"),
                FinanceActions.PAYMENT, List.of("finance"),
                FinanceActions.CREDIT, List.of("finance"),
                FinanceActions.REFUND, List.of("finance"),
                FinanceActions.VOID, List.of("finance"),
                FinanceActions.APPROVE, List.of("finance", "tenant_admin"),
                FinanceActions.READ, List.of("finance", "tenant_admin", "privacy_auditor"),
                FinanceActions.RECONCILE, List.of("finance", "tenant_admin", "privacy_auditor"));
        List<String> roles = List.of("finance", "tenant_admin", "clinician", "reception", "privacy_auditor");
        for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
            for (String role : roles) {
                assertEquals(entry.getValue().contains(role), actions.allows(entry.getKey(), role),
                        entry.getKey() + " / " + role);
            }
        }
        assertFalse(actions.allows("UNKNOWN_ACTION", "finance"));
        assertFalse(actions.allows(FinanceActions.PAYMENT, "clinician"));
    }

    @Test
    void recordValueSemanticsAreStable() {
        FinanceContract.Invoice invoice = invoice();
        FinanceContract.InvoiceItem item = item();
        FinanceContract.LedgerEntry entry = entry();
        assertEquals(invoice, invoice());
        assertEquals(item, item());
        assertEquals(entry, entry());
        assertNotEquals(invoice, item);
        assertEquals(invoice.hashCode(), invoice().hashCode());
        assertEquals(item.hashCode(), item().hashCode());
        assertEquals(entry.hashCode(), entry().hashCode());
        assertNotNull(invoice.toString());
        assertNotNull(item.toString());
        assertNotNull(entry.toString());
    }

    @Test
    void listBearingRecordsDefensivelyCopy() {
        List<FinanceContract.InvoiceItem> items = new ArrayList<>();
        items.add(item());
        List<FinanceContract.LedgerEntry> entries = new ArrayList<>();
        entries.add(entry());
        FinanceContract.View view = new FinanceContract.View(invoice(), items, entries);
        items.clear();
        entries.clear();
        assertEquals(1, view.items().size());
        assertEquals(1, view.entries().size());

        List<FinanceContract.DailyTotal> days = new ArrayList<>();
        days.add(day());
        FinanceContract.Reconciliation reconciliation = new FinanceContract.Reconciliation(days);
        days.clear();
        assertEquals(1, reconciliation.days().size());
        assertEquals(reconciliation, new FinanceContract.Reconciliation(List.of(day())));
        assertNotNull(reconciliation.toString());
    }

    private static FinanceContract.Invoice invoice() {
        return new FinanceContract.Invoice("id", 1, "patient", "USD",
                FinanceContract.InvoiceState.DRAFT, 1000L, 0L, 0L, 0L, 0L, null, null, null, null, null);
    }

    private static FinanceContract.InvoiceItem item() {
        return new FinanceContract.InvoiceItem("item-id", "id", 1, "consultation", 2, 500L, 1000L);
    }

    private static FinanceContract.LedgerEntry entry() {
        return new FinanceContract.LedgerEntry("entry-id", "id", 1,
                FinanceContract.EntryType.PAYMENT, 1000L, "USD", "PAY-1", null, null, 1L, "actor", null);
    }

    private static FinanceContract.DailyTotal day() {
        return new FinanceContract.DailyTotal("2026-11-19", "USD", 1000L, 1000L, 0L, 0L, 1000L, 1);
    }
}
