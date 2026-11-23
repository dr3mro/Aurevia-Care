package com.aurevia.smartclinic.staffmsg.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.smartclinic.staffmsg.references.ReferenceContract.SubjectWarning;

/** Pure input rules for message references: bounded keys, typed kinds and identifier screening. */
class ReferenceInputsTest {

    /** A synthetic UUID in a subject is the clearest identifier leak to warn about. */
    @Test
    void screenFlagsUuidWithoutEchoingTheSubject() {
        String subject = "see chart 123e4567-e89b-12d3-a456-426614174000 please";
        SubjectWarning warning = ReferenceInputs.screenSubject(subject);
        assertTrue(warning.suspicious());
        assertEquals("UUID_LIKE", warning.reasonCode());
        assertFalse(warning.toString().contains(subject), "the warning must never carry the subject text");
        assertFalse(warning.toString().contains("123e4567"), "the warning must never carry an identifier fragment");
    }

    /** A long digit run is the common shape of an MRN or account number. */
    @Test
    void screenFlagsLongDigitRuns() {
        SubjectWarning warning = ReferenceInputs.screenSubject("call about 1234567890 today");
        assertTrue(warning.suspicious());
        assertEquals("DIGIT_RUN", warning.reasonCode());
        assertFalse(warning.toString().contains("1234567890"));
    }

    /** An at-sign in a subject hints at pasted contact details or an address. */
    @Test
    void screenFlagsEmailLikeSubjects() {
        SubjectWarning warning = ReferenceInputs.screenSubject("forward to nurse@example.test");
        assertTrue(warning.suspicious());
        assertEquals("EMAIL_LIKE", warning.reasonCode());
        assertFalse(warning.toString().contains("nurse@example.test"));
    }

    /** An explicit MRN-style token is flagged even when its number is short. */
    @Test
    void screenFlagsMrnLikeTokens() {
        SubjectWarning warning = ReferenceInputs.screenSubject("MRN: 42 follow up");
        assertTrue(warning.suspicious());
        assertEquals("MRN_LIKE", warning.reasonCode());
        assertFalse(warning.toString().contains("MRN: 42"));
    }

    /** An ordinary subject carries no warning. */
    @Test
    void plainSubjectIsNotSuspicious() {
        SubjectWarning warning = ReferenceInputs.screenSubject("follow up on your visit");
        assertFalse(warning.suspicious());
        assertEquals("NONE", warning.reasonCode());
    }

    /** Missing and blank subjects are safe: there is no text that could leak. */
    @Test
    void nullAndBlankSubjectsAreNotSuspicious() {
        for (String value : Arrays.asList((String) null, "", "   ")) {
            SubjectWarning warning = ReferenceInputs.screenSubject(value);
            assertFalse(warning.suspicious());
            assertEquals("NONE", warning.reasonCode());
        }
    }

    /** Every supported kind has a stable wire key and round-trips. */
    @Test
    void kindsResolveFromWireKeys() {
        assertEquals(ReferenceKind.PATIENT, ReferenceKind.from("patient"));
        assertEquals(ReferenceKind.ENCOUNTER, ReferenceKind.from("encounter"));
        assertEquals(ReferenceKind.ORDER, ReferenceKind.from("order"));
        assertEquals(ReferenceKind.DOCUMENT, ReferenceKind.from("document"));
        assertEquals("patient", ReferenceKind.PATIENT.wire());
        assertEquals(ReferenceKind.PATIENT, ReferenceInputs.kind("patient"));
    }

    /** An unsupported kind is rejected at the trust boundary. */
    @Test
    void invalidKindIsRejected() {
        ApiException denied = assertThrows(ApiException.class, () -> ReferenceInputs.kind("billing"));
        assertEquals(422, denied.status());
        assertEquals("INVALID_REFERENCE_KIND", denied.code());
        assertThrows(IllegalArgumentException.class, () -> ReferenceKind.from("billing"));
    }

    /** A non-identifying label is bounded and trimmed. */
    @Test
    void labelsAreBoundedAndTrimmed() {
        assertEquals("Visit summary", ReferenceInputs.label("  Visit summary  "));
        ApiException blank = assertThrows(ApiException.class, () -> ReferenceInputs.label("   "));
        assertEquals(422, blank.status());
        ApiException absent = assertThrows(ApiException.class, () -> ReferenceInputs.label(null));
        assertEquals("INVALID_LABEL", absent.code());
        ApiException tooLong = assertThrows(ApiException.class, () -> ReferenceInputs.label("x".repeat(121)));
        assertEquals("INVALID_LABEL", tooLong.code());
    }

    /** Opaque keys reject blanks and over-long values. */
    @Test
    void keysAreBounded() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(uuid, ReferenceInputs.key(uuid, "targetId"));
        ApiException blank = assertThrows(ApiException.class, () -> ReferenceInputs.key("  ", "targetId"));
        assertEquals(422, blank.status());
        assertThrows(ApiException.class, () -> ReferenceInputs.key("x".repeat(65), "targetId"));
    }

    /** Generated identifiers are unique opaque UUIDs used only as opaque handles. */
    @Test
    void generatedIdsAreDistinctUuids() {
        String first = ReferenceInputs.newId();
        String second = ReferenceInputs.newId();
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertFalse(first.equals(second));
    }
}
