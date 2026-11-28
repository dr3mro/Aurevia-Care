package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentInputs;

/**
 * Parity evidence for the single upload file-name rule (ARC-06, MSG-03, X-03).
 *
 * <p>Original-document links, document artifacts, the shared upload primitive
 * and messaging attachments must accept and reject exactly the same names. One
 * bounded, path-free, control-character-free, no-parent-traversal rule means a
 * future tightening cannot land on only one upload path. Every value here is
 * synthetic; no tenant identifier or patient payload is involved.</p>
 */
class UploadFileNameParityTest {

    /** The observable result of one validator for one supplied name. */
    private record Outcome(boolean accepted, String value, String code) {
    }

    /** Assert that all four upload boundaries agree on an accepted name. */
    @Test
    void allUploadBoundariesAcceptTheSameBoundedNames() {
        assertParity("a".repeat(180), "a".repeat(180));
        assertParity("a".repeat(181), "a".repeat(181));
        assertParity("a".repeat(200), "a".repeat(200));
        assertParity(" notes.pdf ", "notes.pdf");
    }

    /** Assert that all four upload boundaries reject the same unsafe names. */
    @Test
    void allUploadBoundariesRejectBlankTraversalAndOverlongNames() {
        assertRejection(null);
        assertRejection(" ");
        assertRejection("..");
        assertRejection("a..b");
        assertRejection("../x.pdf");
        assertRejection("a\\b.pdf");
        assertRejection("a".repeat(201));
    }

    /** Compare the four validators through their public static entry points. */
    private static void assertParity(String input, String expectedValue) {
        Outcome document = evaluate(DocumentInputs::fileName, input);
        Outcome artifact = evaluate(ArtifactInputs::fileName, input);
        Outcome shared = evaluate(UploadValidation::fileName, input);
        Outcome attachment = evaluate(AttachmentInputs::fileName, input);
        assertEquals(new Outcome(true, expectedValue, null), document);
        assertEquals(document, artifact);
        assertEquals(document, shared);
        assertEquals(document, attachment);
    }

    /** Require every validator to reject the supplied name with the stable code. */
    private static void assertRejection(String input) {
        Outcome document = evaluate(DocumentInputs::fileName, input);
        Outcome artifact = evaluate(ArtifactInputs::fileName, input);
        Outcome shared = evaluate(UploadValidation::fileName, input);
        Outcome attachment = evaluate(AttachmentInputs::fileName, input);
        assertEquals(new Outcome(false, null, "INVALID_FILE_NAME"), document);
        assertEquals(document, artifact);
        assertEquals(document, shared);
        assertEquals(document, attachment);
    }

    /** Capture acceptance, normalized value or the stable machine-readable code. */
    private static Outcome evaluate(Function<String, String> validator, String input) {
        try {
            return new Outcome(true, validator.apply(input), null);
        } catch (ApiException failure) {
            return new Outcome(false, null, failure.code());
        }
    }
}
