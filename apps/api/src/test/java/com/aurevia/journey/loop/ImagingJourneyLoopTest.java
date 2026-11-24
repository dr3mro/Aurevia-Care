package com.aurevia.journey.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * RAD-01 and XCH-03 imaging-order loop with two tenants running side by side.
 *
 * <p>The performing-site worklist receives, schedules, links the external study
 * and hands off the study; the reporting route publishes a signed interpretation,
 * adds an addendum and records the referrer's acknowledgment. Facts observed in
 * HTTP responses are projected with the real journey read model as an
 * {@code IMAGING_ORDER} timeline. The worklist and report kernels are in-memory
 * domain services, so this test proves the HTTP entry points, the tenant scoping
 * and the timeline projection but not durable storage of imaging facts; that
 * limitation is recorded in the task notes.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_journey_imaging_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class ImagingJourneyLoopTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_journey_imaging_it";
    private static final String DATABASE = "aurevia_journey_imaging_it";
    private static final String WORKLIST_SOURCE = "imaging";
    private static final String REPORT_SOURCE = "radiology";
    private static final String AT = "2026-11-21T11:00:00Z";

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
        final String site;
        final String tech;
        final String interpreter;
        final String referrer;
        final String patient;
        final String orderId;
        final String accession;
        final String studyUid;
        final List<JourneyEvent> facts = new ArrayList<>();

        Tenant(String org, String suffix) {
            this.org = org;
            this.suffix = suffix;
            this.site = "site-" + suffix;
            this.tech = "rad-tech-" + suffix;
            this.interpreter = "rad-interpreter-" + suffix;
            this.referrer = "clinician-" + suffix;
            this.patient = LoopSupport.patientId(URL, org);
            this.orderId = "img-" + suffix + "-" + java.util.UUID.randomUUID();
            this.accession = "ACC-RAD-" + suffix;
            this.studyUid = "1.2.840.113619.2.55.3.loop." + suffix;
        }
    }

    private static Instant base(Tenant tenant) {
        return LoopSupport.ORG_A.equals(tenant.org) ? LoopSupport.ORG_A_BASE : LoopSupport.ORG_B_BASE;
    }

    @Test
    void imagingOrderReachesSignedReportAndAcknowledgmentForTwoTenants() {
        Tenant a = new Tenant(LoopSupport.ORG_A, "1111");
        Tenant b = new Tenant(LoopSupport.ORG_B, "2222");

        receive(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED, JourneyStep.ACCEPTED),
                JourneyStep.COLLECTED_OR_SCHEDULED);

        scheduleAndLink(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED, JourneyStep.ACCEPTED,
                JourneyStep.COLLECTED_OR_SCHEDULED), JourneyStep.RESULTED);

        handOffAndPublish(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED, JourneyStep.ACCEPTED,
                JourneyStep.COLLECTED_OR_SCHEDULED, JourneyStep.RESULTED), JourneyStep.RELEASED);

        addendumAndAcknowledge(a, b);
        assertBoth(a, b, Set.of(JourneyStep.PLACED, JourneyStep.DELIVERED, JourneyStep.ACCEPTED,
                JourneyStep.COLLECTED_OR_SCHEDULED, JourneyStep.RESULTED,
                JourneyStep.ACKNOWLEDGED), JourneyStep.RELEASED);
        assertHttpIsolation(a, b);
    }

    private void receive(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String body = "{\"orderId\":\"" + tenant.orderId + "\",\"performingSiteId\":\""
                    + tenant.site + "\",\"patientId\":\"" + tenant.patient
                    + "\",\"orderCode\":\"CT-CHEST\",\"urgency\":\"ROUTINE\","
                    + "\"clinicalQuestion\":\"Synthetic imaging question\",\"referrer\":\""
                    + tenant.referrer + "\"}";
            ResponseEntity<String> created = client.send(HttpMethod.POST,
                    "/api/v1/radiology/worklist/orders", body, tenant.tech, tenant.org);
            assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
            assertEquals("RECEIVED", client.field(created, "/status"));

            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-placed", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, WORKLIST_SOURCE,
                    JourneyStep.PLACED, ActorType.CLINICIAN, base(tenant), true));
            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-delivered", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, WORKLIST_SOURCE,
                    JourneyStep.DELIVERED, ActorType.SYSTEM, base(tenant).plusSeconds(30), true));
            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-accepted", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, WORKLIST_SOURCE,
                    JourneyStep.ACCEPTED, ActorType.RADIOLOGY, base(tenant).plusSeconds(60), false));
        }
    }

    private void scheduleAndLink(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String schedule = "{\"expectedVersion\":1,\"performingSiteId\":\"" + tenant.site
                    + "\",\"scheduledAt\":\"" + AT + "\",\"modality\":\"CT\","
                    + "\"accession\":\"" + tenant.accession + "\",\"accessionIssuer\":\"RAD-A\"}";
            ResponseEntity<String> scheduled = client.send(HttpMethod.POST,
                    "/api/v1/radiology/worklist/orders/" + tenant.orderId + "/schedule",
                    schedule, tenant.tech, tenant.org);
            assertEquals(HttpStatus.OK, scheduled.getStatusCode(), scheduled.getBody());
            assertEquals(tenant.accession, client.field(scheduled, "/accession"));

            String link = "{\"expectedVersion\":2,\"performingSiteId\":\"" + tenant.site
                    + "\",\"studyInstanceUid\":\"" + tenant.studyUid + "\",\"patientId\":\""
                    + tenant.patient + "\",\"accession\":\"" + tenant.accession
                    + "\",\"workstationRef\":\"pacs-" + tenant.suffix + "\"}";
            ResponseEntity<String> linked = client.send(HttpMethod.POST,
                    "/api/v1/radiology/worklist/orders/" + tenant.orderId + "/studies",
                    link, tenant.tech, tenant.org);
            assertEquals(HttpStatus.OK, linked.getStatusCode(), linked.getBody());
            assertEquals("PERFORMED", client.field(linked, "/status"));

            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-scheduled", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, WORKLIST_SOURCE,
                    JourneyStep.COLLECTED_OR_SCHEDULED, ActorType.RADIOLOGY,
                    base(tenant).plusSeconds(120), true));
        }
    }

    private void handOffAndPublish(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String report = "{\"expectedVersion\":3,\"performingSiteId\":\"" + tenant.site + "\"}";
            ResponseEntity<String> handedOff = client.send(HttpMethod.POST,
                    "/api/v1/radiology/worklist/orders/" + tenant.orderId + "/report",
                    report, tenant.tech, tenant.org);
            assertEquals(HttpStatus.OK, handedOff.getStatusCode(), handedOff.getBody());
            assertEquals("REPORTED", client.field(handedOff, "/status"));

            String publish = "{\"orderId\":\"" + tenant.orderId + "\",\"referrer\":\""
                    + tenant.referrer + "\",\"patientId\":\"" + tenant.patient
                    + "\",\"studyInstanceUid\":\"" + tenant.studyUid
                    + "\",\"technique\":\"CT chest\",\"comparison\":\"none\","
                    + "\"findings\":\"synthetic findings\",\"impression\":\"synthetic impression\","
                    + "\"followUp\":\"none\",\"attestation\":\"signed synthetically\","
                    + "\"signedAt\":\"" + AT + "\",\"templateVersion\":\"1\"}";
            ResponseEntity<String> published = client.send(HttpMethod.POST,
                    "/api/v1/radiology/reports", publish, tenant.interpreter, tenant.org);
            assertEquals(HttpStatus.CREATED, published.getStatusCode(), published.getBody());
            assertEquals(tenant.referrer, client.field(published, "/referrer"));
            assertEquals("1", client.field(published, "/version"));

            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-resulted", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, REPORT_SOURCE,
                    JourneyStep.RESULTED, ActorType.RADIOLOGY, base(tenant).plusSeconds(180), true));
        }
    }

    private void addendumAndAcknowledge(Tenant a, Tenant b) {
        for (Tenant tenant : List.of(a, b)) {
            String addendum = "{\"expectedVersion\":1,\"patientId\":\"" + tenant.patient
                    + "\",\"studyInstanceUid\":\"" + tenant.studyUid
                    + "\",\"technique\":\"CT chest\",\"comparison\":\"none\","
                    + "\"findings\":\"additional synthetic finding\","
                    + "\"impression\":\"revised impression\",\"followUp\":\"none\","
                    + "\"attestation\":\"signed synthetically\",\"signedAt\":\"" + AT
                    + "\",\"templateVersion\":\"1\",\"reason\":\"synthetic addendum\"}";
            ResponseEntity<String> added = client.send(HttpMethod.POST,
                    "/api/v1/radiology/reports/" + tenant.orderId + "/addenda",
                    addendum, tenant.interpreter, tenant.org);
            assertEquals(HttpStatus.OK, added.getStatusCode(), added.getBody());

            ResponseEntity<String> acknowledged = client.send(HttpMethod.POST,
                    "/api/v1/radiology/reports/" + tenant.orderId + "/addenda/2/acknowledge",
                    null, tenant.referrer, tenant.org);
            assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
            assertTrue(acknowledged.getBody().contains("ACKNOWLEDGED"), acknowledged.getBody());

            tenant.facts.add(LoopSupport.event("rad-" + tenant.suffix + "-acknowledged", tenant.org,
                    tenant.orderId, JourneyItemType.IMAGING_ORDER, REPORT_SOURCE,
                    JourneyStep.ACKNOWLEDGED, ActorType.CLINICIAN,
                    base(tenant).plusSeconds(240), true));
        }
    }

    private void assertBoth(Tenant a, Tenant b, Set<JourneyStep> complete, JourneyStep nextExpected) {
        for (Tenant tenant : List.of(a, b)) {
            JourneyView view = LoopSupport.TIMELINE.view(Viewer.CLINICIAN, tenant.org, tenant.orderId,
                    JourneyItemType.IMAGING_ORDER, mixed(a, b));
            LoopSupport.assertSteps(view, complete, nextExpected);
        }
        LoopSupport.assertIsolated(mixed(a, b), a.orderId, b.orderId, JourneyItemType.IMAGING_ORDER);
    }

    private static List<JourneyEvent> mixed(Tenant a, Tenant b) {
        List<JourneyEvent> mixed = new ArrayList<>(a.facts);
        mixed.addAll(b.facts);
        return mixed;
    }

    private void assertHttpIsolation(Tenant a, Tenant b) {
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/radiology/worklist/orders/" + b.orderId + "?site=" + a.site,
                null, a.tech, a.org).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/radiology/worklist/orders/" + a.orderId + "?site=" + b.site,
                null, b.tech, b.org).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/radiology/reports/" + b.orderId, null, a.referrer, a.org).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND.value(), client.send(HttpMethod.GET,
                "/api/v1/radiology/reports/" + a.orderId, null, b.referrer, b.org).getStatusCode().value());
    }
}
