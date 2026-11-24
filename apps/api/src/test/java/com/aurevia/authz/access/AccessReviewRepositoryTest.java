package com.aurevia.authz.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * SQL-boundary tests for the IAM-06 access-review rows. Every value is bound
 * as a named parameter, so the tenant organization and the grant identity are
 * never concatenated into SQL. Fixtures are synthetic identifiers only.
 */
class AccessReviewRepositoryTest {

    private static final String ORGANIZATION = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

    @Test
    void openReviewInsertsAnAuditorRecipientBoundToTheTenantAndGrant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AccessRepository repository = new AccessRepository(jdbc);

        repository.openReview(ORGANIZATION, "grant-1", "clinician-a", NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertTrue(sql.getValue().contains("INSERT INTO public.emergency_access_review"));
        assertTrue(sql.getValue().contains("CAST(:organizationId AS uuid)"));
        assertEquals(ORGANIZATION, parameters.getValue().getValue("organizationId"));
        assertEquals("grant-1", parameters.getValue().getValue("grantId"));
        assertEquals("clinician-a", parameters.getValue().getValue("subject"));
        assertEquals(NOW.toString(), parameters.getValue().getValue("openedAt"));
    }

    @Test
    void closeReviewUpdatesOnlyTheOpenReviewForThatGrant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AccessRepository repository = new AccessRepository(jdbc);

        repository.closeReview("grant-1", "auditor-a", "concerns_noted", "checked", NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertTrue(sql.getValue().contains("UPDATE public.emergency_access_review"));
        assertTrue(sql.getValue().contains("status = 'closed'"));
        assertTrue(sql.getValue().contains("status = 'open'"));
        assertEquals("grant-1", parameters.getValue().getValue("grantId"));
        assertEquals("auditor-a", parameters.getValue().getValue("reviewer"));
        assertEquals("concerns_noted", parameters.getValue().getValue("outcome"));
        assertEquals("checked", parameters.getValue().getValue("note"));
        assertEquals(NOW.toString(), parameters.getValue().getValue("closedAt"));
    }
}
