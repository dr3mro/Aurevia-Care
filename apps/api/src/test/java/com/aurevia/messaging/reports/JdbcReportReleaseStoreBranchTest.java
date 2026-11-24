package com.aurevia.messaging.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.reports.ReleasedReport.CorrectionContext;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.tenant.TenantContext;

/**
 * Deterministic branch coverage for the PostgreSQL report-release store (XCH-06, PAT-02).
 *
 * <p>The boundary is mocked so the empty-result and stale-update sides can be forced
 * without a race. Normal paths are proven against real PostgreSQL in
 * {@code JdbcReportReleaseStoreIntegrationTest}. All values are synthetic.</p>
 */
class JdbcReportReleaseStoreBranchTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-subject-1";
    private static final String PATIENT = "patient-1";
    private static final String REPORT = "report-1";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcReportReleaseStore store;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "release-branch"));
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        store = new JdbcReportReleaseStore(jdbc, transactions);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ReleasedReport report(int revision, Status status) {
        return new ReleasedReport(REPORT, revision, PATIENT, ORG, "Clinic A", "2026-06-10",
                "en", "document-" + revision, "checksum-" + revision, status, null);
    }

    private static ReportRelease release(boolean held) {
        return new ReportRelease(report(1, held ? Status.HELD : Status.RELEASED), 7, NOW,
                held ? "pending policy review" : null,
                held ? Instant.parse("2026-06-20T00:00:00Z") : null, NOW);
    }

    @Test
    void linkReturnsTheLinkedPatientAndIsEmptyWithoutOne() {
        PatientChartLink link = new PatientChartLink(PATIENT, true, true);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PatientChartLink>>any())).thenReturn(List.of(link));
        assertEquals(link, store.link(ORG, SUBJECT).orElseThrow());

        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PatientChartLink>>any())).thenReturn(List.of());
        assertTrue(store.link(ORG, "other-subject").isEmpty());
    }

    @Test
    void putLinkAndPutReportWriteTheirRows() {
        store.putLink(ORG, SUBJECT, new PatientChartLink(PATIENT, true, true));
        store.putReport(report(1, Status.RELEASED));
        store.putReport(new ReleasedReport(REPORT, 2, PATIENT, ORG, "Clinic A", "2026-06-12",
                "en", "document-2", "checksum-2", Status.RELEASED,
                new CorrectionContext(1, "corrected value")));
        verify(jdbc, times(3)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void findReturnsAnExactRevisionAndIsEmptyWhenAbsent() {
        ReleasedReport expected = report(2, Status.RELEASED);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any())).thenReturn(List.of(expected));
        assertEquals(expected, store.find(ORG, REPORT, 2).orElseThrow());

        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any())).thenReturn(List.of());
        assertTrue(store.find(ORG, REPORT, 9).isEmpty());
    }

    @Test
    void reportsForReturnsAllPatientRevisions() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any()))
                .thenReturn(List.of(report(2, Status.RELEASED), report(1, Status.WITHDRAWN)));
        assertEquals(2, store.reportsFor(ORG, PATIENT).size());
    }

    @Test
    void latestReleasedRevisionCoversMissingAndPresentRows() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(null, 3);
        assertTrue(store.latestReleasedRevision(ORG, REPORT, LocalDate.parse("2026-06-15")).isEmpty());
        assertEquals(OptionalInt.of(3),
                store.latestReleasedRevision(ORG, REPORT, LocalDate.parse("2026-06-15")));
    }

    @Test
    void isSupersededCoversBothQueryOutcomes() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE, Boolean.FALSE);
        assertTrue(store.isSuperseded(ORG, report(1, Status.RELEASED)));
        assertFalse(store.isSuperseded(ORG, report(2, Status.RELEASED)));
    }

    @Test
    void withdrawOfAMissingRevisionDoesNotWriteAnything() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any())).thenReturn(List.of());
        assertFalse(store.withdraw(ORG, REPORT, 1));
        verify(jdbc, times(0)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void withdrawUpdatesCurrentAccessAndAppendsHistory() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any()))
                .thenReturn(List.of(report(1, Status.RELEASED)));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(store.withdraw(ORG, REPORT, 1));
        verify(jdbc, times(2)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void withdrawWithoutAnAffectedRowDoesNotAppendHistory() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any()))
                .thenReturn(List.of(report(1, Status.RELEASED)));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(store.withdraw(ORG, REPORT, 1));
        verify(jdbc, times(1)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void recordReleaseUpsertsCurrentStateAndAppendsHistoryWithHoldDetails() {
        store.recordRelease(release(true));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals(7, params.getAllValues().get(1).getValue("policyVersion"));
        assertEquals("pending policy review", params.getAllValues().get(1).getValue("holdReason"));
        assertEquals(OffsetDateTime.ofInstant(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC),
                params.getAllValues().get(1).getValue("holdExpiresAt"));
    }

    @Test
    void recordReleaseWithoutHoldUsesNullHoldColumns() {
        store.recordRelease(release(false));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals(null, params.getAllValues().get(1).getValue("holdReason"));
        assertEquals(null, params.getAllValues().get(1).getValue("holdExpiresAt"));
        assertEquals(ORG, params.getAllValues().get(0).getValue("organizationId"));
    }

    @Test
    void recordReleaseWritesCorrectionContextIntoTheUpsert() {
        ReleasedReport corrected = new ReleasedReport(REPORT, 2, PATIENT, ORG, "Clinic A", "2026-06-12",
                "en", "document-2", "checksum-2", Status.RELEASED,
                new CorrectionContext(1, "corrected value"));
        store.recordRelease(new ReportRelease(corrected, 5, NOW, null, null, NOW));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals(1, params.getAllValues().get(0).getValue("correctionRevision"));
        assertEquals("corrected value", params.getAllValues().get(0).getValue("correctionReason"));
    }

    @Test
    void recordReleaseOfAWithdrawnRevisionIsRejectedWithoutAppendingHistory() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> store.recordRelease(release(false)));
        verify(jdbc, times(1)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void recordReleaseWithAReasonButNoExpiryBindsANullExpiryForTheCheckToReject() {
        store.recordRelease(new ReportRelease(report(1, Status.RELEASED), 7, NOW,
                "pending policy review", null, NOW));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals("pending policy review", params.getAllValues().get(1).getValue("holdReason"));
        assertEquals(null, params.getAllValues().get(1).getValue("holdExpiresAt"));
    }

    @Test
    void releaseHistoryReturnsItsRows() {
        ReportRelease expected = release(false);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReportRelease>>any())).thenReturn(List.of(expected));
        assertEquals(List.of(expected), store.releaseHistory(ORG, REPORT, 1));
    }

    @Test
    void reportRowMapperHandlesCorrectionAndHoldColumns() throws Exception {
        ArgumentCaptor<RowMapper<ReleasedReport>> captor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReleasedReport>>any())).thenReturn(List.of());
        store.find(ORG, REPORT, 1);
        verify(jdbc).query(anyString(), any(SqlParameterSource.class), captor.capture());

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("organization_id")).thenReturn(ORG);
        when(resultSet.getString("id")).thenReturn(REPORT);
        when(resultSet.getInt("revision")).thenReturn(2);
        when(resultSet.getString("patient_id")).thenReturn(PATIENT);
        when(resultSet.getString("issuing_organization")).thenReturn("Clinic A");
        when(resultSet.getString("effective_date")).thenReturn("2026-06-10");
        when(resultSet.getString("language")).thenReturn("en");
        when(resultSet.getString("document_id")).thenReturn("document-2");
        when(resultSet.getString("checksum")).thenReturn("checksum-2");
        when(resultSet.getString("status")).thenReturn("RELEASED");
        when(resultSet.getInt("correction_revision")).thenReturn(1);
        when(resultSet.getString("correction_reason")).thenReturn("corrected value");
        assertEquals(new ReleasedReport(REPORT, 2, PATIENT, ORG, "Clinic A", "2026-06-10",
                        "en", "document-2", "checksum-2", Status.RELEASED,
                        new CorrectionContext(1, "corrected value")),
                captor.getValue().mapRow(resultSet, 0));

        when(resultSet.getString("correction_reason")).thenReturn(null);
        assertEquals(report(2, Status.RELEASED), captor.getValue().mapRow(resultSet, 0));
    }

    @Test
    void historyRowMapperReadsRevisionAndReleaseColumns() throws Exception {
        ArgumentCaptor<RowMapper<ReportRelease>> captor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<ReportRelease>>any())).thenReturn(List.of());
        store.releaseHistory(ORG, REPORT, 1);
        verify(jdbc).query(anyString(), any(SqlParameterSource.class), captor.capture());

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("organization_id")).thenReturn(ORG);
        when(resultSet.getString("report_id")).thenReturn(REPORT);
        when(resultSet.getInt("revision")).thenReturn(1);
        when(resultSet.getString("patient_id")).thenReturn(PATIENT);
        when(resultSet.getString("issuing_organization")).thenReturn("Clinic A");
        when(resultSet.getString("effective_date")).thenReturn("2026-06-10");
        when(resultSet.getString("language")).thenReturn("en");
        when(resultSet.getString("document_id")).thenReturn("document-1");
        when(resultSet.getString("checksum")).thenReturn("checksum-1");
        when(resultSet.getString("status")).thenReturn("HELD");
        when(resultSet.getInt("correction_revision")).thenReturn(0);
        when(resultSet.getString("correction_reason")).thenReturn(null);
        when(resultSet.getInt("policy_version")).thenReturn(7);
        when(resultSet.getObject("effective_time", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(resultSet.getString("hold_reason")).thenReturn("pending policy review");
        when(resultSet.getObject("hold_expires_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC));
        when(resultSet.getObject("recorded_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        ReportRelease held = captor.getValue().mapRow(resultSet, 0);
        assertEquals(Status.HELD, held.revision().status());
        assertEquals(7, held.policyVersion());
        assertEquals(NOW, held.effectiveTime());
        assertEquals("pending policy review", held.holdReason());
        assertEquals(Instant.parse("2026-06-20T00:00:00Z"), held.holdExpiresAt());
        assertEquals(NOW, held.recordedAt());

        when(resultSet.getInt("correction_revision")).thenReturn(3);
        when(resultSet.getString("correction_reason")).thenReturn("corrected value");
        ReportRelease corrected = captor.getValue().mapRow(resultSet, 0);
        assertEquals(new CorrectionContext(3, "corrected value"), corrected.revision().correction());
        when(resultSet.getInt("correction_revision")).thenReturn(0);
        when(resultSet.getString("correction_reason")).thenReturn(null);

        when(resultSet.getString("hold_reason")).thenReturn(null);
        when(resultSet.getObject("hold_expires_at", OffsetDateTime.class)).thenReturn(null);
        ReportRelease released = captor.getValue().mapRow(resultSet, 0);
        assertEquals(null, released.holdReason());
        assertEquals(null, released.holdExpiresAt());
    }

    @Test
    void parameterSourcesAreBuiltFromTheTenantAndRevision() {
        store.putLink(ORG, SUBJECT, new PatientChartLink(PATIENT, true, true));
        store.putReport(report(1, Status.RELEASED));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals(ORG, params.getAllValues().get(0).getValue("organizationId"));
        assertEquals(SUBJECT, params.getAllValues().get(0).getValue("subject"));
        assertEquals(REPORT, params.getAllValues().get(1).getValue("id"));
        assertEquals(1, params.getAllValues().get(1).getValue("revision"));
    }
}
