package com.aurevia.smartclinic.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RX-02 clause 4 acceptance through the real HTTP and PostgreSQL boundary.
 *
 * <p>Submission, delivery confirmation, pharmacy acceptance and rejection,
 * clarification, renewal and cancellation are each appended as a distinct
 * immutable fact in the RX-02 event ledger. They are never collapsed into a
 * single status: acceptance is not dispensing, delivery is not acceptance, and
 * a renewal records a fact without resetting the clinical state. Two synthetic
 * prescriptions are needed for the positive trail because acceptance and
 * rejection are mutually exclusive outcomes in the state machine.</p>
 *
 * <p>Negative cases prove the same trail refuses a wrong role, a peer tenant, a
 * stale precondition, forbidden states, a mismatched submission id and a
 * missing precondition without appending any event. Fixtures use two synthetic
 * organizations sharing local identifiers and bounded synthetic subjects; no
 * patient data or credentials are used. The suite is tagged {@code integration}
 * so the fast gate skips it and the integrator runs it on real PostgreSQL.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_rx02_clause4_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class TrackSubmittedDeliveryConfirmedPharmacyAcceptedRejectedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_rx02_clause4_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN = "clinician-1111";
    private static final String PHARMACY = "pharmacy-1111";
    private static final String PEER_CLINICIAN = "clinician-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Initialize the REST client against the random local port.
     *
     * @param port port assigned by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Recreate the database, migrate it and seed two synthetic tenants.
     *
     * @throws Exception on database setup failure
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                String suffix = suffix(org);
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "other-" + suffix, "clinician");
                seedMember(statement, org, "pharmacy-" + suffix, "pharmacy_staff");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
            }
            // The prescribing clinician in A is entitled and both channels are
            // approved, so only the state machine is under test in the positives.
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.market", "US");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement.clinician-1111", "enabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
            seedPolicy(statement, ORG_B, "admin-2222", "rx02.market", "US");
            seedPolicy(statement, ORG_B, "admin-2222", "rx02.entitlement.clinician-2222", "enabled");
            seedPolicy(statement, ORG_B, "admin-2222", "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
        }
    }

    /**
     * Drop the synthetic database.
     *
     * @throws Exception on database cleanup failure
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /**
     * Every clinical fact is appended as its own immutable event in order, and
     * acceptance and rejection are separate event types with separate trails.
     */
    @Test
    void tracksSubmittedDeliveryConfirmedAcceptedRejectedClarificationRenewalAndCancellationAsSeparateEvents() {
        String id = newId();
        ResponseEntity<String> created = create(ORG_A, CLINICIAN, id, CLINICIAN);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created, "/state"));
        assertEquals("1", field(created, "/version"));

        ResponseEntity<String> attested = post(ORG_A, CLINICIAN, id + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.OK, attested.getStatusCode(), attested.getBody());
        assertEquals("attested", field(attested, "/state"));
        assertEquals("2", field(attested, "/version"));

        ResponseEntity<String> transmitted = transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted");
        assertEquals(HttpStatus.OK, transmitted.getStatusCode(), transmitted.getBody());
        assertEquals("submitted", field(transmitted, "/state"));
        assertEquals("E_PRESCRIBE", field(transmitted, "/channel"));
        assertEquals("3", field(transmitted, "/version"));
        String sid = field(transmitted, "/submissionId");
        assertNotNull(sid);

        ResponseEntity<String> confirmed = confirm(ORG_A, CLINICIAN, id, 3, sid);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("delivery_confirmed", field(confirmed, "/state"));
        assertEquals("4", field(confirmed, "/version"));

        ResponseEntity<String> clarified = pharmacy(ORG_A, PHARMACY, id, 4, sid,
                "clarification", "synthetic question");
        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());
        assertEquals("clarification", field(clarified, "/state"));
        assertEquals("5", field(clarified, "/version"));

        ResponseEntity<String> accepted = pharmacy(ORG_A, PHARMACY, id, 5, sid, "accepted", "synthetic");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", field(accepted, "/state"));
        assertEquals("6", field(accepted, "/version"));
        // Acceptance is not dispensing: no such fact exists at this point.
        assertFalse(eventTypes(ORG_A, CLINICIAN, id).contains("dispensing_evidence"));

        ResponseEntity<String> renewed = post(ORG_A, CLINICIAN, id + "/renew", 6,
                "{\"note\":\"synthetic renewal\"}");
        assertEquals(HttpStatus.OK, renewed.getStatusCode(), renewed.getBody());
        assertEquals("accepted", field(renewed, "/state"), "a renewal does not reset the clinical state");
        assertEquals("7", field(renewed, "/version"));

        ResponseEntity<String> cancelled = post(ORG_A, CLINICIAN, id + "/cancel", 7,
                "{\"reason\":\"synthetic cancellation\"}");
        assertEquals(HttpStatus.OK, cancelled.getStatusCode(), cancelled.getBody());
        assertEquals("cancelled", field(cancelled, "/state"));
        assertEquals("8", field(cancelled, "/version"));

        JsonNode view = json(get(ORG_A, CLINICIAN, id));
        assertEquals("cancelled", view.at("/prescription/state").asText());
        assertEquals("8", view.at("/prescription/version").asText());
        JsonNode events = view.at("/events");
        assertEquals(7, eventCount(ORG_A, CLINICIAN, id), "one append-only row per distinct fact");
        assertEquals(7, events.size());
        assertEquals(List.of("attested", "submitted", "delivery_confirmed", "clarification",
                        "pharmacy_accepted", "renewal", "cancelled"), eventTypes(events),
                "each fact keeps its own ordered event type");
        assertTrue(events.at("/0/submissionId").isNull(), "attestation precedes the submission");
        for (int index = 1; index < events.size(); index++) {
            assertEquals(sid, events.at("/" + index + "/submissionId").asText(),
                    "every post-submission fact links to the one stable submission id");
        }
        for (int index = 0; index < events.size(); index++) {
            assertEquals(String.valueOf(index + 2),
                    events.at("/" + index + "/prescriptionVersion").asText(),
                    "each fact is bound to the version it advanced");
        }
        assertEquals(1, countSubmissionRows(ORG_A, id), "one logical submission for the whole trail");
        assertEquals(0, countEventsOfType(ORG_A, id, "dispensing_evidence"));
        assertEquals(0, countEventsOfType(ORG_A, id, "pharmacy_rejected"));

        // Acceptance and rejection cannot coexist on one prescription: drive a
        // second synthetic prescription to prove the rejected fact is separate.
        String rejectedId = newId();
        assertEquals(HttpStatus.CREATED, create(ORG_A, CLINICIAN, rejectedId, CLINICIAN).getStatusCode());
        assertEquals(HttpStatus.OK, post(ORG_A, CLINICIAN, rejectedId + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}").getStatusCode());
        String rejectedSid = field(transmit(ORG_A, CLINICIAN, rejectedId, 2,
                "E_PRESCRIBE", "submitted"), "/submissionId");
        assertNotNull(rejectedSid);
        assertEquals(HttpStatus.OK, confirm(ORG_A, CLINICIAN, rejectedId, 3, rejectedSid).getStatusCode());
        ResponseEntity<String> rejected = pharmacy(ORG_A, PHARMACY, rejectedId, 4, rejectedSid,
                "rejected", "synthetic reason");
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", field(rejected, "/state"));
        assertEquals("5", field(rejected, "/version"));

        JsonNode rejectedView = json(get(ORG_A, CLINICIAN, rejectedId));
        assertEquals("rejected", rejectedView.at("/prescription/state").asText());
        assertEquals("5", rejectedView.at("/prescription/version").asText());
        JsonNode rejectedEvents = rejectedView.at("/events");
        assertEquals(4, eventCount(ORG_A, CLINICIAN, rejectedId));
        assertEquals(4, rejectedEvents.size());
        assertEquals(List.of("attested", "submitted", "delivery_confirmed", "pharmacy_rejected"),
                eventTypes(rejectedEvents), "rejection is its own fact, not an acceptance");
        assertEquals(rejectedSid, rejectedEvents.at("/3/submissionId").asText());
        assertEquals(0, countEventsOfType(ORG_A, rejectedId, "pharmacy_accepted"));
        assertEquals(1, countSubmissionRows(ORG_A, rejectedId));
    }

    /**
     * Dispensing evidence is appended as a fact distinct from the earlier
     * acceptance and only after acceptance, never as part of it.
     */
    @Test
    void dispensingEvidenceIsRecordedAsAFactDistinctFromAcceptance() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String sid = field(transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted"), "/submissionId");
        assertEquals(HttpStatus.OK, confirm(ORG_A, CLINICIAN, id, 3, sid).getStatusCode());
        assertEquals(HttpStatus.OK, pharmacy(ORG_A, PHARMACY, id, 4, sid,
                "accepted", "synthetic").getStatusCode());
        assertFalse(eventTypes(ORG_A, CLINICIAN, id).contains("dispensing_evidence"),
                "acceptance must not create dispensing evidence");

        ResponseEntity<String> dispensed = dispense(ORG_A, PHARMACY, id, 5, sid, "synthetic evidence");
        assertEquals(HttpStatus.OK, dispensed.getStatusCode(), dispensed.getBody());
        assertEquals("dispensed", field(dispensed, "/state"));
        assertEquals("6", field(dispensed, "/version"));

        JsonNode events = json(get(ORG_A, CLINICIAN, id)).at("/events");
        assertEquals(List.of("attested", "submitted", "delivery_confirmed", "pharmacy_accepted",
                "dispensing_evidence"), eventTypes(events),
                "dispensing evidence is appended after, and separately from, acceptance");
        assertEquals(5, eventCount(ORG_A, CLINICIAN, id));
        assertEquals(sid, events.at("/4/submissionId").asText());
        assertEquals("dispensed", events.at("/4/outcome").asText());
        assertEquals(1, countEventsOfType(ORG_A, id, "pharmacy_accepted"));
        assertEquals(1, countEventsOfType(ORG_A, id, "dispensing_evidence"));
    }

    /**
     * A clinician cannot record a pharmacy fact, so the wrong role is refused
     * before any event is appended to the trail.
     */
    @Test
    void clinicianCannotRecordAPharmacyResponse() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String sid = field(transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted"), "/submissionId");
        assertEquals(HttpStatus.OK, confirm(ORG_A, CLINICIAN, id, 3, sid).getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = pharmacy(ORG_A, CLINICIAN, id, 4, sid, "accepted", "wrong role");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a denied actor appends no event");
        assertEquals("delivery_confirmed", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    /**
     * A pharmacy actor cannot open a trail by attesting or transmitting, which
     * are clinician duties in the bounded action matrix.
     */
    @Test
    void pharmacyStaffCannotSubmitOrAttest() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);

        ResponseEntity<String> attested = post(ORG_A, PHARMACY, id + "/attest", 1,
                "{\"note\":\"wrong role\"}");
        assertEquals(HttpStatus.FORBIDDEN, attested.getStatusCode(), attested.getBody());
        assertEquals("FORBIDDEN", code(attested));
        ResponseEntity<String> transmitted = transmit(ORG_A, PHARMACY, id, 1, "E_PRESCRIBE", "submitted");
        assertEquals(HttpStatus.FORBIDDEN, transmitted.getStatusCode(), transmitted.getBody());
        assertEquals("FORBIDDEN", code(transmitted));
        assertEquals(0, eventCount(ORG_A, CLINICIAN, id), "a denied actor appends no event");
    }

    /**
     * A peer tenant cannot read or drive another tenant's trail: forced RLS
     * conceals the foreign row as a 404.
     */
    @Test
    void peerTenantCannotReadOrDriveAnotherTenantsTrail() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        assertEquals(HttpStatus.OK, post(ORG_A, CLINICIAN, id + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}").getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> read = get(ORG_B, PEER_CLINICIAN, id);
        assertEquals(HttpStatus.NOT_FOUND, read.getStatusCode(), read.getBody());
        assertEquals("NOT_FOUND", code(read));
        ResponseEntity<String> driven = post(ORG_B, PEER_CLINICIAN, id + "/attest", 2,
                "{\"note\":\"cross tenant\"}");
        assertEquals(HttpStatus.NOT_FOUND, driven.getStatusCode(), driven.getBody());
        assertEquals("NOT_FOUND", code(driven));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "the peer appends no event");
    }

    /**
     * A version one behind cannot append a fact; the optimistic lock fails
     * closed in the tenant-guarded transaction.
     */
    @Test
    void staleVersionCannotRecordAnEvent() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        assertEquals(HttpStatus.OK, post(ORG_A, CLINICIAN, id + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}").getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> stale = transmit(ORG_A, CLINICIAN, id, 1, "E_PRESCRIBE", "submitted");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", code(stale));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a stale version appends no event");
    }

    /**
     * Delivery cannot be confirmed before a submission exists, so no delivery
     * fact can be created out of order.
     */
    @Test
    void confirmationBeforeSubmissionIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        assertEquals(HttpStatus.OK, post(ORG_A, CLINICIAN, id + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}").getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = confirm(ORG_A, CLINICIAN, id, 2, UUID.randomUUID().toString());
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a premature confirmation appends no event");
        assertEquals("attested", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    /**
     * Dispensing evidence cannot be recorded before the pharmacy accepts, so
     * the two facts can never be merged into one early status change.
     */
    @Test
    void dispensingBeforeAcceptanceIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String sid = field(transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted"), "/submissionId");
        assertEquals(HttpStatus.OK, confirm(ORG_A, CLINICIAN, id, 3, sid).getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = dispense(ORG_A, PHARMACY, id, 4, sid, "too early");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "premature dispensing appends no event");
        assertEquals("delivery_confirmed", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    /**
     * A draft has never been transmitted, so it cannot carry a renewal fact.
     */
    @Test
    void renewingADraftIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);

        ResponseEntity<String> refused = post(ORG_A, CLINICIAN, id + "/renew", 1,
                "{\"note\":\"synthetic renewal\"}");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertEquals(0, eventCount(ORG_A, CLINICIAN, id), "a draft renewal appends no event");
        assertEquals("draft", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    /**
     * A terminal prescription cannot be cancelled, so a cancellation fact can
     * never overwrite a recorded rejection.
     */
    @Test
    void cancellingATerminalPrescriptionIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String sid = field(transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted"), "/submissionId");
        confirm(ORG_A, CLINICIAN, id, 3, sid);
        assertEquals(HttpStatus.OK, pharmacy(ORG_A, PHARMACY, id, 4, sid,
                "rejected", "synthetic").getStatusCode());
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = post(ORG_A, CLINICIAN, id + "/cancel", 5,
                "{\"reason\":\"too late\"}");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a terminal cancellation appends no event");
        assertEquals("rejected", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    /**
     * A pharmacy response needs a delivered prescription, not merely a
     * submitted one. The pharmacy role passes authorization so the state
     * precondition, not a role denial, is under test.
     */
    @Test
    void pharmacyResponseFromSubmittedStateIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String sid = field(transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted"), "/submissionId");
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = pharmacy(ORG_A, PHARMACY, id, 3, sid, "accepted", "too early");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a forbidden state appends no event");
    }

    /**
     * A fact naming a submission id other than this prescription's is refused,
     * so one trail cannot be attributed to a different downstream send.
     */
    @Test
    void wrongSubmissionIdIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);
        post(ORG_A, CLINICIAN, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        transmit(ORG_A, CLINICIAN, id, 2, "E_PRESCRIBE", "submitted");
        int before = eventCount(ORG_A, CLINICIAN, id);

        ResponseEntity<String> refused = confirm(ORG_A, CLINICIAN, id, 3, UUID.randomUUID().toString());
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("SUBMISSION_MISMATCH", code(refused));
        assertEquals(before, eventCount(ORG_A, CLINICIAN, id), "a mismatched submission appends no event");
    }

    /**
     * An irreversible command without an {@code If-Match} precondition is
     * refused at the HTTP boundary before any event can be appended.
     */
    @Test
    void missingIfMatchIsRefused() {
        String id = newId();
        create(ORG_A, CLINICIAN, id, CLINICIAN);

        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/prescriptions/" + id + "/attest",
                "{\"note\":\"missing version\"}", CLINICIAN, ORG_A);
        assertEquals(428, refused.getStatusCode().value(), refused.getBody());
        assertEquals("PRECONDITION_REQUIRED", code(refused));
        assertEquals(0, eventCount(ORG_A, CLINICIAN, id), "a missing precondition appends no event");
        assertEquals("draft", field(get(ORG_A, CLINICIAN, id), "/prescription/state"));
    }

    // ---------- helpers ----------

    private ResponseEntity<String> create(String org, String subject, String id, String prescriber) {
        String body = "{\"prescriptionId\":\"" + id + "\",\"patientId\":\"" + patientId(org)
                + "\",\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + prescriber
                + "\",\"destinationPharmacy\":\"pharmacy-x\"}";
        return send(HttpMethod.POST, "/api/v1/prescriptions", body, subject, org);
    }

    private ResponseEntity<String> transmit(String org, String subject, String id, int version,
                                            String channel, String outcome) {
        return post(org, subject, id + "/transmit", version,
                "{\"channel\":\"" + channel + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private ResponseEntity<String> confirm(String org, String subject, String id, int version, String sid) {
        return post(org, subject, id + "/confirm-delivery", version,
                "{\"submissionId\":\"" + sid + "\"}");
    }

    private ResponseEntity<String> pharmacy(String org, String subject, String id, int version, String sid,
                                            String outcome, String note) {
        return post(org, subject, id + "/pharmacy-response", version,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"" + outcome
                        + "\",\"note\":\"" + note + "\"}");
    }

    private ResponseEntity<String> dispense(String org, String subject, String id, int version, String sid,
                                            String note) {
        return post(org, subject, id + "/dispensing", version,
                "{\"submissionId\":\"" + sid + "\",\"note\":\"" + note + "\"}");
    }

    private ResponseEntity<String> post(String org, String subject, String path, int version, String body) {
        return send(HttpMethod.POST, "/api/v1/prescriptions/" + path, body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/prescriptions/" + id, null, subject, org);
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String field(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static String code(ResponseEntity<String> response) {
        JsonNode body = json(response);
        JsonNode code = body.has("code") ? body.get("code") : body.at("/error/code");
        return code.isMissingNode() ? "" : code.asText();
    }

    private List<String> eventTypes(String org, String subject, String id) {
        return eventTypes(json(get(org, subject, id)).at("/events"));
    }

    private static List<String> eventTypes(JsonNode events) {
        List<String> types = new ArrayList<>();
        for (JsonNode event : events) {
            types.add(event.at("/eventType").asText());
        }
        return types;
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int eventCount(String org, String subject, String prescriptionId) {
        return count("SELECT COUNT(*) FROM public.rx02_event WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)", org, subject);
    }

    private static int countEventsOfType(String org, String prescriptionId, String type) {
        return count("SELECT COUNT(*) FROM public.rx02_event WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid) AND event_type = '" + type + "'",
                org, "clinician-" + suffix(org));
    }

    private static int countSubmissionRows(String org, String prescriptionId) {
        return count("SELECT COUNT(*) FROM public.rx02_submission WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)", org, "clinician-" + suffix(org));
    }

    private static int count(String sql, String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String bootstrap = "bootstrap-" + suffix(org);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPolicy(Statement statement, String org, String actor, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }
}
