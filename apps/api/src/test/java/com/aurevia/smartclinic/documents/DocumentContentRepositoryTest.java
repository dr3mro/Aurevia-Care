package com.aurevia.smartclinic.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * CLN-07 verified-download SQL boundary. The database function recomputes the
 * digest from the retained bytes before returning them; this test proves the
 * repository maps its result and its empty result without touching a database.
 */
class DocumentContentRepositoryTest {

    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final byte[] CONTENT = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DocumentRepository repository = new DocumentRepository(jdbc);

    @Test
    void verifiedContentReturnsTheBytesFromTheDatabaseFunction() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(byte[].class)))
                .thenReturn(CONTENT);

        Optional<byte[]> content = repository.verifiedAssociationContent(ID);
        assertTrue(content.isPresent());
        assertArrayEquals(CONTENT, content.get());
        verify(jdbc).queryForObject(anyString(), any(SqlParameterSource.class), eq(byte[].class));
    }

    @Test
    void verifiedContentIsEmptyWhenTheFunctionYieldsNoBytes() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(byte[].class)))
                .thenReturn(null);

        assertTrue(repository.verifiedAssociationContent(ID).isEmpty());
    }
}
