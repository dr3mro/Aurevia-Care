package com.aurevia.journey.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.journey.JourneyContract.ActorType;
import com.aurevia.journey.JourneyContract.JourneyEvent;
import com.aurevia.journey.JourneyContract.JourneyItemType;
import com.aurevia.journey.JourneyContract.JourneyStep;
import com.aurevia.journey.JourneyContract.JourneyView;
import com.aurevia.journey.JourneyContract.Viewer;

import tools.jackson.databind.JsonNode;

/**
 * RX-02 and XCH-03 whole prescription loop with two tenants running side by side.
 *
 * <p>Each step is driven through a normal HTTP entry point against a real
 * PostgreSQL database: prescribe (create and attest), route (transmit and
 * delivery confirmation), pharmacy accept, then dispensing evidence. After every
 * step the facts observed in the response are normalized into journey facts and
 * projected with the real {@code JourneyTimeline} read model. The canonical
 * mapping is prescribe to {@code PLACED}, route to {@code DELIVERED}, accept to
 * {@code ACCEPTED} and dispense to {@code RESULTED}; there is no separate
 * "dispensed" timeline step, so dispensing evidence completes {@code RESULTED}
 * (the same mapping the journey unit acceptance uses). The two tenants' fact
 * collections are mixed before projection so the tenant filter is exercised,
 * and each HTTP read is checked against the peer tenant.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_journey_rx_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class PrescriptionJourneyLoopTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_journey_rx_it";
    private static final String DATABASE = "orevia_journey_rx_it";
    private static final String SOURCE = "prescription";

    private LoopSupport.Client client;

    @BeforeAll
    static void prepare() throws Exception {
        LoopSupport.prepare(URL, DATABASE);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        LoopSupport.drop(DATABASE);
    }

    @BeforeEach
    void initClient(@LocalServerPort int port) {
        client = new LoopSupport.Client(port);
    }

    private static final class Tenant {
        final String org;
        final String suffix;
        final String prescriber;
        final String pharmacy;
        final String patient;
        final String itemId;
        final List<JourneyEvent> facts = new ArrayList<>();
        String submissionId;

        Tenant(String org, String suffix) {
            this.org = org;
            this.suffix = suffix;
            this.prescriber = "prescriber-" + suffix;
            this.pharmacy = "pharmacy-" + suffix;
            this.patient = LoopSupport.patientId(URL, org);
            this.itemId = LoopSupport.newId();
        }
    }

    private static Instant base(Tenant tenant) {
        return LoopSupport.ORG_A.equals(tenant.org) ? LoopSupport.ORG_A_BASE : LoopSupport.ORG_B_BASE;
    }

    @Test
    void prescribeRouteAcceptDispenseKeepsTwoTenantsIsolated() {
        Tenant a = new Tenant(LoopSupport.ORG_A, "1111");
        Tenant b = new Tenant(LoopSupport.ORG_B, "2222");

        prescribe(a, b);
        assertBothTimelines(a, b, Set.of(JourneyStep.PLACED), JourneyStep.DELIVERED);

        route(a, b);
        assertBothTimelines(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED), JourneyStep.ACCEPTED);

        accept(a, b);
        assertBothTimelines(a, b,
                Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED, JourneyStep.ACCEPTED),
                JourneyStep.COLLECTED_OR_SCHEDULED);
        for (Tenant tenant : List.of(a, b)) {
            List<String> eventTypes = eventTypes(tenant);
            assertTrue(eventTypes.contains("pharmacy_accepted"));
            assertFalse(eventTypes.contains("dispensing_evidence"),
                    "acceptance must not create dispensing evidence");
        }

        dispense(a, b);
        Set<JourneyStep> complete = Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED,
                JourneyStep.ACCEPTED, JourneyStep.RESULTED);
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = LoopSupport.TIMELINE.view(Viewer.CLINICIAN, tenant.org, tenant.itemId,
                    JourneyItemType.PRESCRIPTION, mixed(a, b));
            LoopSupport.assertSteps(view, complete, JourneyStep.COLLECTED_OR_SCHEDULED);
            assertEquals(SOURCE, view.steps().get(JourneyStep.RESULTED.ordinal()).source());
            assertEquals(ActorType.PHARMACY, view.steps().get(JourneyStep.RESULTED.ordinal()).actorType());
            assertTrue(eventTypes(tenant).contains("dispensing_evidence"));

            JourneyView patient = LoopSupport.TIMELINE.view(Viewer.PATIENT, tenant.org, tenant.itemId,
                    JourneyItemType.PRESCRIPTION, mixed(a, b));
            LoopSupport.assertSteps(patient, Set.of(JourneyStep.PLACED, JourneyStep.RESULTED),
                    JourneyStep.DELIVERED);
        }
        assertIsolated(a, b);
        assertHttpIsolation(a, b);
    }

    @Test
    void identicalLocalIdentifiersDoNotCrossTenants() {
        String sharedId = LoopSupport.newId();
        Tenant a = new Tenant(LoopSupport.ORG_A, "1111");
        Tenant b = new Tenant(LoopSupport.ORG_B, "2222");

        assertEquals(HttpStatus.CREATED.value(),
                create(a, sharedId).getStatusCode().value());
        assertEquals(HttpStatus.CREATED.value(),
                create(b, sharedId).getStatusCode().value());

        var ownA = client.send(HttpMethod.GET, "/api/v1/prescriptions/" + sharedId, null,
                a.prescriber, a.org);
        var ownB = client.send(HttpMethod.GET, "/api/v1/prescriptions/" + sharedId, null,
                b.prescriber, b.org);
        assertEquals(HttpStatus.OK, ownA.getStatusCode(), ownA.getBody());
        assertEquals(HttpStatus.OK, ownB.getStatusCode(), ownB.getBody());
        assertEquals(a.prescriber, client.field(ownA, "/prescription/prescriberSubject"));
        assertEquals(b.prescriber, client.field(ownB, "/prescription/prescriberSubject"));
        assertEquals(a.patient, client.field(ownA, "/prescription/patientId"));
        assertEquals(b.patient, client.field(ownB, "/prescription/patientId"));

        // A subject with no membership in the peer tenant cannot use the peer's context.
        assertEquals(HttpStatus.FORBIDDEN.value(),
                client.send(HttpMethod.GET, "/api/v1/prescriptions/" + sharedId, null,
                        a.prescriber, b.org).getStatusCode().value());
    }

    private void prescribe(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            var created = create(tenant, tenant.itemId);
            assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
            assertEquals("draft", client.field(created, "/state"));
            assertEquals("1", client.field(created, "/version"));

            var attested = post(tenant, tenant.itemId + "/attest", 1,
                    "{\"note\":\"synthetic attestation\"}", tenant.prescriber);
            assertEquals(HttpStatus.OK, attested.getStatusCode(), attested.getBody());
            assertEquals("attested", client.field(attested, "/state"));
            assertEquals("2", client.field(attested, "/version"));

            tenant.facts.add(LoopSupport.event("rx-" + tenant.suffix + "-placed", tenant.org,
                    tenant.itemId, JourneyItemType.PRESCRIPTION, SOURCE, JourneyStep.PLACED,
                    ActorType.PRESCRIBER, base(tenant), true));
        }
    }

    private void route(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            var transmitted = post(tenant, tenant.itemId + "/transmit", 2,
                    "{\"channel\":\"E_PRESCRIBE\",\"outcome\":\"submitted\"}", tenant.prescriber);
            assertEquals(HttpStatus.OK, transmitted.getStatusCode(), transmitted.getBody());
            assertEquals("submitted", client.field(transmitted, "/state"));
            assertEquals("E_PRESCRIBE", client.field(transmitted, "/channel"));
            assertEquals("3", client.field(transmitted, "/version"));
            tenant.submissionId = client.field(transmitted, "/submissionId");
            assertTrue(tenant.submissionId != null && !tenant.submissionId.isBlank());

            var confirmed = post(tenant, tenant.itemId + "/confirm-delivery", 3,
                    "{\"submissionId\":\"" + tenant.submissionId + "\"}", tenant.prescriber);
            assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
            assertEquals("delivery_confirmed", client.field(confirmed, "/state"));
            assertEquals("4", client.field(confirmed, "/version"));

            tenant.facts.add(LoopSupport.event("rx-" + tenant.suffix + "-delivered", tenant.org,
                    tenant.itemId, JourneyItemType.PRESCRIPTION, SOURCE, JourneyStep.DELIVERED,
                    ActorType.SYSTEM, base(tenant).plusSeconds(60), false));
        }
    }

    private void accept(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            var accepted = post(tenant, tenant.itemId + "/pharmacy-response", 4,
                    "{\"submissionId\":\"" + tenant.submissionId
                            + "\",\"outcome\":\"accepted\",\"note\":\"synthetic acceptance\"}",
                    tenant.pharmacy);
            assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
            assertEquals("accepted", client.field(accepted, "/state"));
            assertEquals("5", client.field(accepted, "/version"));

            tenant.facts.add(LoopSupport.event("rx-" + tenant.suffix + "-accepted", tenant.org,
                    tenant.itemId, JourneyItemType.PRESCRIPTION, SOURCE, JourneyStep.ACCEPTED,
                    ActorType.PHARMACY, base(tenant).plusSeconds(120), false));
        }
    }

    private void dispense(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            var dispensed = post(tenant, tenant.itemId + "/dispensing", 5,
                    "{\"submissionId\":\"" + tenant.submissionId
                            + "\",\"note\":\"synthetic dispensing evidence\"}",
                    tenant.pharmacy);
            assertEquals(HttpStatus.OK, dispensed.getStatusCode(), dispensed.getBody());
            assertEquals("dispensed", client.field(dispensed, "/state"));
            assertEquals("6", client.field(dispensed, "/version"));

            tenant.facts.add(LoopSupport.event("rx-" + tenant.suffix + "-dispensed", tenant.org,
                    tenant.itemId, JourneyItemType.PRESCRIPTION, SOURCE, JourneyStep.RESULTED,
                    ActorType.PHARMACY, base(tenant).plusSeconds(180), true));
        }
    }

    private void assertBothTimelines(Tenant a, Tenant b, Set<JourneyStep> complete,
                                     JourneyStep nextExpected) {
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = LoopSupport.TIMELINE.view(Viewer.CLINICIAN, tenant.org, tenant.itemId,
                    JourneyItemType.PRESCRIPTION, mixed(a, b));
            LoopSupport.assertSteps(view, complete, nextExpected);
        }
        assertIsolated(a, b);
        assertHttpIsolation(a, b);
    }

    private static List<JourneyEvent> mixed(Tenant a, Tenant b) {
        List<JourneyEvent> mixed = new ArrayList<>(a.facts);
        mixed.addAll(b.facts);
        return mixed;
    }

    private void assertIsolated(Tenant a, Tenant b) {
        LoopSupport.assertIsolated(mixed(a, b), a.itemId, b.itemId, JourneyItemType.PRESCRIPTION);
    }

    private void assertHttpIsolation(Tenant a, Tenant b) {
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/prescriptions/" + b.itemId, null, a.prescriber, a.org).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/prescriptions/" + a.itemId, null, b.prescriber, b.org).getStatusCode().value());
    }

    private List<String> eventTypes(Tenant tenant) {
        var response = client.send(HttpMethod.GET, "/api/v1/prescriptions/" + tenant.itemId, null,
                tenant.prescriber, tenant.org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        List<String> types = new ArrayList<>();
        for (JsonNode event : client.json(response).at("/events")) {
            types.add(event.at("/eventType").asText());
        }
        return types;
    }

    private ResponseEntity<String> create(Tenant tenant, String prescriptionId) {
        String body = "{\"prescriptionId\":\"" + prescriptionId + "\",\"patientId\":\"" + tenant.patient
                + "\",\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + tenant.prescriber
                + "\",\"destinationPharmacy\":\"pharmacy-x\"}";
        return client.send(HttpMethod.POST, "/api/v1/prescriptions", body, tenant.prescriber, tenant.org);
    }

    private ResponseEntity<String> post(Tenant tenant, String path, int version, String body,
                                        String subject) {
        return client.send(HttpMethod.POST, "/api/v1/prescriptions/" + path, body, subject, tenant.org,
                Map.of("If-Match", String.valueOf(version)));
    }
}
