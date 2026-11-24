package com.aurevia.therapy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.api.ApiException;
import com.aurevia.therapy.JdbcTherapyStore;
import com.aurevia.therapy.TherapyContract.AckRequest;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;
import com.aurevia.therapy.TherapyService;
import com.aurevia.tenant.TenantContext;

/**
 * PT-01 clause 3 (API evidence): sign session, progress and discharge notes,
 * send approved summaries to the referrer and document return acknowledgment.
 *
 * <p>The three closure commands are durable domain operations over the V77/V85
 * schema: each signed note is bound to an attestation and checksum and is
 * immutable thereafter, a signed discharge closes the episode, a summary is
 * disclosed only to the episode's verified referrer, and an acknowledgment is
 * bound to one exact summary revision. This class drives those services through
 * their normal tenant-guarded entry point against a fresh PostgreSQL database
 * applied from the production Flyway migrations. Two synthetic tenants hold
 * identical local identifiers so a cross-tenant read is observable, and every
 * fixture is synthetic — no real identity or patient data is used.</p>
 *
 * <p>These commands have no HTTP controller route today; the transport gap is
 * recorded in {@code docs/spec/clauses/therapy/sign-session-progress-discharge-notes-send-approved.md}
 * and is why the clause is reported as partly proven rather than proven.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_pt_clause3_it")
@ActiveProfiles("synthetic-auth")
class SignSessionProgressDischargeNotesSendApprovedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "pt-shared";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String REFERRER_ORG = "referring-org";
    private static final String REFERRER_SUBJECT = "referrer-a";
    private static final String SUMMARY_CONTENT = "approved discharge summary";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_pt_clause3_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcTherapyStore store;

    @Autowired
    TherapyService service;

    /** Create a fresh database, apply all migrations and seed two synthetic tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_pt_clause3_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_pt_clause3_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_pt_clause3_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with a synthetic PT clinician, membership and patient. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + SUBJECT + "', 'synthetic clinician')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + SUBJECT + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'pt_clinician', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + SUBJECT + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "SELECT '" + org + "', '" + PATIENT + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20', id FROM public.user_identity WHERE subject = '" + SUBJECT + "'");
    }

    /** Clear the bound tenant after each test so no context leaks between tests. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Build a synthetic PT clinician actor for an organization. */
    private static Actor clinician(String org) {
        return Actor.of(org, SUBJECT, TherapyService.PT_CLINICIAN);
    }

    /** Run an action inside a bound tenant context, binding the guarded transaction. */
    private static <T> T as(String org, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, SUBJECT, "THERAPY", "therapy-clause3-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Accept a fresh synthetic referral into ORG_A. */
    private Episode accept(String referralId) {
        return as(ORG_A, () -> service.acceptReferral(clinician(ORG_A), new ReferralRequest(
                referralId, PATIENT, REFERRER_ORG, REFERRER_SUBJECT,
                "2026-11-20T09:00:00Z", "knee pain")));
    }

    /** Record the initial evaluation that opens treatment for an episode. */
    private void evaluate(String id) {
        Actor actor = clinician(ORG_A);
        int version = version(actor, id);
        as(ORG_A, () -> {
            service.recordEvaluation(actor, id, new EvaluationRequest(
                    List.of("knee pain"),
                    List.of(new Measurement("ROM", "60", "degrees", "2026-11-20T09:30:00Z")),
                    List.of(new Goal("walk", "independent", "2026-12-20")), 3, 6), version);
            return null;
        });
    }

    /** Read the current optimistic version of an episode. */
    private int version(Actor actor, String id) {
        return as(actor.organizationId(), () -> service.get(actor, id).episode().version());
    }

    /** Draft one note of a kind in ORG_A without signing it. */
    private void draftNote(String id, NoteKind kind, String content, String effectiveAt) {
        Actor actor = clinician(ORG_A);
        int version = version(actor, id);
        as(ORG_A, () -> {
            service.recordNote(actor, id, new NoteRequest(kind, content, effectiveAt, 1), version);
            return null;
        });
    }

    /** Draft and sign one note of a kind in ORG_A, returning the signed note. */
    private ClinicalNote draftAndSign(String id, NoteKind kind, String content, String effectiveAt) {
        Actor actor = clinician(ORG_A);
        int version = version(actor, id);
        return as(ORG_A, () -> {
            service.recordNote(actor, id, new NoteRequest(kind, content, effectiveAt, 1), version);
            return service.signNote(actor, id, kind, 1, "I attest " + kind, version + 1);
        });
    }

    /** Drive an episode to DISCHARGED by signing a session and a discharge note. */
    private String dischargedEpisode(String referralId) {
        String id = accept(referralId).id();
        evaluate(id);
        draftAndSign(id, NoteKind.SESSION, "session note", "2026-11-21T10:00:00Z");
        draftAndSign(id, NoteKind.DISCHARGE, "discharge note", "2026-11-30T10:00:00Z");
        return id;
    }

    /** Send the approved summary from ORG_A through the service. */
    private Summary sendSummary(String id) {
        Actor actor = clinician(ORG_A);
        int version = version(actor, id);
        return as(ORG_A, () -> service.sendSummary(actor, id,
                new SummaryRequest(SUMMARY_CONTENT, REFERRER_ORG, REFERRER_SUBJECT), version));
    }

    /** Record the referrer's return acknowledgment through the service. */
    private ReturnAcknowledgment acknowledge(String id, int summaryVersion) {
        Actor actor = clinician(ORG_A);
        int version = version(actor, id);
        return as(ORG_A, () -> service.acknowledgeReturn(actor, id,
                new AckRequest(summaryVersion, "2026-12-01T08:00:00Z", REFERRER_SUBJECT, "received"), version));
    }

    /** Positive proof: the three note kinds sign, discharge closes, and the approved summary is acknowledged. */
    @Test
    void signsSessionProgressAndDischargeNotesThenSendsApprovedSummaryWithReturnAcknowledgment()
            throws Exception {
        String id = accept("referral-clause3-happy").id();
        evaluate(id);

        ClinicalNote session = draftAndSign(id, NoteKind.SESSION, "session note", "2026-11-21T10:00:00Z");
        ClinicalNote progress = draftAndSign(id, NoteKind.PROGRESS, "progress note", "2026-11-25T10:00:00Z");
        assertTrue(session.signed());
        assertTrue(progress.signed());
        assertEquals(SUBJECT, session.signedBy());
        assertEquals(SUBJECT, progress.signedBy());
        assertNotNull(session.contentChecksum());
        assertNotNull(progress.contentChecksum());

        ClinicalNote discharge = draftAndSign(id, NoteKind.DISCHARGE, "discharge note", "2026-11-30T10:00:00Z");
        assertTrue(discharge.signed());
        assertNotNull(discharge.contentChecksum());

        TherapyAggregate closed = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(EpisodeState.DISCHARGED, closed.episode().state());

        Summary summary = sendSummary(id);
        assertTrue(summary.approved());
        assertEquals(1, summary.version());
        assertEquals(REFERRER_ORG, summary.referrerOrg());
        assertEquals(REFERRER_SUBJECT, summary.referrerSubject());
        assertEquals(SUBJECT, summary.approvedBy());

        ReturnAcknowledgment acknowledgment = acknowledge(id, summary.version());
        assertEquals(summary.version(), acknowledgment.summaryVersion());
        assertEquals(REFERRER_SUBJECT, acknowledgment.receivedBy());

        TherapyAggregate finalState = as(ORG_A, () -> service.get(clinician(ORG_A), id));
        assertEquals(3, finalState.notes().size());
        assertEquals(EpisodeState.DISCHARGED, finalState.episode().state());
        assertEquals(summary.version(), finalState.summary().version());
        assertEquals(acknowledgment.summaryVersion(), finalState.acknowledgment().summaryVersion());
        assertEquals(REFERRER_SUBJECT, as(ORG_A, () -> store.find(ORG_A, id).orElseThrow()
                .acknowledgment().receivedBy()));

        // A signed note is immutable at the database boundary too, under forced RLS.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.therapy_clinical_note SET content = 'tampered' "
                            + "WHERE episode_id = '" + id + "' AND kind = 'session' AND version = 1"));
            assertEquals("42501", failure.getSQLState());
        }
    }

    /** Signing is a clinician-only action; a coordinator may send and acknowledge but never sign. */
    @Test
    void coordinatorCannotSignANote() {
        String id = accept("referral-clause3-role").id();
        evaluate(id);
        draftNote(id, NoteKind.SESSION, "session draft", "2026-11-21T10:00:00Z");

        Actor coordinator = Actor.of(ORG_A, "coordinator", TherapyService.PT_COORDINATOR);
        int version = version(clinician(ORG_A), id);
        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.signNote(coordinator, id, NoteKind.SESSION, 1, "attest", version)));
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("NOTE_SIGN"));
        assertFalse(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().notes().get(0).signed()));
    }

    /** A peer tenant cannot sign another tenant's note and the note stays unsigned. */
    @Test
    void peerTenantCannotSignAnotherTenantsNote() {
        String id = accept("referral-clause3-peer").id();
        evaluate(id);
        draftNote(id, NoteKind.SESSION, "session draft", "2026-11-21T10:00:00Z");

        Actor peer = Actor.of(ORG_B, SUBJECT, TherapyService.PT_CLINICIAN);
        int version = version(clinician(ORG_A), id);
        ApiException failure = as(ORG_B, () -> assertThrows(ApiException.class,
                () -> service.signNote(peer, id, NoteKind.SESSION, 1, "attest", version)));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        assertFalse(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().notes().get(0).signed()));
    }

    /** Signing requires the version the caller read. */
    @Test
    void staleVersionCannotSignANote() {
        String id = accept("referral-clause3-stale-sign").id();
        evaluate(id);
        draftNote(id, NoteKind.PROGRESS, "progress draft", "2026-11-25T10:00:00Z");

        int version = version(clinician(ORG_A), id);
        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.signNote(clinician(ORG_A), id, NoteKind.PROGRESS, 1, "attest", version - 1)));
        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertFalse(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().notes().get(0).signed()));
    }

    /** A signed note is immutable and cannot be signed a second time. */
    @Test
    void aSignedNoteCannotBeSignedAgain() {
        String id = accept("referral-clause3-resign").id();
        evaluate(id);
        draftAndSign(id, NoteKind.SESSION, "session note", "2026-11-21T10:00:00Z");

        int version = version(clinician(ORG_A), id);
        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.signNote(clinician(ORG_A), id, NoteKind.SESSION, 1, "attest", version)));
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        assertEquals(version, as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().episode().version()));
    }

    /** A discharge note cannot be signed until a session or progress note is signed. */
    @Test
    void dischargeWithoutASignedSessionNoteIsRefused() {
        String id = accept("referral-clause3-discharge-guard").id();
        evaluate(id);
        draftNote(id, NoteKind.DISCHARGE, "discharge draft", "2026-11-30T10:00:00Z");

        int version = version(clinician(ORG_A), id);
        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.signNote(clinician(ORG_A), id, NoteKind.DISCHARGE, 1, "attest", version)));
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        assertEquals(EpisodeState.IN_TREATMENT,
                as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().episode().state()));
    }

    /** A summary may be disclosed only to the referrer verified on the episode. */
    @Test
    void summaryToAnUnverifiedReferrerIsRefused() {
        String id = dischargedEpisode("referral-clause3-referrer");
        int version = version(clinician(ORG_A), id);

        ApiException wrongOrg = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(clinician(ORG_A), id,
                        new SummaryRequest(SUMMARY_CONTENT, "other-org", REFERRER_SUBJECT), version)));
        assertEquals(409, wrongOrg.status());
        assertEquals("CONFLICT", wrongOrg.code());
        ApiException wrongSubject = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(clinician(ORG_A), id,
                        new SummaryRequest(SUMMARY_CONTENT, REFERRER_ORG, "someone-else"), version)));
        assertEquals(409, wrongSubject.status());
        assertNull(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().summary()));
    }

    /** An acknowledgment must name the exact summary revision that was sent. */
    @Test
    void acknowledgmentMustBindTheExactSummaryVersion() {
        String id = dischargedEpisode("referral-clause3-ack-version");
        sendSummary(id);
        int version = version(clinician(ORG_A), id);

        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.acknowledgeReturn(clinician(ORG_A), id,
                        new AckRequest(99, "2026-12-01T08:00:00Z", REFERRER_SUBJECT, "received"), version)));
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        assertNull(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().acknowledgment()));
    }

    /** One summary revision can be acknowledged only once. */
    @Test
    void aSummaryCannotBeAcknowledgedTwice() {
        String id = dischargedEpisode("referral-clause3-ack-twice");
        Summary summary = sendSummary(id);
        acknowledge(id, summary.version());
        int version = version(clinician(ORG_A), id);

        ApiException failure = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.acknowledgeReturn(clinician(ORG_A), id,
                        new AckRequest(summary.version(), "2026-12-02T08:00:00Z", REFERRER_SUBJECT, "again"),
                        version)));
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        assertEquals(summary.version(),
                as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().acknowledgment().summaryVersion()));
    }

    /** Non-PT staff cannot send an approved summary or record the referrer's acknowledgment. */
    @Test
    void nonPtStaffCannotSendTheApprovedSummaryOrRecordTheReturnAcknowledgment() {
        String id = dischargedEpisode("referral-clause3-nonpt");
        Summary summary = sendSummary(id);
        int version = version(clinician(ORG_A), id);

        Actor finance = Actor.of(ORG_A, "finance", "finance");
        ApiException sendDenied = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(finance, id,
                        new SummaryRequest("unapproved", REFERRER_ORG, REFERRER_SUBJECT), version)));
        assertEquals(403, sendDenied.status());
        assertEquals("FORBIDDEN", sendDenied.code());
        ApiException ackDenied = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.acknowledgeReturn(finance, id,
                        new AckRequest(summary.version(), "2026-12-01T08:00:00Z", REFERRER_SUBJECT, "received"),
                        version)));
        assertEquals(403, ackDenied.status());
        assertEquals("FORBIDDEN", ackDenied.code());

        Actor noRoles = Actor.of(ORG_A, "no-roles");
        ApiException noRolesDenied = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(noRoles, id,
                        new SummaryRequest("unapproved", REFERRER_ORG, REFERRER_SUBJECT), version)));
        assertEquals(403, noRolesDenied.status());
        assertEquals("no active membership", noRolesDenied.getMessage());

        TherapyAggregate unchanged = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(summary.version(), unchanged.summary().version());
        assertNull(unchanged.acknowledgment());
        assertEquals(version, unchanged.episode().version());
    }

    /** Summary send and return acknowledgment both require the current episode version. */
    @Test
    void summaryAndAcknowledgmentRejectStaleVersions() {
        String id = dischargedEpisode("referral-clause3-stale-summary");
        int staleVersion = version(clinician(ORG_A), id) - 1;

        ApiException staleSend = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(clinician(ORG_A), id,
                        new SummaryRequest(SUMMARY_CONTENT, REFERRER_ORG, REFERRER_SUBJECT), staleVersion)));
        assertEquals(412, staleSend.status());
        assertEquals("STALE_VERSION", staleSend.code());

        Summary summary = sendSummary(id);
        int staleAckVersion = version(clinician(ORG_A), id) - 1;
        ApiException staleAck = as(ORG_A, () -> assertThrows(ApiException.class,
                () -> service.acknowledgeReturn(clinician(ORG_A), id,
                        new AckRequest(summary.version(), "2026-12-01T08:00:00Z", REFERRER_SUBJECT, "received"),
                        staleAckVersion)));
        assertEquals(412, staleAck.status());
        assertEquals("STALE_VERSION", staleAck.code());
        assertNull(as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().acknowledgment()));
    }

    /** The referrer-facing commands conceal another tenant's episode under forced RLS. */
    @Test
    void closureCommandsConcealPeerTenantEpisodes() {
        String id = dischargedEpisode("referral-clause3-peer-summary");
        Summary summary = sendSummary(id);
        int version = version(clinician(ORG_A), id);

        Actor peer = Actor.of(ORG_B, SUBJECT, TherapyService.PT_CLINICIAN);
        ApiException sendDenied = as(ORG_B, () -> assertThrows(ApiException.class,
                () -> service.sendSummary(peer, id,
                        new SummaryRequest("leak", REFERRER_ORG, REFERRER_SUBJECT), version)));
        assertEquals(404, sendDenied.status());
        assertEquals("NOT_FOUND", sendDenied.code());
        ApiException ackDenied = as(ORG_B, () -> assertThrows(ApiException.class,
                () -> service.acknowledgeReturn(peer, id,
                        new AckRequest(summary.version(), "2026-12-01T08:00:00Z", REFERRER_SUBJECT, "leak"),
                        version)));
        assertEquals(404, ackDenied.status());
        assertEquals("NOT_FOUND", ackDenied.code());

        TherapyAggregate unchanged = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(summary.version(), unchanged.summary().version());
        assertNull(unchanged.acknowledgment());
        assertEquals(version, unchanged.episode().version());

        Actor auditor = Actor.of(ORG_B, "auditor", TherapyService.PRIVACY_AUDITOR);
        List<TherapyAuditEvent> peerAudit = as(ORG_B, () -> service.auditTrail(auditor));
        assertTrue(peerAudit.stream().anyMatch(event -> "NOT_FOUND".equals(event.outcome())
                && id.equals(event.resourceId())));
    }
}
