package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** CLN-07 web adapter for the verified original download: it only binds and maps. */
class DocumentContentControllerTest {

    private static final String ID = "33333333-3333-3333-3333-333333333333";

    @Test
    void associationContentDelegatesWithAnOctetStreamBody() {
        DocumentService service = mock(DocumentService.class);
        DocumentController controller = new DocumentController(service);
        byte[] original = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
        when(service.associationContent(ID)).thenReturn(original);

        ResponseEntity<byte[]> response = controller.associationContent(ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertArrayEquals(original, response.getBody());
        verify(service).associationContent(ID);
    }
}
