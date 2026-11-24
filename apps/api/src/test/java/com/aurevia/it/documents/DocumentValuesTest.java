package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.documents.DocumentModel.AssociationEvent;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.DocumentAssocView;
import com.aurevia.documents.DocumentController;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentModel.PdfSpec;
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.documents.DocumentModel.RenderedDocument;

/** S-G1-07 value objects expose every part and defend their List fields. */
class DocumentValuesTest {

    private static DocumentAssoc assoc() {
        return new DocumentAssoc("a-1", 1, "chart", "p-1", "scan.pdf", "application/pdf",
                123L, "abc", 0, "active", null, "clinician-a", "2026-11-19T10:00:00Z", null);
    }

    private static AssociationEvent event() {
        return new AssociationEvent("e-1", "a-1", "link", null, "clinician-a", "2026-11-19T10:00:00Z");
    }

    @Test
    void associationExposesItsParts() {
        DocumentAssoc a = assoc();
        assertEquals("a-1", a.id());
        assertEquals(1, a.version());
        assertEquals("chart", a.subjectType());
        assertEquals("p-1", a.subjectId());
        assertEquals("scan.pdf", a.fileName());
        assertEquals("application/pdf", a.mediaType());
        assertEquals(123L, a.byteSize());
        assertEquals("abc", a.sha256());
        assertEquals(0, a.rotationDegrees());
        assertEquals("active", a.status());
        assertEquals(null, a.voidReason());
        assertEquals("clinician-a", a.createdBy());
        assertEquals("2026-11-19T10:00:00Z", a.createdAt());
        assertEquals(null, a.voidedAt());
        assertEquals(a, assoc());
        assertEquals(a.hashCode(), assoc().hashCode());
        assertEquals(a.toString(), assoc().toString());
        assertNotEquals(a, new DocumentAssoc("a-2", 1, "chart", "p-1", "scan.pdf", "application/pdf",
                123L, "abc", 0, "active", null, "clinician-a", "2026-11-19T10:00:00Z", null));
    }

    @Test
    void eventAndViewExposeTheirParts() {
        AssociationEvent e = event();
        assertEquals("e-1", e.id());
        assertEquals("a-1", e.associationId());
        assertEquals("link", e.action());
        assertEquals(null, e.reason());
        assertEquals("clinician-a", e.actor());
        assertEquals("2026-11-19T10:00:00Z", e.occurredAt());
        assertEquals(e, event());
        assertEquals(e.hashCode(), event().hashCode());
        assertEquals(e.toString(), event().toString());

        DocumentAssocView view = new DocumentAssocView(assoc(), List.of(e));
        assertEquals(assoc(), view.association());
        assertEquals(1, view.history().size());
        assertThrows(UnsupportedOperationException.class, () -> view.history().clear());
        assertEquals(view, new DocumentAssocView(assoc(), List.of(e)));
        assertEquals(view.hashCode(), new DocumentAssocView(assoc(), List.of(e)).hashCode());
        assertEquals(view.toString(), new DocumentAssocView(assoc(), List.of(e)).toString());
    }

    @Test
    void renderedDocumentExposesItsParts() {
        RenderedDocument r = new RenderedDocument("r-1", 1, "chart", "p-1", "en", "LTR",
                1, 321L, "digest", "provenance", "2026-11-19T10:00:00Z", "clinician-a");
        assertEquals("r-1", r.id());
        assertEquals(1, r.version());
        assertEquals("chart", r.subjectType());
        assertEquals("p-1", r.subjectId());
        assertEquals("en", r.locale());
        assertEquals("LTR", r.direction());
        assertEquals(1, r.pageCount());
        assertEquals(321L, r.byteSize());
        assertEquals("digest", r.sha256());
        assertEquals("provenance", r.provenance());
        assertEquals("2026-11-19T10:00:00Z", r.renderedAt());
        assertEquals("clinician-a", r.createdBy());
        assertEquals(r, new RenderedDocument("r-1", 1, "chart", "p-1", "en", "LTR",
                1, 321L, "digest", "provenance", "2026-11-19T10:00:00Z", "clinician-a"));
        assertEquals(r.hashCode(), new RenderedDocument("r-1", 1, "chart", "p-1", "en", "LTR",
                1, 321L, "digest", "provenance", "2026-11-19T10:00:00Z", "clinician-a").hashCode());
        assertEquals(r.toString(), new RenderedDocument("r-1", 1, "chart", "p-1", "en", "LTR",
                1, 321L, "digest", "provenance", "2026-11-19T10:00:00Z", "clinician-a").toString());
    }

