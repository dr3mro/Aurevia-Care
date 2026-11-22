package com.aurevia.smartclinic.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision;
import com.aurevia.smartclinic.clinical.notes.NoteState;
import com.aurevia.smartclinic.clinical.queues.EncounterNoteFact;
import com.aurevia.smartclinic.clinical.queues.UnsignedEncounterQueue;
import com.aurevia.smartclinic.config.queues.OperationalQueueItem;
import com.aurevia.smartclinic.config.queues.OperationalWorkState;
import com.aurevia.smartclinic.config.rosters.CoverageRosterFact;
import com.aurevia.smartclinic.config.rosters.MissingCoverageQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADM-02 clause 1 (API evidence): the seven operational queues for unsigned encounters,
 * unreconciled reports, unacknowledged critical findings, rejected exchanges, document
 * failures, missing coverage and revoked accounts.
 *
 * <p>The active tests prove the part that exists today: two tenant-scoped, read-derived
 * projections — {@code UnsignedEncounterQueue} and {@code MissingCoverageQueue} — return
 * open {@code OperationalQueueItem} values and refuse a signed encounter, another tenant's
 * fact and a fully staffed roster. Both are pure domain functions over caller-supplied
 * facts, so these are unit tests with no Spring context, no database and no HTTP server.</p>
 *
 * <p>The disabled test documents the uncovered part of the clause: there is no HTTP
 * aggregate over all seven catalog queues, no durable store, no operator allowlist or
 * next-action revocation and no audit-id enforcement. It is complete and compiles against
 * the current tree; a future wiring task supplies the running HTTP server, seeded two-org
 * operator fixtures and a real audit event, then enables it. Fixtures are synthetic — two
 * organizations with local-only ids, no patient data or credentials.</p>
 */
