package com.aurevia.messaging.enrollment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.CaseState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind;

/**
 * Deterministic branch coverage for the JDBC patient-access adapter.
 *
 * <p>Every read mapper, empty-result side, compare-and-set side and digest
 * failure is driven here with synthetic records and a mocked JDBC boundary;
 * end-to-end behaviour is proven by {@code JdbcPatientAccessStoreIntegrationTest}.</p>
 */
class JdbcPatientAccessStoreBranchTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHART = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SUBJECT = "syn-patient";
    private static final String FACTS = "site-verified-facts";
    private static final String CODE = "opaque-code";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private PatientAccessService.DigestFactory digester;
    private JdbcPatientAccessStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        digester = () -> MessageDigest.getInstance("SHA-256");
        store = new JdbcPatientAccessStore(jdbc, transactions, digester);
    }

    private static String digest(String value) throws NoSuchAlgorithmException {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            result.append(Character.forDigit((current >> 4) & 0xf, 16));
            result.append(Character.forDigit(current & 0xf, 16));
        }
        return result.toString();
    }

    private static OffsetDateTime asDateTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountMapperRestoresStateLinksAndOptionalContact() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Array links = mock(Array.class);
        when(links.getArray()).thenReturn(new String[] { CHART });
        when(rs.getArray("linked_chart_ids")).thenReturn(links);
        when(rs.getString("id")).thenReturn(ID);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("subject")).thenReturn(SUBJECT);
        when(rs.getString("fact_digest")).thenReturn(digest(FACTS));
        when(rs.getString("state")).thenReturn(AccountState.ACTIVE.wire());
        when(rs.getInt("failed_attempts")).thenReturn(3);
        when(rs.getString("contact_digest")).thenReturn("syn-contact");
        when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PatientAccount>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<PatientAccount> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        PatientAccount account = store.account(ORG, ID).orElseThrow();
        assertEquals(ID, account.id());
        assertEquals(ORG, account.organizationId());
        assertEquals(SUBJECT, account.subject());
        assertEquals(Set.of(CHART), account.linkedChartIds());
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(3, account.failedAttempts());
        assertEquals("syn-contact", account.contactDigest());
        assertEquals(NOW, account.createdAt());
    }

    @Test
    void accountBySubjectReturnsEmptyWhenNoRowMatches() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertTrue(store.accountBySubject(ORG, SUBJECT).isEmpty());
    }

    @Test
    void invitationMapperRestoresPendingInvitation() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(ID);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("chart_id")).thenReturn(CHART);
        when(rs.getString("fact_digest")).thenReturn(digest(FACTS));
        when(rs.getString("state")).thenReturn(InvitationState.PENDING.wire());
        when(rs.getInt("failed_attempts")).thenReturn(2);
        when(rs.getString("issued_by")).thenReturn("reception");
        when(rs.getObject("expires_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));
        when(rs.getObject("issued_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Invitation>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Invitation> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        Invitation invitation = store.invitation(ORG, CODE).orElseThrow();
        assertEquals(ID, invitation.code());
        assertEquals(CHART, invitation.chartId());
        assertEquals(InvitationState.PENDING, invitation.state());
        assertEquals(2, invitation.failedAttempts());
        assertEquals("reception", invitation.issuedBy());
    }

    @Test
    void invitationReturnsEmptyWhenNoRowMatches() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertTrue(store.invitation(ORG, CODE).isEmpty());
    }

    @Test
    void tokenMapperRestoresSingleUseState() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(ID);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("subject")).thenReturn(SUBJECT);
        when(rs.getString("purpose")).thenReturn("recovery");
        when(rs.getObject("expires_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));
        when(rs.getBoolean("used")).thenReturn(true);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AccessToken>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<AccessToken> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        AccessToken token = store.token(ORG, CODE).orElseThrow();
        assertEquals("recovery", token.purpose());
        assertTrue(token.used());
        assertEquals(NOW, token.expiresAt());
    }

    @Test
    void tokenReturnsEmptyWhenNoRowMatches() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertTrue(store.token(ORG, CODE).isEmpty());
    }

    @Test
    void supportCaseMapperAndListRestoreStateAndOptionalAssignee() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(ID);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("patient_id")).thenReturn(SUBJECT);
        when(rs.getString("kind")).thenReturn(SupportKind.RECORD_ACCESS.wire());
        when(rs.getString("summary")).thenReturn("synthetic summary");
        when(rs.getString("state")).thenReturn(CaseState.IN_REVIEW.wire());
        when(rs.getObject("opened_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<SupportCase>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<SupportCase> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SupportCase>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<SupportCase> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        SupportCase found = store.supportCase(ORG, ID).orElseThrow();
        assertEquals(SupportKind.RECORD_ACCESS, found.kind());
        assertEquals(CaseState.IN_REVIEW, found.state());
        assertNull(found.assignee());
        assertEquals(List.of(found), store.supportCases(ORG));
    }

    @Test
    void supportCaseAndListAreEmptyWhenNoRowsMatch() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertTrue(store.supportCase(ORG, ID).isEmpty());
        assertTrue(store.supportCases(ORG).isEmpty());
    }

    @Test
    void eventAndNotificationMappersRestoreAppendOnlyFacts() throws Exception {
        ResultSet eventRow = mock(ResultSet.class);
        when(eventRow.getString("id")).thenReturn(ID);
        when(eventRow.getString("organization_id")).thenReturn(ORG);
        when(eventRow.getString("subject")).thenReturn(SUBJECT);
        when(eventRow.getString("event_type")).thenReturn(EventType.ENROLL_SUCCESS.wire());
        when(eventRow.getString("resource_id")).thenReturn(ID);
        when(eventRow.getString("outcome")).thenReturn("success");
        when(eventRow.getString("detail")).thenReturn("enrolled");
        when(eventRow.getObject("occurred_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));

        ResultSet noticeRow = mock(ResultSet.class);
        when(noticeRow.getString("subject")).thenReturn(SUBJECT);
        when(noticeRow.getString("organization_id")).thenReturn(ORG);
        when(noticeRow.getString("event_type")).thenReturn("enrollment_confirmed");
        when(noticeRow.getObject("occurred_at", OffsetDateTime.class)).thenReturn(asDateTime(NOW));

        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    ResultSet row = sql.contains("patient_access_event") ? eventRow : noticeRow;
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<AccessEvent> events = store.audit(ORG);
        assertEquals(EventType.ENROLL_SUCCESS, events.getFirst().eventType());
        assertEquals(NOW, events.getFirst().occurredAt());
        List<GenericNotification> notices = store.notifications(ORG);
        assertEquals("enrollment_confirmed", notices.getFirst().eventType());
        assertEquals(ORG, notices.getFirst().organizationId());
    }

    @Test
    void eventAndNotificationListsAreEmptyWhenNoRowsMatch() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertTrue(store.audit(ORG).isEmpty());
        assertTrue(store.notifications(ORG).isEmpty());
    }

    @Test
    void writesCoverAllUpsertsAndAppendPaths() throws Exception {
        PatientAccount account = new PatientAccount(ID, ORG, SUBJECT, Set.of(CHART), digest(FACTS),
                AccountState.ACTIVE, 0, null, NOW);
        Invitation invitation = new Invitation(ID, ORG, CHART, digest(FACTS), NOW,
                InvitationState.PENDING, 0, "reception", NOW);
        AccessToken token = new AccessToken(CODE, ORG, SUBJECT, "recovery", NOW, false);
        SupportCase supportCase = new SupportCase(ID, ORG, SUBJECT, SupportKind.ACCESSIBILITY,
                "synthetic summary", CaseState.OPEN, null, NOW);
        AccessEvent event = new AccessEvent(ID, ORG, SUBJECT, EventType.INVITATION_ISSUED, ID,
                "success", "issued", NOW);
        GenericNotification notification = new GenericNotification(SUBJECT, ORG, "recovery_issued", NOW);

        store.putAccount(ORG, account);
        store.putInvitation(ORG, invitation);
        store.putToken(ORG, token);
        store.putSupportCase(ORG, supportCase);
        store.appendAudit(event);
        store.appendNotification(notification);
        store.recordInvitationFailure(ORG, CODE, 1);

        assertEquals(7, org.mockito.Mockito.mockingDetails(jdbc).getInvocations().size());
    }

    @Test
    void chartExistsCoversBothSides() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE, Boolean.FALSE);
        assertTrue(store.chartExists(ORG, CHART));
        assertFalse(store.chartExists(ORG, CHART));
    }

    @Test
    void consumeInvitationCoversBothSides() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE, Boolean.FALSE, null);
        assertTrue(store.consumeInvitation(ORG, CODE));
        assertFalse(store.consumeInvitation(ORG, CODE));
        assertFalse(store.consumeInvitation(ORG, CODE));
    }

    @Test
    void consumeTokenCoversBothSides() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1, 0);
        assertTrue(store.consumeToken(ORG, CODE));
        assertFalse(store.consumeToken(ORG, CODE));
    }

    @Test
    void missingDigestFailsClosed() {
        JdbcPatientAccessStore failing = new JdbcPatientAccessStore(jdbc, transactionTemplate(), () -> {
            throw new NoSuchAlgorithmException("synthetic failure");
        });
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> failing.consumeInvitation(ORG, CODE));
        assertTrue(exception.getMessage().contains("SHA-256"));
    }

    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactions;
    }
}
