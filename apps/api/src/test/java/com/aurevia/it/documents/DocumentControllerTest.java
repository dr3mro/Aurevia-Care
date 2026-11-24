package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.DocumentAssocView;
import com.aurevia.documents.DocumentController;
import com.aurevia.documents.DocumentService;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.documents.DocumentModel.RenderedDocument;

/** S-G1-07 web adapter: it only binds inputs and maps outcomes. */
class DocumentControllerTest {

    private static final String ID = "33333333-3333-3333-3333-333333333333";

    private DocumentService service;
    private DocumentController controller;

    @BeforeEach
    void setUp() {
        service = mock(DocumentService.class);
        controller = new DocumentController(service);
    }

    private DocumentAssoc assoc() {
        return new DocumentAssoc(ID, 1, "chart", "p", "scan.pdf", "application/pdf",
                1L, "digest", 0, "active", null, "actor", "now", null);
    }

    private RenderedDocument rendered() {
        return new RenderedDocument(ID, 1, "chart", "p", "en", "LTR", 1, 4L,
                "digest", "provenance", "now", "actor");
    }

    @Test
    void linkReturns201WithALocation() {
        LinkRequest request = new LinkRequest("chart", "p", "scan.pdf", "application/pdf", "Yg==", 0);
        when(service.link(request)).thenReturn(assoc());
        ResponseEntity<DocumentAssoc> response = controller.link(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/documents/associations/" + ID, response.getHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals(ID, response.getBody().id());
    }

    @Test
    void associationDelegates() {
        DocumentAssocView view = new DocumentAssocView(assoc(), List.of());
        when(service.association(ID)).thenReturn(view);
        assertEquals(view, controller.association(ID));
    }

    @Test
    void voidParsesTheIfMatchPrecondition() {
        when(service.voidAssociation(eq(ID), anyInt(), anyString())).thenReturn(assoc());
        assertEquals(ID, controller.voidAssociation(ID,
                new DocumentController.VoidRequest("wrong chart"), "W/\"1\"").id());
        verify(service).voidAssociation(ID, 1, "wrong chart");
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.voidAssociation(ID, new DocumentController.VoidRequest("r"), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.voidAssociation(ID, new DocumentController.VoidRequest("r"), "abc")).status());
    }

    @Test
    void renderReturns201WithALocation() {
        RenderRequest request = new RenderRequest("chart", "p", "en", "T", List.of("l"),
                "S", "I", "E", 0, null);
        when(service.render(request)).thenReturn(rendered());
        ResponseEntity<RenderedDocument> response = controller.render(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/documents/rendered/" + ID, response.getHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals(ID, response.getBody().id());
    }

    @Test
    void renderedReadsDelegate() {
        when(service.rendered(ID)).thenReturn(rendered());
        assertEquals(ID, controller.rendered(ID).id());
        byte[] pdf = { '%', 'P', 'D', 'F' };
        when(service.renderedContent(ID)).thenReturn(pdf);
        ResponseEntity<byte[]> content = controller.renderedContent(ID);
        assertEquals(MediaType.APPLICATION_PDF, content.getHeaders().getContentType());
        assertArrayEquals(pdf, content.getBody());
        List<RenderedDocument> versions = List.of(rendered());
        when(service.renderedVersions("chart", "p", "en")).thenReturn(versions);
        assertEquals(versions, controller.renderedVersions("chart", "p", "en"));
        verify(service).renderedVersions("chart", "p", "en");
        verify(service).renderedContent(ID);
        verify(service).rendered(ID);
    }
}
