package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * CLN-07 / ARC-01 subject-existence boundary for document associations.
 *
 * <p>Verifies that the repository checks a subject exists inside the active
 * tenant before it inserts an original, and that the check is an RLS-bounded
 * SELECT EXISTS over the chart, order and result tables. Mockito substitutes the
 * JDBC template, so no database or real patient data is involved.</p>
 */
class DocumentRepositorySubjectTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "22222222-2222-2222-2222-222222222222";
    private static final byte[] CONTENT = "synthetic original".getBytes(StandardCharsets.UTF_8);

    private NamedParameterJdbcTemplate jdbc;
    private DocumentRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new DocumentRepository(jdbc);
    }

    private static Map<String, ?> anyParams() {
        return org.mockito.ArgumentMatchers.<Map<String, ?>>any();
    }

    private void existsResult(Boolean value) {
        when(jdbc.queryForObject(anyString(), anyParams(), eq(Boolean.class))).thenReturn(value);
    }

    @Test
    void subjectExistsChecksChartOrderAndResultWithRlsBoundedQueries() {
        existsResult(true);
        assertTrue(repository.subjectExists("chart", SUBJECT));
        assertTrue(repository.subjectExists("order", SUBJECT));
        assertTrue(repository.subjectExists("result", SUBJECT));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).queryForObject(sql.capture(), anyParams(), eq(Boolean.class));
        List<String> statements = sql.getAllValues();
        assertTrue(statements.get(0).contains("public.current_patients()"));
        assertTrue(statements.get(1).contains("public.exchange_order"));
        assertTrue(statements.get(2).contains("public.xch04_report_revision"));
        for (String statement : statements) {
            assertTrue(statement.toUpperCase().contains("SELECT EXISTS")
                    || statement.toUpperCase().contains("SELECT CASE"));
        }
    }

    @Test
    void subjectExistsIsFalseForAMissingOrNullResult() {
        existsResult(false);
        assertFalse(repository.subjectExists("order", SUBJECT));
        existsResult(null);
        assertFalse(repository.subjectExists("result", SUBJECT));
    }

    @Test
    void insertIsSkippedWhenTheSubjectDoesNotExist() {
        existsResult(false);
        assertEquals(0, repository.insert(ORG, ID, "order", SUBJECT,
                "req.pdf", "application/pdf", CONTENT, 0));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void insertWritesBytesWhenTheSubjectExists() {
        existsResult(true);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertEquals(1, repository.insert(ORG, ID, "order", SUBJECT,
                "req.pdf", "application/pdf", CONTENT, 0));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(SUBJECT, params.getValue().getValue("subjectId"));
        assertEquals("order", params.getValue().getValue("subjectType"));
    }

    @Test
    void subjectExistsUsesTheSameFoldAsChartExists() {
        existsResult(true);
        assertTrue(repository.chartExists(SUBJECT));
        assertTrue(repository.subjectExists("chart", SUBJECT));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(sql.capture(), anyParams(), eq(Boolean.class));
        assertEquals(sql.getAllValues().get(0), sql.getAllValues().get(1));
    }
}
