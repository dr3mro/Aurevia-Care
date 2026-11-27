package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

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

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S-G4-04 acceptance (RX-02) on a real PostgreSQL instance.
 *
 * <p>Four synthetic organizations share local subject and prescription
 * identifiers so tenant isolation is observable. All fixtures and narratives
 * are synthetic; no patient data or credentials are used. This suite is tagged
 * {@code integration}: the swarm fast gate skips it and the integrator runs it
 * with a real PostgreSQL service.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_prescription_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class RxTransmissionFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String ORG_D = "44444444-4444-4444-4444-444444444444";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_prescription_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Initialize the REST client against the random local port.
     *
     * @param port assigned server port
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Recreate the database, migrate it and seed four synthetic tenants.
     *
     * @throws Exception on database setup failure
     */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_prescription_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_prescription_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D}) {
                String suffix = suffix(org);
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "other-" + suffix, "clinician");
                seedMember(statement, org, "pharmacy-" + suffix, "pharmacy_staff");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "auditor-" + suffix, "privacy_auditor");
            }
            seedMember(statement, ORG_A, "unentitled-1111", "clinician");
            seedMember(statement, ORG_A, "revocable-1111", "clinician");
            // A: both channels; B: no policies; C: entitlement but no channel policy;
            // D: only the printable channel is approved.
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.market", "US");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement.clinician-1111", "enabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement.unentitled-1111", "disabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement.revocable-1111", "enabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
            seedPolicy(statement, ORG_C, "admin-3333", "rx02.market", "US");
            seedPolicy(statement, ORG_C, "admin-3333", "rx02.entitlement.clinician-3333", "enabled");
            seedPolicy(statement, ORG_D, "admin-4444", "rx02.market", "US");
            seedPolicy(statement, ORG_D, "admin-4444", "rx02.entitlement.clinician-4444", "enabled");
            seedPolicy(statement, ORG_D, "admin-4444", "rx02.approved_channels", "PRINTABLE_LOCAL");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_prescription_it WITH (FORCE)");
        }
    }

    @Test
    void electronicWorkflowTracksEveryFactSeparately() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String id = newId();

        ResponseEntity<String> created = createWithDestination(org, prescriber, id, prescriber);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("pharmacy-x", field(created, "/destinationPharmacy"));
        assertEquals("draft", field(created, "/state"));
        assertEquals("1", field(created, "/version"));

        ResponseEntity<String> attested = post(org, prescriber, id + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.OK, attested.getStatusCode(), attested.getBody());
        assertEquals("attested", field(attested, "/state"));
        assertEquals("2", field(attested, "/version"));

        ResponseEntity<String> transmitted = transmit(org, prescriber, id, 2, "E_PRESCRIBE", "submitted");
        assertEquals(HttpStatus.OK, transmitted.getStatusCode(), transmitted.getBody());
        assertEquals("submitted", field(transmitted, "/state"));
        assertEquals("E_PRESCRIBE", field(transmitted, "/channel"));
        assertEquals("3", field(transmitted, "/version"));
        String sid = field(transmitted, "/submissionId");
        assertNotNull(sid);

        ResponseEntity<String> confirmed = confirm(org, prescriber, id, 3, sid);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("delivery_confirmed", field(confirmed, "/state"));
        assertEquals("4", field(confirmed, "/version"));
        assertFalse(eventTypes(org, prescriber, id).contains("pharmacy_accepted"));

        ResponseEntity<String> accepted = pharmacy(org, "pharmacy-1111", id, 4, sid, "accepted", "synthetic");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", field(accepted, "/state"));
        assertEquals("5", field(accepted, "/version"));
        assertFalse(eventTypes(org, prescriber, id).contains("dispensing_evidence"),
                "acceptance must not create dispensing evidence");

        ResponseEntity<String> dispensed = dispense(org, "pharmacy-1111", id, 5, sid, "synthetic evidence");
        assertEquals(HttpStatus.OK, dispensed.getStatusCode(), dispensed.getBody());
        assertEquals("dispensed", field(dispensed, "/state"));
        assertEquals("6", field(dispensed, "/version"));

        ResponseEntity<String> renewed = post(org, prescriber, id + "/renew", 6,
                "{\"note\":\"synthetic renewal\"}");
        assertEquals(HttpStatus.OK, renewed.getStatusCode(), renewed.getBody());
        assertEquals("dispensed", field(renewed, "/state"), "renewal does not reset the original");
        assertEquals("7", field(renewed, "/version"));

        JsonNode events = json(get(org, prescriber, id)).at("/events");
        assertEquals(6, events.size());
        assertEquals("attested", events.at("/0/eventType").asText());
        assertEquals("submitted", events.at("/1/eventType").asText());
        assertEquals("delivery_confirmed", events.at("/2/eventType").asText());
        assertEquals("pharmacy_accepted", events.at("/3/eventType").asText());
        assertEquals("dispensing_evidence", events.at("/4/eventType").asText());
        assertEquals("renewal", events.at("/5/eventType").asText());
        assertEquals(sid, events.at("/5/submissionId").asText(), "renewal links to the original submission");
        assertEquals(1, countSubmissionRows(org, id));
    }

    @Test
    void ambiguousRetryIsBlockedUntilPartnerStatusIsVerifiedAbsent() {
        String org = ORG_A;
        String prescriber = "clinician-1111";

        String first = newId();
        create(org, prescriber, first, prescriber);
        post(org, prescriber, first + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> ambiguous = transmit(org, prescriber, first, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(ambiguous, "/submissionId");

        assertEquals("RETRY_NOT_ALLOWED", code(retry(org, prescriber, first, 3, sid)),
                "retry before verification must be refused");
        assertEquals(HttpStatus.OK, verify(org, prescriber, first, 3, sid, "present").getStatusCode());
        assertEquals("submitted", field(get(org, prescriber, first), "/prescription/state"));
        assertEquals("INVALID_TRANSITION", code(retry(org, prescriber, first, 4, sid)),
                "a partner that already holds the prescription must not be resent to");

        String second = newId();
        create(org, prescriber, second, prescriber);
        post(org, prescriber, second + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> ambiguous2 = transmit(org, prescriber, second, 2, "E_PRESCRIBE", "ambiguous");
        String sid2 = field(ambiguous2, "/submissionId");

        assertEquals(HttpStatus.OK, verify(org, prescriber, second, 3, sid2, "unknown").getStatusCode());
        assertEquals("ambiguous", field(get(org, prescriber, second), "/prescription/state"));
        assertEquals("RETRY_NOT_ALLOWED", code(retry(org, prescriber, second, 4, sid2)),
                "an unknown partner status must not authorize a retry");

        assertEquals(HttpStatus.OK, verify(org, prescriber, second, 4, sid2, "absent").getStatusCode());
        ResponseEntity<String> retried = retry(org, prescriber, second, 5, sid2);
        assertEquals(HttpStatus.OK, retried.getStatusCode(), retried.getBody());
        assertEquals("submitted", field(retried, "/state"));
        assertEquals(sid2, field(retried, "/submissionId"), "the retry reuses the stable submission id");
        assertEquals(1, countSubmissionRows(org, second), "one logical submission despite two send facts");
        assertEquals(2, countEventsOfType(org, second, "submitted"));
    }

    @Test
    void printablePrescriptionNeverBecomesElectronicallyDelivered() {
        String org = ORG_D;
        String prescriber = "clinician-4444";
        String id = newId();
        create(org, prescriber, id, prescriber);
        post(org, prescriber, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> printed = transmit(org, prescriber, id, 2, "PRINTABLE_LOCAL", "printed");
        assertEquals(HttpStatus.OK, printed.getStatusCode(), printed.getBody());
        assertEquals("printed", field(printed, "/state"));
        assertEquals(null, field(printed, "/submissionId"));
        assertEquals(0, countSubmissionRows(org, id));

        assertEquals("INVALID_TRANSITION",
                code(confirm(org, prescriber, id, 3, UUID.randomUUID().toString())));
        assertEquals("INVALID_TRANSITION", code(transmit(org, prescriber, id, 3, "E_PRESCRIBE", "submitted")));
        assertEquals("INVALID_TRANSITION",
                code(post(org, prescriber, id + "/cancel", 3, "{\"reason\":\"synthetic\"}")));
        assertTrue(eventTypes(org, prescriber, id).contains("printed"));
        assertFalse(eventTypes(org, prescriber, id).contains("delivery_confirmed"));

        ResponseEntity<String> renewed = post(org, prescriber, id + "/renew", 3,
                "{\"note\":\"synthetic renewal\"}");
        assertEquals(HttpStatus.OK, renewed.getStatusCode(), renewed.getBody());
        assertEquals("printed", field(renewed, "/state"));
        assertEquals("4", field(renewed, "/version"));
        assertTrue(eventTypes(org, prescriber, id).contains("renewal"));
        JsonNode renewalEvent = json(get(org, prescriber, id)).at("/events/2");
        assertEquals("renewal", renewalEvent.at("/eventType").asText());
        assertTrue(renewalEvent.at("/submissionId").isNull(),
                "a printable renewal has no submission to link to");
    }

    @Test
    void remainingCommandPreconditionsAreEnforced() {
        String org = ORG_A;
        String prescriber = "clinician-1111";

        String cancelId = newId();
        create(org, prescriber, cancelId, prescriber);
        assertEquals("FORBIDDEN", code(post(org, "other-1111", cancelId + "/cancel", 1,
                "{\"reason\":\"wrong actor\"}")));

        String verifyId = newId();
        create(org, prescriber, verifyId, prescriber);
        post(org, prescriber, verifyId + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> ambiguous = transmit(org, prescriber, verifyId, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(ambiguous, "/submissionId");
        assertEquals("FORBIDDEN", code(verify(org, "other-1111", verifyId, 3, sid, "absent")));
        assertEquals(HttpStatus.OK, verify(org, prescriber, verifyId, 3, sid, "present").getStatusCode());
        assertEquals("INVALID_TRANSITION", code(verify(org, prescriber, verifyId, 4, sid, "absent")),
                "a submitted prescription is no longer ambiguous");
    }

    @Test
    void retryAttemptsAreBoundedAndReuseOneSubmission() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String id = newId();
        create(org, prescriber, id, prescriber);
        post(org, prescriber, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> ambiguous = transmit(org, prescriber, id, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(ambiguous, "/submissionId");

        assertEquals(HttpStatus.OK, verify(org, prescriber, id, 3, sid, "absent").getStatusCode());
        assertEquals(HttpStatus.OK, retry(org, prescriber, id, 4, sid, "ambiguous").getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, prescriber, id, 5, sid, "absent").getStatusCode());
        assertEquals(HttpStatus.OK, retry(org, prescriber, id, 6, sid, "ambiguous").getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, prescriber, id, 7, sid, "absent").getStatusCode());
        assertEquals("RETRY_LIMIT", code(retry(org, prescriber, id, 8, sid, "submitted")));
        assertEquals(1, countSubmissionRows(org, id), "bounded retries never duplicate the send");
        assertEquals(3, countEventsOfType(org, id, "submitted"));
    }

    @Test
    void authorizationEntitlementAndChannelPoliciesFailClosed() {
        String id = newId();
        create(ORG_A, "clinician-1111", id, "clinician-1111");
        post(ORG_A, "clinician-1111", id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");

        assertEquals("FORBIDDEN", code(post(ORG_A, "other-1111", id + "/attest", 1,
                "{\"note\":\"wrong actor\"}")), "only the prescriber may attest");
        assertEquals("FORBIDDEN", code(post(ORG_A, "pharmacy-1111", id + "/attest", 1,
                "{\"note\":\"wrong role\"}")));
        assertEquals("FORBIDDEN", code(post(ORG_A, "ghost-1111", id + "/attest", 1,
                "{\"note\":\"no membership\"}")));
        assertEquals("STALE_VERSION", code(post(ORG_A, "clinician-1111", id + "/attest", 9,
                "{\"note\":\"stale\"}")));
        assertEquals("PRECONDITION_REQUIRED", code(send(HttpMethod.POST,
                "/api/v1/prescriptions/" + id + "/attest", "{\"note\":\"missing version\"}",
                "clinician-1111", ORG_A)));
        assertEquals("INVALID_ID", code(get(ORG_A, "clinician-1111", "not-a-uuid")));

        String noPolicy = newId();
        create(ORG_B, "clinician-2222", noPolicy, "clinician-2222");
        assertEquals("NO_MARKET_POLICY", code(post(ORG_B, "clinician-2222", noPolicy + "/attest", 1,
                "{\"note\":\"no policy\"}")));

        String disabled = newId();
        create(ORG_A, "unentitled-1111", disabled, "unentitled-1111");
        assertEquals("NO_ENTITLEMENT", code(post(ORG_A, "unentitled-1111", disabled + "/attest", 1,
                "{\"note\":\"disabled\"}")));

        // A clinician with no entitlement setting at all (the orElseThrow path,
        // distinct from a setting that is present but disabled) is also denied.
        String noSetting = newId();
        create(ORG_A, "other-1111", noSetting, "other-1111");
        assertEquals("NO_ENTITLEMENT", code(post(ORG_A, "other-1111", noSetting + "/attest", 1,
                "{\"note\":\"no entitlement setting\"}")));

        String noChannel = newId();
        create(ORG_C, "clinician-3333", noChannel, "clinician-3333");
        post(ORG_C, "clinician-3333", noChannel + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        assertEquals("NO_CHANNEL_POLICY", code(transmit(ORG_C, "clinician-3333", noChannel, 2,
                "E_PRESCRIBE", "submitted")));

        String unapproved = newId();
        create(ORG_D, "clinician-4444", unapproved, "clinician-4444");
        post(ORG_D, "clinician-4444", unapproved + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        assertEquals("UNAPPROVED_CHANNEL", code(transmit(ORG_D, "clinician-4444", unapproved, 2,
                "E_PRESCRIBE", "submitted")));
        assertEquals("INVALID_CHANNEL", code(transmit(ORG_D, "clinician-4444", unapproved, 2,
                "SNEAKER_NET", "submitted")));

        String revocable = newId();
        create(ORG_A, "revocable-1111", revocable, "revocable-1111");
        execute(ORG_A, "admin-1111", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = 'revocable-1111'");
        assertEquals("FORBIDDEN", code(get(ORG_A, "revocable-1111", revocable)),
                "revocation applies on the next protected request");
        assertEquals(HttpStatus.OK, get(ORG_A, "auditor-1111", id).getStatusCode(),
                "a privacy auditor can still read the audit trail");
    }

    @Test
    void tenantIsolationConcealsIdenticalLocalIdentifiers() {
        String sharedId = newId();
        ResponseEntity<String> inA = create(ORG_A, "clinician-1111", sharedId, "clinician-1111");
        ResponseEntity<String> inB = create(ORG_B, "clinician-2222", sharedId, "clinician-2222");
        assertEquals(HttpStatus.CREATED, inA.getStatusCode());
        assertEquals(HttpStatus.CREATED, inB.getStatusCode(), "RX-02 does not couple to the RX-01 table");
        assertEquals("clinician-1111", field(inA, "/prescriberSubject"));
        assertEquals("clinician-2222", field(inB, "/prescriberSubject"));

        // Both tenants own a row with the identical local id. Forced RLS must
        // resolve each read to that tenant's own row, never the peer's, so the
        // shared id is asserted as visible to both with its local author.
        ResponseEntity<String> aView = get(ORG_A, "clinician-1111", sharedId);
        ResponseEntity<String> bView = get(ORG_B, "clinician-2222", sharedId);
        assertEquals(HttpStatus.OK, aView.getStatusCode());
        assertEquals(HttpStatus.OK, bView.getStatusCode());
        assertEquals("clinician-1111", field(aView, "/prescription/prescriberSubject"));
        assertEquals("clinician-2222", field(bView, "/prescription/prescriberSubject"));

        // Concealment needs an id that exists only in the other tenant: when
        // both tenants own the shared id, B has its own row and correctly sees
        // it. A foreign-only id has no local row to mask the cross-tenant read.
        String aOnlyId = newId();
        create(ORG_A, "clinician-1111", aOnlyId, "clinician-1111");
        assertEquals(HttpStatus.NOT_FOUND, get(ORG_B, "clinician-2222", aOnlyId).getStatusCode(),
                "the other tenant's id is concealed");
        assertEquals("NOT_FOUND", code(post(ORG_B, "clinician-2222", aOnlyId + "/attest", 1,
                "{\"note\":\"cross tenant\"}")));
        // A concealed id still produces a durable refusal in the caller's own
        // tenant, attributed to the caller. Nothing is written in the owner's
        // tenant, so the audit row is never an existence oracle.
        assertEquals(1, auditOutcomeCount(ORG_B, aOnlyId, "RX_ATTEST", "DENIED"));
        assertEquals(actorId(ORG_B, "clinician-2222"),
                auditRow(ORG_B, aOnlyId, "RX_ATTEST", "DENIED").actorId());
        assertEquals(0, auditOutcomeCount(ORG_A, aOnlyId, "RX_ATTEST", "DENIED"),
                "no denial for the foreign id is recorded in the resource owner's tenant");
        assertEquals("NOT_FOUND", code(get(ORG_A, "clinician-1111", UUID.randomUUID().toString())));
        assertEquals("NOT_FOUND", code(create(ORG_A, "clinician-1111", newId(), "clinician-1111",
                UUID.randomUUID().toString())));
    }

    @Test
    void unsafeStateTransitionsAndSubmissionMismatchesAreRejected() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String id = newId();
        create(org, prescriber, id, prescriber);
        assertEquals("INVALID_TRANSITION", code(post(org, prescriber, id + "/renew", 1,
                "{\"note\":\"draft\"}")), "a draft cannot be renewed");

        post(org, prescriber, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        assertEquals("INVALID_TRANSITION", code(post(org, prescriber, id + "/attest", 2,
                "{\"note\":\"twice\"}")));
        assertEquals("INVALID_TRANSITION", code(pharmacy(org, "pharmacy-1111", id, 2,
                UUID.randomUUID().toString(), "accepted", "early")));

        ResponseEntity<String> transmitted = transmit(org, prescriber, id, 2, "E_PRESCRIBE", "submitted");
        String sid = field(transmitted, "/submissionId");
        assertEquals("SUBMISSION_MISMATCH", code(confirm(org, prescriber, id, 3, UUID.randomUUID().toString())));

        assertEquals(HttpStatus.OK, confirm(org, prescriber, id, 3, sid).getStatusCode());
        assertEquals("SUBMISSION_MISMATCH", code(pharmacy(org, "pharmacy-1111", id, 4,
                UUID.randomUUID().toString(), "accepted", "wrong id")));
        assertEquals("INVALID_TRANSITION", code(dispense(org, "pharmacy-1111", id, 4, sid, "too early")));
        assertEquals("INVALID_TRANSITION", code(transmit(org, prescriber, id, 4, "E_PRESCRIBE", "submitted")));

        assertEquals(HttpStatus.OK, pharmacy(org, "pharmacy-1111", id, 4, sid,
                "clarification", "synthetic").getStatusCode());
        assertEquals("clarification", field(get(org, prescriber, id), "/prescription/state"));
        assertEquals(HttpStatus.OK, pharmacy(org, "pharmacy-1111", id, 5, sid,
                "accepted", "resolved").getStatusCode());
        assertEquals("SUBMISSION_MISMATCH", code(dispense(org, "pharmacy-1111", id, 6,
                UUID.randomUUID().toString(), "wrong id")));
        assertEquals(HttpStatus.OK, dispense(org, "pharmacy-1111", id, 6, sid, "synthetic").getStatusCode());
        assertEquals("INVALID_TRANSITION", code(post(org, prescriber, id + "/cancel", 7,
                "{\"reason\":\"after dispensing\"}")));

        String rejected = newId();
        create(org, prescriber, rejected, prescriber);
        post(org, prescriber, rejected + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        String rejectedSid = field(transmit(org, prescriber, rejected, 2, "E_PRESCRIBE", "submitted"),
                "/submissionId");
        confirm(org, prescriber, rejected, 3, rejectedSid);
        assertEquals(HttpStatus.OK, pharmacy(org, "pharmacy-1111", rejected, 4, rejectedSid,
                "rejected", "synthetic").getStatusCode());
        assertEquals("rejected", field(get(org, prescriber, rejected), "/prescription/state"));
        assertEquals("INVALID_TRANSITION", code(post(org, prescriber, rejected + "/cancel", 5,
                "{\"reason\":\"terminal\"}")));

        String cancelled = newId();
        create(org, prescriber, cancelled, prescriber);
        assertEquals(HttpStatus.OK, post(org, prescriber, cancelled + "/cancel", 1,
                "{\"reason\":\"synthetic\"}").getStatusCode());
        assertEquals("cancelled", field(get(org, prescriber, cancelled), "/prescription/state"));
    }

    @Test
    void ambiguitySubmissionMismatchAndDatabaseGuardsHold() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String id = newId();
        create(org, prescriber, id, prescriber);
        post(org, prescriber, id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> transmitted = transmit(org, prescriber, id, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(transmitted, "/submissionId");

        assertEquals("SUBMISSION_MISMATCH",
                code(verify(org, prescriber, id, 3, UUID.randomUUID().toString(), "absent")));
        assertEquals("SUBMISSION_MISMATCH", code(retry(org, prescriber, id, 3, UUID.randomUUID().toString())));
        // Use the pharmacy role so role authorization passes and the state
        // precondition is the behavior under test: an ambiguous prescription
        // has not been delivered, so it cannot yet receive a pharmacy response.
        assertEquals("INVALID_TRANSITION", code(post(org, "pharmacy-1111", id + "/pharmacy-response", 3,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"accepted\"}")));

        // The database is the second line of defence: the send row is append-only
        // and the submission id stamped on the prescription is immutable.
        String update = "UPDATE public.prescription_transmission SET submission_id = gen_random_uuid()";
        assertThrowsSql(() -> execute(org, prescriber, update));
        String eventUpdate = "UPDATE public.prescription_event SET outcome = 'tampered'";
        assertThrowsSql(() -> execute(org, prescriber, eventUpdate));
        String replay = "INSERT INTO public.prescription_submission (organization_id, submission_id, "
                + "prescription_id, channel) VALUES (public.app_org(), CAST('" + sid
                + "' AS uuid), CAST('" + id + "' AS uuid), 'E_PRESCRIBE')";
        assertThrowsSql(() -> execute(org, prescriber, replay));

        // A fresh submission id for the same prescription is refused too: the
        // ledger holds exactly one downstream submission per RX-02 record (G4).
        String duplicate = "INSERT INTO public.prescription_submission (organization_id, submission_id, "
                + "prescription_id, channel) VALUES (public.app_org(), gen_random_uuid(), CAST('"
                + id + "' AS uuid), 'E_PRESCRIBE')";
        assertThrowsSql(() -> execute(org, prescriber, duplicate));
    }

    @Test
    void deniedCommandsLeaveDurableAuditRowsWithoutChangingState() {
        String org = ORG_A;
        String prescriber = "clinician-1111";

        String wrongOwnerId = newId();
        create(org, prescriber, wrongOwnerId, prescriber);
        ResponseEntity<String> wrongOwner = post(org, "other-1111", wrongOwnerId + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.FORBIDDEN, wrongOwner.getStatusCode(), wrongOwner.getBody());
        assertEquals("FORBIDDEN", code(wrongOwner));
        assertEquals(1, auditOutcomeCount(org, wrongOwnerId, "RX_ATTEST", "DENIED"),
                "a wrong-owner refusal commits exactly one durable DENIED row");
        AuditRow wrongOwnerRow = auditRow(org, wrongOwnerId, "RX_ATTEST", "DENIED");
        assertEquals(actorId(org, "other-1111"), wrongOwnerRow.actorId(),
                "the actor is the denied caller, not the prescription owner");
        assertEquals("RX_ATTEST", wrongOwnerRow.purpose());
        assertEquals("PRESCRIPTION_TRANSMISSION", wrongOwnerRow.scope());
        assertEquals("draft", field(get(org, prescriber, wrongOwnerId), "/prescription/state"));
        assertEquals("1", field(get(org, prescriber, wrongOwnerId), "/prescription/version"));

        String roleDeniedId = newId();
        create(org, prescriber, roleDeniedId, prescriber);
        ResponseEntity<String> roleDenied = post(org, "pharmacy-1111", roleDeniedId + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.FORBIDDEN, roleDenied.getStatusCode(), roleDenied.getBody());
        assertEquals(1, auditOutcomeCount(org, roleDeniedId, "RX_ATTEST", "DENIED"),
                "an authorize denial still writes exactly one row, never two");
        assertEquals(actorId(org, "pharmacy-1111"),
                auditRow(org, roleDeniedId, "RX_ATTEST", "DENIED").actorId());

        String staleId = newId();
        create(org, prescriber, staleId, prescriber);
        ResponseEntity<String> stale = post(org, prescriber, staleId + "/attest", 9,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(412, stale.getStatusCode().value());
        assertEquals("STALE_VERSION", code(stale));
        assertEquals(1, auditOutcomeCount(org, staleId, "RX_ATTEST", "DENIED"));
        assertEquals(actorId(org, prescriber), auditRow(org, staleId, "RX_ATTEST", "DENIED").actorId());
        assertEquals("draft", field(get(org, prescriber, staleId), "/prescription/state"));
        assertEquals("1", field(get(org, prescriber, staleId), "/prescription/version"));

        String twiceId = newId();
        create(org, prescriber, twiceId, prescriber);
        ResponseEntity<String> attested = post(org, prescriber, twiceId + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.OK, attested.getStatusCode(), attested.getBody());
        assertEquals(1, auditOutcomeCount(org, twiceId, "RX_ATTEST", "SUCCESS"));
        assertEquals(0, auditOutcomeCount(org, twiceId, "RX_ATTEST", "DENIED"),
                "an allowed attestation must not write a denial");
        AuditRow successRow = auditRow(org, twiceId, "RX_ATTEST", "SUCCESS");
        assertEquals(actorId(org, prescriber), successRow.actorId());
        assertEquals("RX_ATTEST", successRow.purpose());
        assertEquals("PRESCRIPTION_TRANSMISSION", successRow.scope());

        ResponseEntity<String> twice = post(org, prescriber, twiceId + "/attest", 2,
                "{\"note\":\"second attestation\"}");
        assertEquals(409, twice.getStatusCode().value());
        assertEquals("INVALID_TRANSITION", code(twice));
        assertEquals(1, auditOutcomeCount(org, twiceId, "RX_ATTEST", "DENIED"),
                "a refused second attestation adds exactly one denial");
        assertEquals("attested", field(get(org, prescriber, twiceId), "/prescription/state"));
        assertEquals("2", field(get(org, prescriber, twiceId), "/prescription/version"));

        String unentitledId = newId();
        create(org, "unentitled-1111", unentitledId, "unentitled-1111");
        ResponseEntity<String> unentitled = post(org, "unentitled-1111", unentitledId + "/attest", 1,
                "{\"note\":\"synthetic attestation\"}");
        assertEquals(422, unentitled.getStatusCode().value());
        assertEquals("NO_ENTITLEMENT", code(unentitled));
        assertEquals(1, auditOutcomeCount(org, unentitledId, "RX_ATTEST", "DENIED"),
                "a domain-policy refusal is audited by the same catch");
        assertEquals(actorId(org, "unentitled-1111"),
                auditRow(org, unentitledId, "RX_ATTEST", "DENIED").actorId());

        String terminalId = newId();
        create(org, prescriber, terminalId, prescriber);
        post(org, prescriber, terminalId + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        ResponseEntity<String> transmitted = transmit(org, prescriber, terminalId, 2,
                "E_PRESCRIBE", "submitted");
        String sid = field(transmitted, "/submissionId");
        confirm(org, prescriber, terminalId, 3, sid);
        pharmacy(org, "pharmacy-1111", terminalId, 4, sid, "accepted", "synthetic");
        dispense(org, "pharmacy-1111", terminalId, 5, sid, "synthetic evidence");
        ResponseEntity<String> cancelled = post(org, prescriber, terminalId + "/cancel", 6,
                "{\"reason\":\"after dispensing\"}");
        assertEquals(409, cancelled.getStatusCode().value());
        assertEquals("INVALID_TRANSITION", code(cancelled));
        assertEquals(1, auditOutcomeCount(org, terminalId, "RX_CANCEL", "DENIED"),
                "a refused cancellation commits exactly one DENIED row");
        AuditRow cancelRow = auditRow(org, terminalId, "RX_CANCEL", "DENIED");
        assertEquals(actorId(org, prescriber), cancelRow.actorId());
        assertEquals("RX_CANCEL", cancelRow.purpose());
        assertEquals("PRESCRIPTION_TRANSMISSION", cancelRow.scope());
        assertEquals("dispensed", field(get(org, prescriber, terminalId), "/prescription/state"));
        assertEquals("6", field(get(org, prescriber, terminalId), "/prescription/version"));
        assertAuditChainOk(org);
    }

    // ---------- helpers ----------

    private ResponseEntity<String> create(String org, String subject, String id, String prescriber) {
        String body = "{\"prescriptionId\":\"" + id + "\",\"patientId\":\"" + patientId(org)
                + "\",\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + prescriber + "\"}";
        return send(HttpMethod.POST, "/api/v1/prescriptions", body, subject, org);
    }

    private ResponseEntity<String> createWithDestination(String org, String subject, String id,
                                                         String prescriber) {
        return create(org, subject, id, prescriber, patientId(org));
    }

    private ResponseEntity<String> create(String org, String subject, String id, String prescriber,
                                          String patient) {
        String body = "{\"prescriptionId\":\"" + id + "\",\"patientId\":\"" + patient
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

    private ResponseEntity<String> verify(String org, String subject, String id, int version, String sid,
                                          String status) {
        return post(org, subject, id + "/verify-partner-status", version,
                "{\"submissionId\":\"" + sid + "\",\"status\":\"" + status + "\"}");
    }

    private ResponseEntity<String> retry(String org, String subject, String id, int version, String sid) {
        return retry(org, subject, id, version, sid, "submitted");
    }

    private ResponseEntity<String> retry(String org, String subject, String id, int version, String sid,
                                         String outcome) {
        return post(org, subject, id + "/retry", version,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"" + outcome + "\"}");
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

    private java.util.List<String> eventTypes(String org, String subject, String id) {
        JsonNode events = json(get(org, subject, id)).at("/events");
        java.util.List<String> types = new java.util.ArrayList<>();
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
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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

    private static int countSubmissionRows(String org, String prescriptionId) {
        return count("SELECT COUNT(*) FROM public.prescription_submission WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)", org);
    }

    private static int countEventsOfType(String org, String prescriptionId, String type) {
        return count("SELECT COUNT(*) FROM public.prescription_event WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid) AND event_type = '" + type + "'", org);
    }

    private static int count(String sql, String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
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

    private static void assertThrowsSql(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalStateException exception) {
            return;
        }
        org.junit.jupiter.api.Assertions.fail("expected the database guard to reject the write");
    }

    private static void execute(String org, String subject, String sql) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int auditOutcomeCount(String org, String resourceId, String action, String outcome) {
        return count("SELECT COUNT(*) FROM public.audit_event WHERE resource_id = '" + resourceId
                + "' AND action = '" + action + "' AND outcome = '" + outcome + "'", org);
    }

    private static AuditRow auditRow(String org, String resourceId, String action, String outcome) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT actor_id, purpose, scope FROM public.audit_event "
                            + "WHERE resource_id = ? AND action = ? AND outcome = ?")) {
                statement.setString(1, resourceId);
                statement.setString(2, action);
                statement.setString(3, outcome);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return new AuditRow(result.getString(1), result.getString(2), result.getString(3));
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String actorId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity WHERE subject = ?")) {
                statement.setString(1, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertAuditChainOk(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public.audit_verify_chain('" + org + "') WHERE NOT ok")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "every audit row keeps the append-only hash chain");
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record AuditRow(String actorId, String purpose, String scope) {
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
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix(org) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
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
