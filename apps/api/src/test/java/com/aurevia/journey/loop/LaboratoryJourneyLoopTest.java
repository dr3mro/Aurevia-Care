package com.aurevia.journey.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.aurevia.journey.JourneyContract.StepStatus;
import com.aurevia.journey.JourneyContract.Viewer;

/**
 * LAB-01, LAB-02 and XCH-03 whole diagnostic-order loop with two tenants side by side.
 *
 * <p>Every step is driven through a real HTTP endpoint on PostgreSQL: order
 * (an authorized exchange order), collect (accession and matched specimen),
 * result (preliminary observations), verify by a second verifier, release under
 * an immediate patient policy, then a linked correction of report and release.
 * The observed facts are normalized to {@code JourneyEvent} records keyed by the
 * single order identifier and projected with the real {@code JourneyTimeline}.</p>
 *
 * <p>The projector chooses the earliest fact for a step, so verification and
 * correction do not move an already-complete step. Those steps are asserted at
 * the HTTP level (state {@code final}, state {@code corrected}, correction
 * history) while the timeline assertion explicitly proves the earliest fact is
 * retained. Tenant isolation is checked by mixing both tenants' facts before
 * every projection and by cross-tenant HTTP reads that must return 404.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_journey_lab_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class LaboratoryJourneyLoopTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_journey_lab_it";
    private static final String DATABASE = "orevia_journey_lab_it";
    private static final String ORDER_SOURCE = "order";
    private static final String LAB_SOURCE = "lab";
    private static final String RELEASE_SOURCE = "release";

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
        final String clinician;
        final String collector;
        final String verifier;
        final String reception;
        final String grant;
        final String destination;
        final String patient;
        final String accessionNo;
        final String itemId;
        final List<JourneyEvent> facts = new ArrayList<>();
        String specimenId;
        String releaseId;

        Tenant(String org, String suffix) {
            this.org = org;
            this.suffix = suffix;
            this.clinician = "clinician-" + suffix;
            this.collector = "collector-" + suffix;
            this.verifier = "verifier-" + suffix;
            this.reception = "reception-" + suffix;
            this.grant = LoopSupport.ORG_A.equals(org) ? LoopSupport.GRANT_A : LoopSupport.GRANT_B;
            this.destination = LoopSupport.ORG_A.equals(org) ? LoopSupport.ORG_B : LoopSupport.ORG_A;
            this.patient = LoopSupport.patientId(URL, org);
            this.accessionNo = "ACC-" + suffix;
            this.itemId = LoopSupport.newId();
        }
    }

    private static Instant base(Tenant tenant) {
        return LoopSupport.ORG_A.equals(tenant.org) ? LoopSupport.ORG_A_BASE : LoopSupport.ORG_B_BASE;
    }

    @Test
    void orderCollectResultVerifyReleaseCorrectKeepsTwoTenantsIsolated() {
        Tenant a = new Tenant(LoopSupport.ORG_A, "1111");
        Tenant b = new Tenant(LoopSupport.ORG_B, "2222");

        order(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED),
                JourneyStep.ACCEPTED);

        collect(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED,
                JourneyStep.ACCEPTED, JourneyStep.COLLECTED_OR_SCHEDULED), JourneyStep.RESULTED);

        result(a, b);
        Set<JourneyStep> throughResult = Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED,
                JourneyStep.ACCEPTED, JourneyStep.COLLECTED_OR_SCHEDULED, JourneyStep.RESULTED);
        assertBoth(a, b, throughResult, JourneyStep.RELEASED);

        verify(a, b);
        assertBoth(a, b, throughResult, JourneyStep.RELEASED);
        // Verification emits a later RESULTED fact, but earliest-wins keeps the preliminary one.
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = project(tenant, a, b);
            assertEquals(base(tenant).plusSeconds(240),
                    view.steps().get(JourneyStep.RESULTED.ordinal()).occurredAt());
            assertEquals("final", client.field(
                    client.send(HttpMethod.GET, "/api/v1/lab/results/" + tenant.specimenId, null,
                            tenant.verifier, tenant.org), "/state"));
        }

        release(a, b);
        Set<JourneyStep> throughRelease = new java.util.HashSet<>(throughResult);
        throughRelease.add(JourneyStep.RELEASED);
        assertBoth(a, b, throughRelease, JourneyStep.ACKNOWLEDGED);
        for (Tenant tenant : List.of(a, b)) {
            JourneyView patient = LoopSupport.TIMELINE.view(Viewer.PATIENT, tenant.org, tenant.itemId,
                    JourneyItemType.LAB_ORDER, mixed(a, b));
            assertEquals(StepStatus.COMPLETE,
                    patient.steps().get(JourneyStep.RELEASED.ordinal()).status());
            var releaseRead = client.send(HttpMethod.GET, "/api/v1/releases/" + tenant.releaseId,
                    null, tenant.clinician, tenant.org);
            assertEquals("IMMEDIATE", client.field(releaseRead, "/kind"));
            assertEquals("AUTHORIZED", client.field(releaseRead, "/state"));
        }

        correct(a, b);
        assertBoth(a, b, throughRelease, JourneyStep.ACKNOWLEDGED);
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = project(tenant, a, b);
            assertEquals(base(tenant).plusSeconds(360),
                    view.steps().get(JourneyStep.RELEASED.ordinal()).occurredAt(),
                    "a correction must not displace the earliest release fact");
            assertEquals("CORRECTED", client.field(
                    client.send(HttpMethod.GET, "/api/v1/releases/" + tenant.releaseId, null,
                            tenant.clinician, tenant.org), "/state"));
            String history = client.send(HttpMethod.GET,
                    "/api/v1/lab/releases/" + tenant.releaseId + "/history", null,
                    tenant.clinician, tenant.org).getBody();
            assertTrue(history.contains("RELEASE_CORRECT"), history);
        }
        assertHttpIsolation(a, b);
    }

    private void order(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String body = "{\"patientId\":\"" + tenant.patient + "\",\"encounterRef\":\"ENC-SYN-1\","
                    + "\"orderCode\":\"CBC\",\"priority\":\"routine\","
                    + "\"clinicalQuestion\":\"Synthetic CBC question\",\"destinationOrg\":\""
                    + tenant.destination + "\",\"purpose\":\"lab review\",\"authorizationId\":\""
                    + tenant.grant + "\",\"responsibleClinician\":\"" + tenant.clinician
                    + "\",\"workQueue\":\"lab-queue\",\"durationMinutes\":30}";
            ResponseEntity<String> created = client.send(HttpMethod.POST, "/api/v1/orders", body,
                    tenant.clinician, tenant.org);
            assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
            assertEquals("1", client.field(created, "/version"));
            assertEquals(tenant.clinician, client.field(created, "/sourceActor"));
            assertEquals(36, client.field(created, "/snapshotFingerprint").length());

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-placed", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, ORDER_SOURCE, JourneyStep.PLACED,
                    ActorType.CLINICIAN, base(tenant), true));
            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-delivered", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, ORDER_SOURCE, JourneyStep.DELIVERED,
                    ActorType.SYSTEM, base(tenant).plusSeconds(60), true));
        }
    }

    private void collect(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String accession = "{\"accessionNo\":\"" + tenant.accessionNo + "\","
                    + "\"accessionIssuer\":\"LAB-A\",\"patientId\":\"" + tenant.patient
                    + "\",\"sourceOrderRef\":\"" + tenant.itemId + "\"}";
            ResponseEntity<String> created = client.send(HttpMethod.POST, "/api/v1/lab/accessions",
                    accession, tenant.collector, tenant.org);
            assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
            tenant.specimenId = client.field(created, "/id");

            String collect = "{\"patientId\":\"" + tenant.patient + "\",\"accessionNo\":\""
                    + tenant.accessionNo + "\",\"accessionIssuer\":\"LAB-A\","
                    + "\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\","
                    + "\"collector\":\"Sam Collector\",\"collectedAt\":\"2026-11-21T09:00:00Z\","
                    + "\"quality\":\"acceptable\",\"expectedVersion\":1}";
            ResponseEntity<String> collected = client.send(HttpMethod.POST,
                    "/api/v1/lab/specimens/" + tenant.specimenId + "/collect", collect,
                    tenant.collector, tenant.org);
            assertEquals(HttpStatus.OK, collected.getStatusCode(), collected.getBody());
            assertEquals("collected", client.field(collected, "/state"));

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-accepted", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, LAB_SOURCE, JourneyStep.ACCEPTED,
                    ActorType.LABORATORY, base(tenant).plusSeconds(120), false));
            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-collected", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, LAB_SOURCE,
                    JourneyStep.COLLECTED_OR_SCHEDULED, ActorType.LABORATORY,
                    base(tenant).plusSeconds(180), true));
        }
    }

    private void result(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String body = "{\"patientId\":\"" + tenant.patient + "\",\"accessionNo\":\""
                    + tenant.accessionNo + "\",\"accessionIssuer\":\"LAB-A\","
                    + "\"expectedVersion\":2,\"owner\":\"" + tenant.clinician
                    + "\",\"queue\":\"result-queue\",\"dueAfter\":\"PT30M\","
                    + "\"escalation\":\"PHONE\",\"observations\":[{\"code\":\"GLU\","
                    + "\"kind\":\"numeric\",\"presence\":\"present\",\"value\":\"5.5\","
                    + "\"qualifier\":\"none\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                    + "\"instrument\":\"analyser-1\",\"referenceLow\":\"4.0\",\"referenceHigh\":\"6.0\"}]}";
            ResponseEntity<String> entered = client.send(HttpMethod.POST,
                    "/api/v1/lab/results/" + tenant.specimenId, body, tenant.collector, tenant.org);
            assertEquals(HttpStatus.OK, entered.getStatusCode(), entered.getBody());
            assertEquals("preliminary", client.field(entered, "/state"));

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-resulted", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, LAB_SOURCE, JourneyStep.RESULTED,
                    ActorType.LABORATORY, base(tenant).plusSeconds(240), true));
        }
    }

    private void verify(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String body = "{\"patientId\":\"" + tenant.patient + "\",\"accessionNo\":\""
                    + tenant.accessionNo + "\",\"accessionIssuer\":\"LAB-A\","
                    + "\"expectedVersion\":1,\"owner\":\"" + tenant.clinician
                    + "\",\"queue\":\"result-queue\",\"reason\":\"synthetic verification\"}";
            ResponseEntity<String> verified = client.send(HttpMethod.POST,
                    "/api/v1/lab/results/" + tenant.specimenId + "/verify", body,
                    tenant.verifier, tenant.org);
            assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
            assertEquals("final", client.field(verified, "/state"));

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-verified", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, LAB_SOURCE, JourneyStep.RESULTED,
                    ActorType.LABORATORY, base(tenant).plusSeconds(300), true));
        }
    }

    private void release(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String body = "{\"reportId\":\"" + tenant.specimenId + "\",\"revisionId\":\""
                    + tenant.specimenId + ":v2\",\"revisionVersion\":2,\"patientId\":\""
                    + tenant.patient + "\",\"policy\":{\"policyVersion\":1,"
                    + "\"patientKind\":\"IMMEDIATE\",\"patientDelay\":\"PT0S\"}}";
            ResponseEntity<String> released = client.send(HttpMethod.POST, "/api/v1/lab/releases",
                    body, tenant.clinician, tenant.org);
            assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
            assertEquals("true", client.field(released, "/patientVisibleNow"));
            tenant.releaseId = client.field(released, "/releaseId");

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-released", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, RELEASE_SOURCE, JourneyStep.RELEASED,
                    ActorType.CLINICIAN, base(tenant).plusSeconds(360), true));
        }
    }

    private void correct(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String correction = "{\"patientId\":\"" + tenant.patient + "\",\"accessionNo\":\""
                    + tenant.accessionNo + "\",\"accessionIssuer\":\"LAB-A\","
                    + "\"expectedVersion\":2,\"owner\":\"" + tenant.clinician
                    + "\",\"queue\":\"result-queue\",\"reason\":\"GLU value corrected\"}";
            ResponseEntity<String> correctedReport = client.send(HttpMethod.POST,
                    "/api/v1/lab/results/" + tenant.specimenId + "/correct", correction,
                    tenant.verifier, tenant.org);
            assertEquals(HttpStatus.OK, correctedReport.getStatusCode(), correctedReport.getBody());
            assertEquals("corrected", client.field(correctedReport, "/state"));

            String body = "{\"correctedRevisionId\":\"" + tenant.specimenId + ":v3\","
                    + "\"correctedRevisionVersion\":3,\"reason\":\"GLU value corrected\"}";
            ResponseEntity<String> correctedRelease = client.send(HttpMethod.POST,
                    "/api/v1/lab/releases/" + tenant.releaseId + "/correct", body,
                    tenant.clinician, tenant.org, Map.of("If-Match", "1"));
            assertEquals(HttpStatus.OK, correctedRelease.getStatusCode(), correctedRelease.getBody());

            tenant.facts.add(LoopSupport.event("lab-" + tenant.suffix + "-corrected", tenant.org,
                    tenant.itemId, JourneyItemType.LAB_ORDER, RELEASE_SOURCE, JourneyStep.RELEASED,
                    ActorType.CLINICIAN, base(tenant).plusSeconds(420), true));
        }
    }

    private JourneyView project(Tenant tenant, Tenant a, Tenant b) {
        return LoopSupport.TIMELINE.view(Viewer.CLINICIAN, tenant.org, tenant.itemId,
                JourneyItemType.LAB_ORDER, mixed(a, b));
    }

    private void assertBoth(Tenant a, Tenant b, Set<JourneyStep> complete, JourneyStep nextExpected) {
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = LoopSupport.TIMELINE.view(Viewer.CLINICIAN, tenant.org, tenant.itemId,
                    JourneyItemType.LAB_ORDER, mixed(a, b));
            LoopSupport.assertSteps(view, complete, nextExpected);
        }
        LoopSupport.assertIsolated(mixed(a, b), a.itemId, b.itemId, JourneyItemType.LAB_ORDER);
        assertHttpIsolation(a, b);
    }

    private static List<JourneyEvent> mixed(Tenant a, Tenant b) {
        List<JourneyEvent> mixed = new ArrayList<>(a.facts);
        mixed.addAll(b.facts);
        return mixed;
    }

    private void assertHttpIsolation(Tenant a, Tenant b) {
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/orders/" + b.itemId, null, a.clinician, a.org).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/orders/" + a.itemId, null, b.clinician, b.org).getStatusCode().value());
        if (a.specimenId != null && b.specimenId != null) {
            assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                    "/api/v1/lab/results/" + b.specimenId, null, a.verifier, a.org).getStatusCode().value());
            assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                    "/api/v1/lab/results/" + a.specimenId, null, b.verifier, b.org).getStatusCode().value());
        }
        if (a.releaseId != null && b.releaseId != null) {
            assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                    "/api/v1/releases/" + b.releaseId, null, a.clinician, a.org).getStatusCode().value());
            assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                    "/api/v1/releases/" + a.releaseId, null, b.clinician, b.org).getStatusCode().value());
        }
    }
}