    @Test
    void pdfSpecDefendsItsLineList() {
        PdfSpec spec = new PdfSpec("org", "chart", "p-1", "en", 1, 90, "Title",
                List.of("a", "b"), "SYS", "SRC", "2026-11-19T10:00:00Z", null);
        assertEquals("org", spec.organizationId());
        assertEquals("chart", spec.subjectType());
        assertEquals("p-1", spec.subjectId());
        assertEquals("en", spec.locale());
        assertEquals(1, spec.version());
        assertEquals(90, spec.rotationDegrees());
        assertEquals("Title", spec.title());
        assertEquals(List.of("a", "b"), spec.lines());
        assertThrows(UnsupportedOperationException.class, () -> spec.lines().add("c"));
        assertEquals("SYS", spec.sourceSystem());
        assertEquals("SRC", spec.sourceId());
        assertEquals("2026-11-19T10:00:00Z", spec.effectiveAt());
        assertEquals(spec, new PdfSpec("org", "chart", "p-1", "en", 1, 90, "Title",
                List.of("a", "b"), "SYS", "SRC", "2026-11-19T10:00:00Z", null));
        assertEquals(spec.hashCode(), new PdfSpec("org", "chart", "p-1", "en", 1, 90, "Title",
                List.of("a", "b"), "SYS", "SRC", "2026-11-19T10:00:00Z", null).hashCode());
        assertEquals(spec.toString(), new PdfSpec("org", "chart", "p-1", "en", 1, 90, "Title",
                List.of("a", "b"), "SYS", "SRC", "2026-11-19T10:00:00Z", null).toString());
    }

    @Test
    void renderRequestDefersInvalidLineListsToBoundaryValidation() {
        RenderRequest absent = new RenderRequest("chart", "p-1", "en", "Title", null,
                "SYS", "SRC", "2026-11-19T10:00:00Z", 0, null);
        assertEquals(null, absent.lines());

        RenderRequest withNullElement = new RenderRequest("chart", "p-1", "en", "Title",
                java.util.Arrays.asList("ok", null), "SYS", "SRC", "2026-11-19T10:00:00Z", 0, null);
        assertEquals(java.util.Arrays.asList("ok", null), withNullElement.lines());
        assertThrows(UnsupportedOperationException.class, () -> withNullElement.lines().clear());
    }

    @Test
    void commandRecordsExposeEveryPart() {
        LinkRequest link = new LinkRequest("chart", "p-1", "scan.pdf", "application/pdf", "Ynl0ZXM=", 90);
        assertEquals("chart", link.subjectType());
        assertEquals("p-1", link.subjectId());
        assertEquals("scan.pdf", link.fileName());
        assertEquals("application/pdf", link.mediaType());
        assertEquals("Ynl0ZXM=", link.contentBase64());
        assertEquals(90, link.rotationDegrees());
        assertEquals(link, new LinkRequest("chart", "p-1", "scan.pdf", "application/pdf", "Ynl0ZXM=", 90));
        assertEquals(link.hashCode(),
                new LinkRequest("chart", "p-1", "scan.pdf", "application/pdf", "Ynl0ZXM=", 90).hashCode());
        assertEquals(link.toString(),
                new LinkRequest("chart", "p-1", "scan.pdf", "application/pdf", "Ynl0ZXM=", 90).toString());

        RenderRequest render = new RenderRequest("chart", "p-1", "en", "Title", List.of("a"),
                "SYS", "SRC", "2026-11-19T10:00:00Z", 0, "a-1");
        assertEquals("chart", render.subjectType());
        assertEquals("p-1", render.subjectId());
        assertEquals("en", render.locale());
        assertEquals("Title", render.title());
        assertEquals(List.of("a"), render.lines());
        assertThrows(UnsupportedOperationException.class, () -> render.lines().clear());
        assertEquals("SYS", render.sourceSystem());
        assertEquals("SRC", render.sourceId());
        assertEquals("2026-11-19T10:00:00Z", render.effectiveAt());
        assertEquals(0, render.rotationDegrees());
        assertEquals("a-1", render.sourceAssociationId());
        assertEquals(render, new RenderRequest("chart", "p-1", "en", "Title", List.of("a"),
                "SYS", "SRC", "2026-11-19T10:00:00Z", 0, "a-1"));
        assertEquals(render.hashCode(), new RenderRequest("chart", "p-1", "en", "Title", List.of("a"),
                "SYS", "SRC", "2026-11-19T10:00:00Z", 0, "a-1").hashCode());
        assertEquals(render.toString(), new RenderRequest("chart", "p-1", "en", "Title", List.of("a"),
                "SYS", "SRC", "2026-11-19T10:00:00Z", 0, "a-1").toString());

        DocumentController.VoidRequest voidRequest = new DocumentController.VoidRequest("wrong chart");
        assertEquals("wrong chart", voidRequest.reason());
        assertEquals(voidRequest, new DocumentController.VoidRequest("wrong chart"));
        assertEquals(voidRequest.hashCode(), new DocumentController.VoidRequest("wrong chart").hashCode());
        assertEquals(voidRequest.toString(), new DocumentController.VoidRequest("wrong chart").toString());
    }
}
