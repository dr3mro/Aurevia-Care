package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.documents.DocumentModel.VerifiedContent;

/**
 * CLN-07 verified-download SQL boundary. The database function returns the
 * retained bytes with an integrity flag; this test proves the repository maps
 * both outcomes and the empty result without touching a database.
 */
class DocumentContentRepositoryTest {

    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final byte[] CONTENT = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DocumentRepository repository = new DocumentRepository(jdbc);

    @SuppressWarnings("unchecked")
    private void functionReturns(List<VerifiedContent> rows) {
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class))).thenReturn(rows);
    }

    @Test
    void verifiedContentReturnsTheBytesAndIntactFlagFromTheDatabaseFunction() {
        functionReturns(List.of(new VerifiedContent(CONTENT, true)));

        var content = repository.verifiedAssociationContent(ID);
        assertTrue(content.isPresent());
        assertArrayEquals(CONTENT, content.get().content());
        assertTrue(content.get().intact());
        verify(jdbc).query(anyString(), any(Map.class), any(RowMapper.class));
    }

    @Test
    void verifiedContentCarriesTheTamperedFlagWithoutRaising() {
        functionReturns(List.of(new VerifiedContent(CONTENT, false)));

        var content = repository.verifiedAssociationContent(ID);
        assertTrue(content.isPresent());
        assertArrayEquals(CONTENT, content.get().content());
        assertFalse(content.get().intact());
    }

    @Test
    void verifiedContentIsEmptyWhenTheFunctionYieldsNoRow() {
        functionReturns(List.of());

        assertTrue(repository.verifiedAssociationContent(ID).isEmpty());
    }

    @Test
    void verifiedContentDefensivelyCopiesTheRetainedBytes() {
        byte[] mutable = CONTENT.clone();
        VerifiedContent content = new VerifiedContent(mutable, true);
        mutable[0] = 'X';

        assertArrayEquals(CONTENT, content.content());
        byte[] exposed = content.content();
        exposed[0] = 'Y';
        assertArrayEquals(CONTENT, content.content());
    }
}
