package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionRepository;

/** V94 decision store: reads, compare-and-set writes and failure paths. */
class JdbcSubstitutionDecisionStoreTest {

    private NamedParameterJdbcTemplate jdbc;
    private JdbcSubstitutionStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcSubstitutionStore(mock(PrescriptionRepository.class),
                mock(TransactionTemplate.class), jdbc);
    }

    private Proposal proposal() {
        return new Proposal("p-1", SubstitutionFixtures.ORG, substitutionId(),
                SubstitutionFixtures.PATIENT, SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.original(),
                SubstitutionFixtures.alternative(), SubstitutionContract.Reason.STOCK, null, null,
                SubstitutionFixtures.noConflictCheck(), SubstitutionContract.State.PENDING, 1,
                SubstitutionFixtures.AT);
    }

    @SuppressWarnings("unchecked")
    private void rows(List<String> values) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(values);
    }

    @Test
    void proposalIsReadFromItsFrozenSnapshotOrAbsent() {
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(proposal());
        rows(List.of(json));
        assertEquals("p-1", store.findProposal(SubstitutionFixtures.ORG, "p-1").id());
        verify(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        rows(List.of());
        assertNull(store.findProposal(SubstitutionFixtures.ORG, "p-1"));
    }

    @Test
    void alternativesAreReadFromTheFrozenListOrEmpty() {
        rows(List.of("[]"));
        List<Alternative> result = store.findAlternatives(SubstitutionFixtures.ORG, "p-1");
        assertNotNull(result);
        assertEquals(0, result.size());

        rows(List.of());
        assertEquals(0, store.findAlternatives(SubstitutionFixtures.ORG, "p-1").size());
    }

    @Test
    void revisionIsAbsentWhenNoApprovalWasRecorded() {
        rows(List.of());
        assertNull(store.findRevision(SubstitutionFixtures.ORG, "p-1"));
    }

    @Test
    void replayLookupReturnsTheWinningProposalOrNull() {
        rows(List.of("proposal-1"));
        assertEquals("proposal-1", store.findReplayProposalId(SubstitutionFixtures.ORG, "approve", "key"));
        rows(List.of());
        assertNull(store.findReplayProposalId(SubstitutionFixtures.ORG, "approve", "key"));
    }

    @Test
    void replayProposalIdIsMappedFromTheDecisionRow() throws Exception {
        // The store reads the winning proposal by column position; a canned list would
        // leave that mapping untested, so drive the real RowMapper with a synthetic row.
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("proposal-1");
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<String> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        assertEquals("proposal-1",
                store.findReplayProposalId(SubstitutionFixtures.ORG, "approve", "key"));
    }

    @Test
    void claimReportsWhetherTheKeyWasWon() {
        rows(List.of("proposal-1"));
        assertEquals(true, store.claim(SubstitutionFixtures.ORG, "approve", "key", "proposal-1"));
        rows(List.of());
        assertEquals(false, store.claim(SubstitutionFixtures.ORG, "approve", "key", "proposal-1"));
    }

    @Test
    void proposalAndRevisionAreAppended() {
        store.insertProposal(proposal(), List.of());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));

        SignedRevision revision = new SignedRevision("r-1", SubstitutionFixtures.PRESCRIPTION, "p-1", 1,
                SubstitutionFixtures.original(), SubstitutionFixtures.alternative(),
                SubstitutionContract.Reason.STOCK, SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.noConflictCheck(), SubstitutionFixtures.ATTESTATION,
                "catalog-v3-approved", SubstitutionFixtures.AT);
        store.insertRevision(SubstitutionFixtures.ORG, revision);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void closingAProposalRequiresExactlyOneWinner() {
        Proposal closed = new Proposal(proposal().id(), SubstitutionFixtures.ORG,
                substitutionId(), SubstitutionFixtures.PATIENT, SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.original(),
                SubstitutionFixtures.alternative(), SubstitutionContract.Reason.STOCK, null, null,
                SubstitutionFixtures.noConflictCheck(), SubstitutionContract.State.APPROVED, 2,
                SubstitutionFixtures.AT);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        store.closeProposal(closed, Instant.parse("2026-11-21T12:01:00Z"));

        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        ApiException failure = assertThrows(ApiException.class,
                () -> store.closeProposal(closed, Instant.parse("2026-11-21T12:01:00Z")));
        assertEquals(409, failure.status());
        assertEquals("CLOSED_PROPOSAL", failure.code());
    }

    @Test
    void revisionNumbersArePersistentAndFailClosedOnAnEmptyAggregate() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(4);
        assertEquals(4, store.nextRevisionNumber(SubstitutionFixtures.ORG, substitutionId()));

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(null);
        assertThrows(NullPointerException.class,
                () -> store.nextRevisionNumber(SubstitutionFixtures.ORG, substitutionId()));
    }

    private static String substitutionId() {
        return "11111111-1111-1111-1111-111111111111";
    }
}
