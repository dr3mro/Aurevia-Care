package com.aurevia.it.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.ClinicalNoteController;
import com.aurevia.clinical.notes.ClinicalNoteService;
import com.aurevia.clinical.notes.ClinicalNoteRevision;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.clinical.notes.NoteView;

/** S-G1-05 fast unit tier: HTTP binding, If-Match semantics and thin delegation only. */
class ClinicalNoteControllerTest {

    private static final String NOTE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private ClinicalNoteService service;
    private ClinicalNoteController controller;
    private ClinicalNoteRevision revision;

    @BeforeEach
    void setUp() {
        service = mock(ClinicalNoteService.class);
        controller = new ClinicalNoteController(service);
        revision = new ClinicalNoteRevision(NOTE, 1, NoteState.DRAFT, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                null, "Title", "Content", "en", "SOAP", 1, "author-a", "clinician-a",
                "2026-11-19T10:00:00Z", null, null, null, "checksum", null, null, null, "[]",
                "2026-11-19T10:00:00Z");
    }

    @Test
    void createDelegatesEveryField() {
        when(service.createDraft("patient-a", null, "Title", "Content", "en", "SOAP", 1,
                "author-a", null)).thenReturn(revision);
        ClinicalNoteController.CreateRequest request = new ClinicalNoteController.CreateRequest(
                "patient-a", null, "Title", "Content", "en", "SOAP", 1, "author-a", null);
        assertEquals(revision, controller.create(request));
        verify(service).createDraft("patient-a", null, "Title", "Content", "en", "SOAP", 1,
                "author-a", null);
    }

    @Test
    void editParsesStrongWeakAndQuotedPreconditions() {
        when(service.updateDraft(NOTE, 3, "Title", "Content")).thenReturn(revision);
        assertEquals(revision, controller.edit(NOTE, "3",
                new ClinicalNoteController.EditRequest("Title", "Content")));
        assertEquals(revision, controller.edit(NOTE, "W/\"3\"",
                new ClinicalNoteController.EditRequest("Title", "Content")));
        assertEquals(revision, controller.edit(NOTE, "\"3\"",
                new ClinicalNoteController.EditRequest("Title", "Content")));
    }

    @Test
    void editRejectsMissingAndMalformedPreconditions() {
        assertEquals(428, assertThrows(ApiException.class, () -> controller.edit(NOTE, null,
                new ClinicalNoteController.EditRequest("Title", "Content"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> controller.edit(NOTE, "abc",
                new ClinicalNoteController.EditRequest("Title", "Content"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> controller.edit(NOTE, "0",
                new ClinicalNoteController.EditRequest("Title", "Content"))).status());
    }

    @Test
    void signatureAndCorrectionRoutesDelegateExactly() {
        when(service.sign(NOTE, 1, "attest")).thenReturn(revision);
        when(service.signAddendum(NOTE, 1, "attest")).thenReturn(revision);
        when(service.createAmendmentDraft(NOTE, 1)).thenReturn(revision);
        when(service.markEnteredInError(NOTE, 1, "reason", "[]")).thenReturn(revision);
        when(service.get(NOTE)).thenReturn(new NoteView(revision, List.of(revision)));
        assertEquals(revision, controller.sign(NOTE, "\"1\"",
                new ClinicalNoteController.SignRequest("attest")));
        assertEquals(revision, controller.signAddendum(NOTE, "\"1\"",
                new ClinicalNoteController.SignRequest("attest")));
        assertEquals(revision, controller.amend(NOTE, "\"1\""));
        assertEquals(revision, controller.enteredInError(NOTE, "\"1\"",
                new ClinicalNoteController.ErrorRequest("reason", "[]")));
        assertEquals(1, controller.get(NOTE).history().size());
        verify(service).sign(NOTE, 1, "attest");
        verify(service).signAddendum(NOTE, 1, "attest");
        verify(service).createAmendmentDraft(NOTE, 1);
        verify(service).markEnteredInError(NOTE, 1, "reason", "[]");
        verify(service).get(NOTE);
    }

    @Test
    void requestRecordsExposeTheirParts() {
        ClinicalNoteController.CreateRequest create = new ClinicalNoteController.CreateRequest(
                "patient-a", "encounter-a", "Title", "Content", "ar", "SOAP", 2, "author-a", "now");
        assertEquals("patient-a", create.patientId());
        assertEquals("encounter-a", create.encounterId());
        assertEquals("Title", create.title());
        assertEquals("Content", create.content());
        assertEquals("ar", create.language());
        assertEquals("SOAP", create.templateCode());
        assertEquals(2, create.templateVersion());
        assertEquals("author-a", create.clinicalAuthorSubject());
        assertEquals("now", create.effectiveAt());
        ClinicalNoteController.EditRequest edit = new ClinicalNoteController.EditRequest("T", "C");
        assertEquals("T", edit.title());
        assertEquals("C", edit.content());
        ClinicalNoteController.SignRequest sign = new ClinicalNoteController.SignRequest("attest");
        assertEquals("attest", sign.attestation());
        ClinicalNoteController.ErrorRequest error = new ClinicalNoteController.ErrorRequest("why", "[]");
        assertEquals("why", error.reason());
        assertEquals("[]", error.affectedRecipients());
    }
}
