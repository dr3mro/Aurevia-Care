package com.aurevia.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.Evaluation;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Intervention;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.OutcomeReading;
import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;

/**
 * Deterministic branch coverage for the PostgreSQL {@link com.aurevia.therapy.TherapyStore}
 * adapter (PT-01, ARC-02). The JDBC boundary is mocked so empty-result and
 * write-count sides are forced without a race; normal paths are proven against
 * real PostgreSQL by the integration test. Every value here is synthetic.
 */
class JdbcTherapyStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String EPISODE = "33333333-3333-3333-3333-333333333333";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcTherapyStore store;

    /** Build a mocked JDBC boundary whose guarded transaction executes callbacks inline. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        store = new JdbcTherapyStore(jdbc, transactions);
    }

    @AfterEach
    void clear() {
        com.aurevia.tenant.TenantContext.clear();
    }

    private static Episode episode(EpisodeState state) {
        return new Episode(EPISODE, ORG, PATIENT, "referral-1", "referring-org", "referrer-a",
                state, 3, NOW, "2026-11-20T09:00:00Z", "knee pain");
    }

    private static Evaluation evaluation() {
        return new Evaluation(EPISODE, PATIENT, List.of("knee pain"), List.of(measurement()),
                List.of(goal()), 3, 6, "clinician-a", NOW, 1);
    }

    private static Measurement measurement() {
        return new Measurement("ROM", "90", "degrees", "2026-11-20T09:30:00Z");
    }

    private static Goal goal() {
        return new Goal("walk without pain", "independent", "2026-12-20");
    }

    private static Intervention intervention() {
        return new Intervention("manual therapy", "2026-11-21T09:00:00Z", "clinician-a");
    }

    private static OutcomeReading reading() {
        return new OutcomeReading("KOOS", 1, "65", "points", "2026-11-21T10:00:00Z");
    }

    private static ClinicalNote signedNote() {
        return new ClinicalNote(NoteKind.SESSION, 1, PATIENT, "session content", "clinician-a",
                "2026-11-21T10:00:00Z", 2, true, "clinician-a", NOW, "I attest", "checksum-1");
    }

    private static Summary summary() {
        return new Summary(1, "discharge summary", "referring-org", "referrer-a", true,
                "coordinator-a", NOW);
    }

    private static ReturnAcknowledgment acknowledgment() {
        return new ReturnAcknowledgment(1, "2026-11-22T10:00:00Z", "referrer-a", "received");
    }

    private static OutcomeScale scale(boolean approved) {
        return new OutcomeScale(ORG, "KOOS", 1, "knee outcome", "points", List.of("0", "65", "100"),
                approved, approved ? "governance-a" : null);
    }

    private static TherapyAggregate fullAggregate() {
        return new TherapyAggregate(episode(EpisodeState.DISCHARGED), evaluation(),
                List.of(intervention()), List.of(reading()), List.of(signedNote()),
                summary(), acknowledgment());
    }

    /** Stub a query that reads a named table so multiple queries coexist in one test. */
    private void stubQuery(String table, List<?> rows) {
        when(jdbc.query(contains(table), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Object>>any())).thenReturn((List) rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findReturnsEmptyWhenTheEpisodeIsAbsent() {
        stubQuery("FROM public.therapy_episode e", List.of());
        assertTrue(store.find(ORG, EPISODE).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findReconstructsTheWholeAggregateFromTenantTables() {
        stubQuery("FROM public.therapy_episode e", List.of(episode(EpisodeState.IN_TREATMENT)));
        stubQuery("FROM public.therapy_evaluation ev", List.of(evaluation()));
        stubQuery("FROM public.therapy_evaluation_problem p", List.of("knee pain"));
        stubQuery("FROM public.therapy_baseline_measurement b", List.of(measurement()));
        stubQuery("FROM public.therapy_goal g", List.of(goal()));
        stubQuery("FROM public.therapy_intervention i", List.of(intervention()));
        stubQuery("FROM public.therapy_outcome_reading r", List.of(reading()));
        stubQuery("FROM public.therapy_clinical_note n", List.of(signedNote()));
        stubQuery("FROM public.therapy_summary s", List.of(summary()));
        stubQuery("FROM public.therapy_return_acknowledgment a", List.of(acknowledgment()));

        TherapyAggregate found = store.find(ORG, EPISODE).orElseThrow();
        assertEquals(EpisodeState.IN_TREATMENT, found.episode().state());
        assertEquals(List.of("knee pain"), found.evaluation().problems());
        assertEquals(List.of(measurement()), found.evaluation().baselines());
        assertEquals(List.of(goal()), found.evaluation().goals());
        assertEquals(List.of(intervention()), found.interventions());
        assertEquals(List.of(reading()), found.readings());
        assertEquals(List.of(signedNote()), found.notes());
        assertEquals(summary(), found.summary());
        assertEquals(acknowledgment(), found.acknowledgment());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findReturnsNullOptionalChildrenWhenTheEpisodeHasNoEvaluation() {
        stubQuery("FROM public.therapy_episode e", List.of(episode(EpisodeState.ACTIVE)));
        stubQuery("FROM public.therapy_evaluation ev", List.of());
        stubQuery("FROM public.therapy_summary s", List.of());
        stubQuery("FROM public.therapy_return_acknowledgment a", List.of());

        TherapyAggregate found = store.find(ORG, EPISODE).orElseThrow();
        assertEquals(null, found.evaluation());
        assertEquals(null, found.summary());
        assertEquals(null, found.acknowledgment());
        assertTrue(found.notes().isEmpty());
    }

    @Test
    void savePersistsEveryFactAndSignsTheDraftNote() {
        store.save(fullAggregate());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(11)).update(sql.capture(), params.capture());
        String allSql = String.join(" ", sql.getAllValues());
        assertTrue(allSql.contains("public.therapy_episode"));
        assertTrue(allSql.contains("therapy_evaluation_problem"));
        assertTrue(allSql.contains("therapy_evaluation"));
        assertTrue(allSql.contains("therapy_baseline_measurement"));
        assertTrue(allSql.contains("therapy_goal"));
        assertTrue(allSql.contains("therapy_intervention"));
        assertTrue(allSql.contains("therapy_outcome_reading"));
        assertTrue(allSql.contains("therapy_clinical_note"));
        assertTrue(allSql.contains("therapy_summary"));
        assertTrue(allSql.contains("therapy_return_acknowledgment"));
        SqlParameterSource sign = params.getAllValues().stream()
                .filter(p -> p.hasValue("signedBy"))
                .filter(p -> "clinician-a".equals(p.getValue("signedBy"))).findFirst().orElseThrow();
        assertEquals("checksum-1", sign.getValue("checksum"));
        assertEquals("I attest", sign.getValue("attestation"));
    }

    @Test
    void goalsKeepTheirClinicalOrderAndDuplicateDescriptionsDoNotCollide() {
        Goal first = new Goal("walk without pain", "independent", "2026-12-20");
        Goal second = new Goal("climb stairs", "assisted", "2026-12-27");
        Goal third = new Goal("walk without pain", "community", "2027-01-10");
        Evaluation evaluation = new Evaluation(EPISODE, PATIENT, List.of(), List.of(),
                List.of(first, second, third), 3, 6, "clinician-a", NOW, 1);
        store.save(new TherapyAggregate(episode(EpisodeState.IN_TREATMENT), evaluation,
                List.of(), List.of(), List.of(), null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), params.capture());
        List<Integer> ordinals = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("INTO public.therapy_goal")) {
                ordinals.add((Integer) params.getAllValues().get(i).getValue("ordinal"));
                descriptions.add((String) params.getAllValues().get(i).getValue("description"));
            }
        }
        assertEquals(List.of(1, 2, 3), ordinals);
        assertEquals(List.of("walk without pain", "climb stairs", "walk without pain"), descriptions);
    }

    @Test
    void saveOfANewEpisodeOnlyWritesTheEpisode() {
        store.save(new TherapyAggregate(episode(EpisodeState.ACTIVE), null,
                List.of(), List.of(), List.of(), null, null));
        verify(jdbc, times(1)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void saveOfAnUnsignedDraftDoesNotIssueASignUpdate() {
        ClinicalNote draft = new ClinicalNote(NoteKind.PROGRESS, 1, PATIENT, "draft", "clinician-a",
                "2026-11-21T10:00:00Z", null, false, null, null, null, null);
        store.save(new TherapyAggregate(episode(EpisodeState.IN_TREATMENT), null,
                List.of(), List.of(), List.of(draft), null, null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getAllValues().stream().noneMatch(s -> s.contains("signed_by =")));
    }

    @Test
    void hasReferralCoversBothQueryOutcomes() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE, Boolean.FALSE);
        assertTrue(store.hasReferral(ORG, "referral-1"));
        assertFalse(store.hasReferral(ORG, "referral-2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scaleReturnsTheExactVersionOrEmpty() {
        stubQuery("sc.version = :version", List.of(scale(true)));
        assertEquals(scale(true), store.scale(ORG, "KOOS", 1).orElseThrow());
        stubQuery("sc.version = :version", List.of());
        assertTrue(store.scale(ORG, "KOOS", 9).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scalesReturnsTheVersionHistory() {
        stubQuery("ORDER BY sc.version", List.of(scale(true), scale(false)));
        assertEquals(2, store.scales(ORG, "KOOS").size());
    }

    @Test
    void allocateScaleVersionReturnsTheDatabaseAllocatedNumber() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(4);
        assertEquals(4, store.allocateScaleVersion(ORG, "KOOS"));
    }

    @Test
    void saveScaleWritesTheDefinitionAndApprovalParameters() {
        store.saveScale(scale(false));
        store.saveScale(scale(true));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), params.capture());
        assertEquals("KOOS", params.getAllValues().get(0).getValue("key"));
        assertEquals(false, params.getAllValues().get(0).getValue("approved"));
        assertEquals(true, params.getAllValues().get(1).getValue("approved"));
        assertEquals("governance-a", params.getAllValues().get(1).getValue("approvedBy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditReturnsTheAppendOnlyTrail() {
        TherapyAuditEvent event = new TherapyAuditEvent(ORG, "clinician-a", "EPISODE_ACCEPT", EPISODE,
                "SUCCESS");
        stubQuery("FROM public.therapy_audit_event a", List.of(event));
        assertEquals(List.of(event), store.audit(ORG));
    }

    @Test
    void appendAuditWritesOneFact() {
        store.appendAudit(new TherapyAuditEvent(ORG, "clinician-a", "EPISODE_ACCEPT", EPISODE,
                "SUCCESS"));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals("EPISODE_ACCEPT", params.getValue().getValue("action"));
    }

    @Test
    void episodeMapperReadsTheLifecycleColumns() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("id")).thenReturn(EPISODE);
        when(resultSet.getString("organization_id")).thenReturn(ORG);
        when(resultSet.getString("patient_id")).thenReturn(PATIENT);
        when(resultSet.getString("referral_id")).thenReturn("referral-1");
        when(resultSet.getString("referrer_org")).thenReturn("referring-org");
        when(resultSet.getString("referrer_subject")).thenReturn("referrer-a");
        when(resultSet.getString("state")).thenReturn("in_treatment");
        when(resultSet.getInt("version")).thenReturn(3);
        when(resultSet.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(resultSet.getString("received_at")).thenReturn("2026-11-20T09:00:00Z");
        when(resultSet.getString("clinical_question")).thenReturn("knee pain");
        assertEquals(episode(EpisodeState.IN_TREATMENT), JdbcTherapyStore.EPISODE_MAPPER.mapRow(resultSet, 0));
    }

    @Test
    void scaleMapperReadsThePermittedValuesArray() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(new String[] {"0", "65", "100"});
        when(resultSet.getArray("permitted_values")).thenReturn(array);
        when(resultSet.getString("organization_id")).thenReturn(ORG);
        when(resultSet.getString("key")).thenReturn("KOOS");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("description")).thenReturn("knee outcome");
        when(resultSet.getString("unit")).thenReturn("points");
        when(resultSet.getBoolean("approved")).thenReturn(true);
        when(resultSet.getString("approved_by")).thenReturn("governance-a");
        assertEquals(scale(true), JdbcTherapyStore.SCALE_MAPPER.mapRow(resultSet, 0));
    }

    @Test
    void noteMapperReadsTheSignatureColumns() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("kind")).thenReturn("session");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("patient_id")).thenReturn(PATIENT);
        when(resultSet.getString("content")).thenReturn("session content");
        when(resultSet.getString("author")).thenReturn("clinician-a");
        when(resultSet.getString("effective_at")).thenReturn("2026-11-21T10:00:00Z");
        when(resultSet.getObject("template_version", Integer.class)).thenReturn(2);
        when(resultSet.getBoolean("signed")).thenReturn(true);
        when(resultSet.getString("signed_by")).thenReturn("clinician-a");
        when(resultSet.getObject("signed_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(resultSet.getString("attestation")).thenReturn("I attest");
        when(resultSet.getString("content_checksum")).thenReturn("checksum-1");
        assertEquals(signedNote(), JdbcTherapyStore.NOTE_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getBoolean("signed")).thenReturn(false);
        when(resultSet.getObject("template_version", Integer.class)).thenReturn(null);
        when(resultSet.getObject("signed_at", OffsetDateTime.class)).thenReturn(null);
        ClinicalNote draft = JdbcTherapyStore.NOTE_MAPPER.mapRow(resultSet, 0);
        assertFalse(draft.signed());
        assertEquals(null, draft.templateVersion());
    }

    @Test
    void scalarMappersReadEveryColumn() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);

        when(resultSet.getString("episode_id")).thenReturn(EPISODE);
        when(resultSet.getString("author")).thenReturn("clinician-a");
        when(resultSet.getObject("recorded_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(resultSet.getInt("frequency_per_week")).thenReturn(3);
        when(resultSet.getInt("duration_weeks")).thenReturn(6);
        when(resultSet.getInt("version")).thenReturn(1);
        Evaluation mappedEvaluation = JdbcTherapyStore.EVALUATION_MAPPER.mapRow(resultSet, 0);
        assertEquals(Evaluation.class, mappedEvaluation.getClass());
        assertEquals(3, mappedEvaluation.frequencyPerWeek());
        assertEquals(6, mappedEvaluation.durationWeeks());
        assertEquals("clinician-a", mappedEvaluation.author());

        when(resultSet.getString("code")).thenReturn("ROM");
        when(resultSet.getString("value")).thenReturn("90");
        when(resultSet.getString("unit")).thenReturn("degrees");
        when(resultSet.getString("observed_at")).thenReturn("2026-11-20T09:30:00Z");
        assertEquals(measurement(), JdbcTherapyStore.BASELINE_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("description")).thenReturn("walk without pain");
        when(resultSet.getString("target")).thenReturn("independent");
        when(resultSet.getString("target_date")).thenReturn("2026-12-20");
        assertEquals(goal(), JdbcTherapyStore.GOAL_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("description")).thenReturn("manual therapy");
        when(resultSet.getString("performed_at")).thenReturn("2026-11-21T09:00:00Z");
        assertEquals(intervention(), JdbcTherapyStore.INTERVENTION_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("scale_key")).thenReturn("KOOS");
        when(resultSet.getInt("scale_version")).thenReturn(1);
        when(resultSet.getString("value")).thenReturn("65");
        when(resultSet.getString("unit")).thenReturn("points");
        when(resultSet.getString("observed_at")).thenReturn("2026-11-21T10:00:00Z");
        assertEquals(reading(), JdbcTherapyStore.READING_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("content")).thenReturn("discharge summary");
        when(resultSet.getString("referrer_org")).thenReturn("referring-org");
        when(resultSet.getString("referrer_subject")).thenReturn("referrer-a");
        when(resultSet.getBoolean("approved")).thenReturn(true);
        when(resultSet.getString("approved_by")).thenReturn("coordinator-a");
        when(resultSet.getObject("sent_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertEquals(summary(), JdbcTherapyStore.SUMMARY_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getInt("summary_version")).thenReturn(1);
        when(resultSet.getString("received_at")).thenReturn("2026-11-22T10:00:00Z");
        when(resultSet.getString("received_by")).thenReturn("referrer-a");
        when(resultSet.getString("note")).thenReturn("received");
        assertEquals(acknowledgment(), JdbcTherapyStore.ACK_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("organization_id")).thenReturn(ORG);
        when(resultSet.getString("actor_subject")).thenReturn("clinician-a");
        when(resultSet.getString("action")).thenReturn("EPISODE_ACCEPT");
        when(resultSet.getString("resource_id")).thenReturn(EPISODE);
        when(resultSet.getString("outcome")).thenReturn("SUCCESS");
        assertEquals(new TherapyAuditEvent(ORG, "clinician-a", "EPISODE_ACCEPT", EPISODE, "SUCCESS"),
                JdbcTherapyStore.AUDIT_MAPPER.mapRow(resultSet, 0));

        when(resultSet.getString("problem")).thenReturn("knee pain");
        assertEquals("knee pain", JdbcTherapyStore.PROBLEM_MAPPER.mapRow(resultSet, 0));
    }
}
