package com.aurevia.lab.accession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IssuerCode;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.patient.PatientRepository;
import com.aurevia.patient.PatientService;

/**
 * The single issuer/code trust-boundary rule is shared by patient registration and every
 * lab accession identifier field (IAM-03, LAB-01, ARC-03).
 *
 * <p>The same bounded values are asserted against the primitive, the four lab fields and
 * the patient issuer field so a future edit cannot make one caller disagree with another.
 * All fixtures are synthetic.</p>
 */
class IssuerCodeBoundaryTest {

    private static final String VALID = "CLINIC-A";
    private static final String PUNCTUATION = "a_.:-Z";
    private static final String MAX = "a".repeat(32);
    private static final String ARABIC = "عيادة";

    private static List<String> accepted() {
        return List.of(VALID, PUNCTUATION, MAX, "ab", "LAB_1.2:3-4");
    }

    private static List<String> rejected() {
        return Arrays.asList(null, "a", "a".repeat(33), "bad issuer", ARABIC, "code\ninjection");
    }

    @Test
    void primitiveAcceptsTheFullAlphabetAndBothLengthBoundaries() {
        for (String value : accepted()) {
            assertEquals(value, IssuerCode.issuerCode(value).value());
        }
    }

    @Test
    void primitiveExposesStableRecordSemantics() {
        IssuerCode first = IssuerCode.issuerCode(VALID);
        assertEquals(VALID, first.value());
        assertEquals(first, IssuerCode.issuerCode(VALID));
        assertEquals(first.hashCode(), IssuerCode.issuerCode(VALID).hashCode());
        assertNotEquals(first, IssuerCode.issuerCode("CLINIC-B"));
        assertTrue(first.toString().contains(VALID));
        assertEquals("[A-Za-z0-9_.:-]{2,32}", IssuerCode.PATTERN);
    }

    @Test
    void primitiveRejectsOutOfBoundaryAndNonAsciiValuesWithoutEchoingInput() {
        for (String value : rejected()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> IssuerCode.issuerCode(value));
            assertEquals(IssuerCode.INVALID_MESSAGE, failure.getMessage());
        }
    }

    @Test
    void labIssuerAndCodeFieldsShareThePrimitiveBoundary() {
        List<Function<String, String>> fields = List.of(
                LabInputs::issuer, LabInputs::specimenType, LabInputs::siteCode, LabInputs::quality);
        List<String> codes = List.of(
                "INVALID_ISSUER", "INVALID_SPECIMEN_TYPE", "INVALID_SITE", "INVALID_QUALITY");
        List<String> messages = List.of(
                "accessionIssuer is malformed", "specimenType is malformed",
                "siteCode is malformed", "quality is malformed");
        for (int index = 0; index < fields.size(); index++) {
            Function<String, String> field = fields.get(index);
            for (String value : accepted()) {
                assertEquals(value, field.apply(value));
            }
            for (String value : rejected()) {
                ApiException failure = assertThrows(ApiException.class, () -> field.apply(value));
                assertEquals(422, failure.status());
                assertEquals(codes.get(index), failure.code());
                assertEquals(messages.get(index), failure.getMessage());
            }
        }
    }

    @Test
    void patientIssuerUsesTheSameBoundaryAsLabFields() {
        PatientService service = new PatientService(
                mock(PatientRepository.class), new ActionPolicy(), mock(TransactionTemplate.class));
        for (String value : accepted()) {
            // A null DOB stops at the next guard only after the issuer was accepted.
            ApiException next = assertThrows(ApiException.class, () -> service.register(
                    "Valid Name", "MRN-1", value, null, null, "en", null, null, null));
            assertEquals("INVALID_DOB", next.code(), "issuer should have been accepted: " + value);
        }
        for (String value : rejected()) {
            ApiException failure = assertThrows(ApiException.class, () -> service.register(
                    "Valid Name", "MRN-1", value, null, null, "en", null, null, null));
            assertEquals(422, failure.status());
            assertEquals("INVALID_ISSUER", failure.code());
        }
    }

    @Test
    void unchangedLabInputsBoundariesStillRejectMalformedValues() {
        assertEquals("ACC-1", LabInputs.accessionNo("ACC-1"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> LabInputs.accessionNo("bad accession!")).status());
        assertEquals("ORD-SYN", LabInputs.sourceOrderRef("ORD-SYN"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> LabInputs.sourceOrderRef("bad ref!")).status());
        assertEquals("Dr Smith", LabInputs.collector("Dr Smith"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> LabInputs.collector("!")).status());
        assertEquals("Rejected", LabInputs.reason("Rejected"));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.reason(null)).status());
        assertEquals("lab-a", LabInputs.notification("lab-a"));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.notification("!")).status());
        String uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertEquals(uuid, LabInputs.id(uuid));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.id("x")).status());
        assertEquals(uuid, LabInputs.patientId(uuid));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.patientId("x")).status());
        assertEquals(1, LabInputs.version(1));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.version(0)).status());
        assertEquals("2026-11-20T09:00:00Z",
                LabInputs.instant("2026-11-20T09:00:00Z", "collectedAt").toString());
        assertEquals(422, assertThrows(ApiException.class,
                () -> LabInputs.instant(null, "collectedAt")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> LabInputs.instant("not-a-time", "collectedAt")).status());
    }
}