class ProvideOperationalQueuesUnsignedEncountersUnreconciledReportsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String T0 = "2026-11-21T09:00:00Z";
    private static final String T1 = "2026-11-21T10:00:00Z";
    private static final String T2 = "2026-11-21T11:00:00Z";
    private static final Instant CREATED = Instant.parse(T0);

    /** The exact seven-queue vocabulary enforced by the ADM-02 offline evaluator. */
    private static final List<String> CATALOG_QUEUES = List.of(
            "unsigned_encounters", "unreconciled_reports", "unacknowledged_critical_findings",
            "rejected_exchanges", "document_failures", "missing_coverage", "revoked_accounts");

    /** Placeholder until a wiring task binds a running server; the assertions are final. */
    private static final String BASE_URL = "http://localhost:8080";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An unsigned draft encounter yields one open item tagged with the oldest revision. */
    @Test
    void anUnsignedEncounterAppearsAsAnOpenOperationalQueueItem() {
        // Deliberately out of time order so the assertion proves "oldest wins", not input order.
        List<OperationalQueueItem> items = UnsignedEncounterQueue.query(ORG, List.of(
                fact(ORG, note("note-3", NoteState.DRAFT, "encounter-1", T2)),
                fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T1)),
                fact(ORG, note("note-2", NoteState.DRAFT, "encounter-1", T0))));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("encounter-1", item.id());
        assertEquals("unsigned_encounters", item.queue());
        assertEquals(Instant.parse(T0), item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertNull(item.owner());
        assertNull(item.reason());
        assertTrue(item.actions().isEmpty());
    }

    /** A signature anywhere in the encounter suppresses the unsigned-encounter item. */
    @Test
    void aSignedEncounterIsAbsentFromTheUnsignedEncounterQueue() {
        EncounterNoteFact draft = fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T0));

        // Positive control: the item exists before the signature, so the empty result
        // below is caused by the signature rather than by a malformed fixture.
        assertEquals(1, UnsignedEncounterQueue.query(ORG, List.of(draft)).size());

        List<EncounterNoteFact> afterSigning = List.of(draft,
                fact(ORG, note("note-2", NoteState.SIGNED, "encounter-1", T1)));
        assertTrue(UnsignedEncounterQueue.query(ORG, afterSigning).isEmpty());
    }

    /** A fact owned by another tenant never enters this tenant's queue. */
    @Test
    void anotherTenantsUnsignedEncounterIsNotReturned() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-2", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-3", NoteState.DRAFT, "other-encounter", T1)));

        List<OperationalQueueItem> orgItems = UnsignedEncounterQueue.query(ORG, facts);
        List<OperationalQueueItem> otherItems = UnsignedEncounterQueue.query(OTHER_ORG, facts);

        assertEquals(Set.of("same-encounter"), idSet(orgItems));
        assertEquals(Set.of("same-encounter", "other-encounter"), idSet(otherItems));
        assertTrue(orgItems.stream().allMatch(item -> ORG.equals(item.organizationId())));
        assertTrue(otherItems.stream().allMatch(item -> OTHER_ORG.equals(item.organizationId())));
    }

    /** A roster with no named owner yields one open missing-coverage work item. */
    @Test
    void aRosterWithNoOwnerAppearsAsAnOpenMissingCoverageItem() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "unit-1", null, "backup-1", CREATED)));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("unit-1", item.id());
        assertEquals("missing_coverage", item.queue());
        assertEquals(CREATED, item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertTrue(item.actions().isEmpty());
    }

    /** A roster with both a named owner and backup has no coverage gap to work. */
    @Test
    void aFullyStaffedRosterIsAbsentFromTheMissingCoverageQueue() {
        CoverageRosterFact staffed = roster(ORG, "staffed", "owner-1", "backup-1", CREATED);

        assertTrue(MissingCoverageQueue.query(ORG, List.of(staffed)).isEmpty());
        // Mixed-list control: the empty result is due to the staffed roster, not the query.
        assertEquals(List.of("gap"), ids(MissingCoverageQueue.query(ORG, List.of(staffed,
                roster(ORG, "gap", null, "backup-1", CREATED)))));
    }

    /**
     * Full clause evidence: all seven catalog queues must be reachable through one HTTP
     * aggregate under an operator allowlist, with tenant isolation and a corroborating
     * audit id on every operator action. Disabled because none of that exists yet: there
     * is no HTTP entry point, no durable store, five of the seven projections are unbuilt,
     * and no allowlist, next-action revocation or audit-id enforcement is wired. The future
     * enable task supplies a running server, seeded two-org operators and a real audit
     * event, then replaces the base-url placeholder with its injected port and adds the
     * integration tag. All field names match the existing {@code AuditEvent} contract.
     */
    @Test
    @Disabled("GAP: ADM-02 has no HTTP operational-queue entry point: only the in-process "
            + "UnsignedEncounterQueue / MissingCoverageQueue projections and the offline "
            + "ops_queues.py evaluator exist; there is no tenant-guarded ops-queues controller, "
            + "role policy, action handler or audit correlation, and five of the seven catalog "
            + "queues have no projection. A wiring task must add those, seed one item per tenant, "
            + "then enable this test with an injected port and the integration tag.")
    void allSevenOperationalQueuesAreReachableOverHttpWithRoleAndTenantGuards() throws Exception {
        RestClient client = RestClient.builder().baseUrl(BASE_URL).build();
        String queuesPath = "/api/v1/ops/queues";
        String unsignedA = "unsigned-org-a";
        String unsignedB = "unsigned-org-b";
        String actionAt = "2026-11-22T09:30:00Z";

        // An active, allowlisted operator sees every required queue as a tenant list.
        ResponseEntity<String> operatorView = request(client, HttpMethod.GET, queuesPath,
                null, "operator-a", ORG);
        assertEquals(HttpStatus.OK, operatorView.getStatusCode(), operatorView.getBody());
        JsonNode queues = json(operatorView);
        assertEquals(CATALOG_QUEUES.size(), queues.size(), operatorView.getBody());
        Set<String> allIds = new HashSet<>();
        for (String name : CATALOG_QUEUES) {
            assertTrue(queues.has(name), "catalog queue " + name + " must be reachable");
            assertTrue(queues.path(name).isArray(), "catalog queue " + name + " must carry work items");
            for (JsonNode item : queues.path(name)) {
                assertEquals(ORG, text(item, "organization_id"));
                allIds.add(item.path("id").asText());
            }
        }
        assertTrue(hasId(queues, unsignedA), "this tenant's unsigned item must be present");
        assertFalse(allIds.isEmpty());

        // A role outside the ADM-02 allowlist is refused the aggregate.
        ResponseEntity<String> wrongRole = request(client, HttpMethod.GET, queuesPath,
                null, "reception-a", ORG);
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());

        // An operator in another tenant never sees this tenant's work item.
        ResponseEntity<String> otherTenant = request(client, HttpMethod.GET, queuesPath,
                null, "operator-b", OTHER_ORG);
        assertEquals(HttpStatus.OK, otherTenant.getStatusCode(), otherTenant.getBody());
        JsonNode otherQueues = json(otherTenant);
        assertTrue(hasId(otherQueues, unsignedB));
        assertFalse(hasId(otherQueues, unsignedA),
                "another tenant must not see this tenant's unsigned-encounter item");

        // An operator action closes the item and cites an audit event for that same action.
        String action = "{\"type\":\"resolve\",\"actor\":\"operator-a\","
                + "\"reason\":\"signed elsewhere\",\"at\":\"" + actionAt + "\"}";
        ResponseEntity<String> acted = request(client, HttpMethod.POST,
                queuesPath + "/unsigned_encounters/items/" + unsignedA + "/actions",
                action, "operator-a", ORG);
        assertEquals(HttpStatus.OK, acted.getStatusCode(), acted.getBody());
        JsonNode actedBody = json(acted);
        assertEquals("resolved", text(actedBody, "state"));
        String auditId = text(actedBody, "auditId");
        assertTrue(auditId != null && !auditId.isBlank(), "the action must cite an audit id");

        // The cited event must corroborate actor, verb, item and instant (OPS-02 fields).
        ResponseEntity<String> auditPage = request(client, HttpMethod.GET,
                "/api/v1/audit-events?limit=100", null, "operator-a", ORG);
        assertEquals(HttpStatus.OK, auditPage.getStatusCode(), auditPage.getBody());
        JsonNode event = eventWithId(json(auditPage), auditId);
        assertEquals("RESOLVE", text(event, "action"));
        assertEquals(unsignedA, text(event, "resourceId"));
        assertFalse(text(event, "occurredAt") == null || text(event, "occurredAt").isBlank());
        assertFalse(text(event, "actorId") == null || text(event, "actorId").isBlank());
    }

    /**
     * Return the item ids in projection order.
     *
     * @param items projected work items
     * @return their local ids
     */
    private static List<String> ids(List<OperationalQueueItem> items) {
        return items.stream().map(OperationalQueueItem::id).toList();
    }

    /**
     * Return item ids as a set so tenant assertions do not depend on encounter order.
     *
     * @param items projected work items
     * @return their distinct local ids
     */
    private static Set<String> idSet(List<OperationalQueueItem> items) {
        return Set.copyOf(ids(items));
    }

    /**
     * Wrap a note revision with an explicit owning tenant.
     *
     * @param organizationId owning tenant
     * @param revision       synthetic note revision
     * @return the tenant-scoped fact
     */
    private static EncounterNoteFact fact(String organizationId, ClinicalNoteRevision revision) {
        return new EncounterNoteFact(organizationId, revision);
    }

    /**
     * Build a synthetic note revision; null signature fields model an unsigned draft.
     *
     * @param id          synthetic note id
     * @param state       lifecycle state under test
     * @param encounterId optional encounter context
     * @param createdAt   ingestion instant
     * @return the immutable synthetic revision
     */
    private static ClinicalNoteRevision note(String id, NoteState state, String encounterId, String createdAt) {
        return new ClinicalNoteRevision(id, 1, state, PATIENT, encounterId, "Synthetic title",
                "Synthetic content", "en", "synthetic-template", 1, "clinician-1", "clinician-1",
                createdAt, null, null, null, "synthetic-checksum", null, null, null, "[]", createdAt);
    }

    /**
     * Build a synthetic coverage roster fact.
     *
     * @param organizationId owning tenant
     * @param unitId         synthetic unit id
     * @param owner          primary owner, or {@code null} for a gap
     * @param backup         backup owner, or {@code null} for a gap
     * @param createdAt      instant the roster fact was recorded
     * @return the immutable synthetic fact
     */
    private static CoverageRosterFact roster(String organizationId, String unitId, String owner,
                                             String backup, Instant createdAt) {
        return new CoverageRosterFact(organizationId, unitId, owner, backup, createdAt);
    }

    /**
     * Send one HTTP request, keeping error bodies assertable.
     *
     * @param client       client bound to the test server
     * @param method       HTTP method
     * @param uri          request path
     * @param body         JSON request body, or {@code null}
     * @param subject      synthetic acting subject
     * @param organization active tenant header value
     * @return the response with its body
     */
    private static ResponseEntity<String> request(RestClient client, HttpMethod method, String uri,
                                                  String body, String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = client.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Parse a response body as JSON.
     *
     * @param response HTTP response
     * @return the parsed JSON tree
     * @throws IllegalStateException when the body is not valid JSON
     */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /**
     * Read a text field without confusing null and missing JSON nodes.
     *
     * @param node JSON object
     * @param key  field name
     * @return the text value, or {@code null}
     */
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    /**
     * Find an item across every catalog queue.
     *
     * @param queues all seven queue arrays
     * @param id     local item id
     * @return whether the id appears in any queue
     */
    private static boolean hasId(JsonNode queues, String id) {
        for (String name : CATALOG_QUEUES) {
            for (JsonNode item : queues.path(name)) {
                if (id.equals(item.path("id").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Locate the audit event cited by an operator action.
     *
     * @param page audit page returned by the existing audit endpoint
     * @param id   cited audit id
     * @return the matching event
     * @throws AssertionError when no event carries that id
     */
    private static JsonNode eventWithId(JsonNode page, String id) {
        for (JsonNode event : page.path("events")) {
            if (id.equals(event.path("id").asText())) {
                return event;
            }
        }
        throw new AssertionError("audit event " + id + " is missing from " + page);
    }
}
