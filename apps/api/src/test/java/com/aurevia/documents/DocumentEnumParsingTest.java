package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.PdfJobState;
import com.aurevia.documents.DocumentContract.ScanVerdict;

/**
 * Boundary coverage for the stored-key parsers used by the JDBC mappers.
 *
 * <p>A null or unknown key must resolve to {@code null} so the store can reject
 * a corrupt row at its boundary instead of silently inventing a state.</p>
 */
class DocumentEnumParsingTest {

    @Test
    void scanVerdictParsesStoredKeysAndRejectsUnknownOnes() {
        for (ScanVerdict verdict : ScanVerdict.values()) {
            assertEquals(verdict, ScanVerdict.from(verdict.wire()));
        }
        assertNull(ScanVerdict.from(null));
        assertNull(ScanVerdict.from("unknown"));
    }

    @Test
    void artifactStateParsesStoredKeysAndRejectsUnknownOnes() {
        for (ArtifactState state : ArtifactState.values()) {
            assertEquals(state, ArtifactState.from(state.wire()));
        }
        assertNull(ArtifactState.from(null));
        assertNull(ArtifactState.from("unknown"));
    }

    @Test
    void pdfJobStateParsesStoredKeysAndRejectsUnknownOnes() {
        for (PdfJobState state : PdfJobState.values()) {
            assertEquals(state, PdfJobState.from(state.wire()));
        }
        assertNull(PdfJobState.from(null));
        assertNull(PdfJobState.from("unknown"));
    }

    @Test
    void eventTypeParsesStoredKeysAndRejectsUnknownOnes() {
        for (EventType type : EventType.values()) {
            assertEquals(type, EventType.from(type.wire()));
        }
        assertNull(EventType.from(null));
        assertNull(EventType.from("unknown"));
    }
}
