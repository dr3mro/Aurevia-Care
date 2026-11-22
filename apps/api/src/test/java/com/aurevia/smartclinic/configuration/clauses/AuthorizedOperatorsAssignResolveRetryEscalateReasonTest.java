package com.aurevia.smartclinic.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.config.queues.OperationalQueueItem;
import com.aurevia.smartclinic.config.queues.OperationalWorkState;
import com.aurevia.smartclinic.config.queues.QueueAction;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ADM-02 clause 2 (API test): authorized operators assign, resolve, retry or
 * escalate with reason.
 *
 * <p>The active tests exercise the only built part of this clause: the
 * tenant-scoped, immutable {@link com.aurevia.smartclinic.config.queues.OperationalQueueItem}
 * kernel that retains a mandatory reason on every assign and resolve action.
 * They use synthetic identifiers and a fixed instant, set a synthetic tenant
 * context, and run each action through a mocked transaction template that
 * executes the real callback, matching the neighbouring
 * {@code scheduling.clauses.TransferHandoffRequiresActiveAssigneeTest} style.</p>
 *
 * <p>The authorized-operator HTTP command boundary, the retry and escalate
 * verbs, persistence and audit do not exist yet. The three tests for that
 * boundary are complete but disabled with a GAP reason, so they document the
 * missing behaviour and stay green without claiming it. A later task enables
 * them when the boundary is built.</p>
 */
class AuthorizedOperatorsAssignResolveRetryEscalateReasonTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "operator-a";
    private static final String ITEM = "33333333-3333-3333-3333-333333333333";
    private static final String QUEUE = "unsigned_encounters";
    private static final String OWNER = "nurse-1";
    private static final String REASON = "handover to day shift";
    private static final Instant CREATED = Instant.parse("2026-11-21T09:00:00Z");
    private static final Instant ACTION_AT = CREATED.plusSeconds(600);
    private static final String GAP = "GAP: no authorized operator command boundary, no retry or escalate "
            + "action, no HTTP route, no persistence or audit for ADM-02 queues";

    private TransactionTemplate transactions;

    /** Install the synthetic tenant context and the transaction helper for the clause. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "OPERATIONAL_QUEUES", "corr-adm-02-c02"));
        transactions = transactions();
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Transaction template that runs the guarded callback synchronously, as production does. */
    private TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Capture the ApiException raised by a refused action. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** A fresh synthetic open item in the unsigned-encounters queue. */
    private OperationalQueueItem openItem() {
        return OperationalQueueItem.open(ORG, ITEM, QUEUE, CREATED);
    }

    /** A refused command response from the not-yet-built operator boundary. */
    private record CommandResponse(int status, String body) {
    }

    /** Send the intended operator command; compiles today, fails until the route exists. */
    private CommandResponse command(HttpClient client, String url, String actor, String organization,
                                    String owner, String reason, int version)
            throws IOException, InterruptedException {
        String body = "{\"actor\":\"" + actor + "\",\"reason\":\"" + reason + "\""
                + (owner == null ? "" : ",\"owner\":\"" + owner + "\"")
                + ",\"version\":" + version + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Synthetic-Subject", actor)
                .header("X-Active-Organization", organization)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", "adm-02-" + actor + "-" + version)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new CommandResponse(response.statusCode(), response.body());
    }

    /** Re-read the item or its audit trail through the not-yet-built operator boundary. */
    private CommandResponse read(HttpClient client, String url, String actor, String organization)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-Synthetic-Subject", actor)
                .header("X-Active-Organization", organization)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new CommandResponse(response.statusCode(), response.body());
    }

    /** Assign records the owner and retains the reason on the action without mutating the source item. */
    @Test
    void assignRecordsOwnerAndReasonWithoutMutatingTheOriginal() {
        OperationalQueueItem open = openItem();

        OperationalQueueItem assigned = transactions.execute(
                status -> open.assign(SUBJECT, OWNER, REASON, ACTION_AT));

        assertEquals(OperationalWorkState.ASSIGNED, assigned.state());
        assertEquals(OWNER, assigned.owner());
        assertEquals(REASON, assigned.reason());
        assertEquals(1, assigned.actions().size());
        QueueAction action = assigned.actions().get(0);
        assertEquals("assign", action.type());
        assertEquals(SUBJECT, action.actor());
        assertEquals(REASON, action.reason());
        assertEquals(ACTION_AT, action.at());
        assertEquals(OperationalWorkState.OPEN, open.state());
        assertNull(open.owner());
        assertNull(open.reason());
        assertTrue(open.actions().isEmpty());
        assertEquals(ORG, assigned.organizationId());
        assertEquals(ITEM, assigned.id());
        assertEquals(QUEUE, assigned.queue());
        assertEquals(CREATED, assigned.createdAt());
    }

    /** A resolved item is terminal: neither resolve nor assign may act on it again. */
    @Test
    void resolveIsRefusedOnAnAlreadyResolvedItem() {
        Instant resolvedAt = ACTION_AT.plusSeconds(1);
        OperationalQueueItem resolved = openItem()
                .assign(SUBJECT, OWNER, REASON, ACTION_AT)
                .resolve(SUBJECT, "encounter signed", resolvedAt);

        ApiException secondResolve = reject(() -> transactions.execute(
                status -> resolved.resolve(SUBJECT, "close again", resolvedAt.plusSeconds(1))));
        ApiException assignAfterResolve = reject(() -> transactions.execute(
                status -> resolved.assign(SUBJECT, OWNER, "reopen", resolvedAt.plusSeconds(1))));

        assertEquals(409, secondResolve.status());
        assertEquals("INVALID_TRANSITION", secondResolve.code());
        assertEquals(409, assignAfterResolve.status());
        assertEquals("INVALID_TRANSITION", assignAfterResolve.code());
        assertEquals(OperationalWorkState.RESOLVED, resolved.state());
        assertEquals("encounter signed", resolved.reason());
        assertEquals(2, resolved.actions().size());
        assertEquals(REASON, resolved.actions().get(0).reason());
        assertEquals("encounter signed", resolved.actions().get(1).reason());
    }

    /** A reason is mandatory: both a null and a blank assign reason are refused. */
    @Test
    void assignIsRefusedWithoutAReason() {
        OperationalQueueItem open = openItem();

        ApiException missing = reject(() -> transactions.execute(
                status -> open.assign(SUBJECT, OWNER, null, ACTION_AT)));
        ApiException blank = reject(() -> transactions.execute(
                status -> open.assign(SUBJECT, OWNER, " \t ", ACTION_AT)));

        assertEquals(422, missing.status());
        assertEquals("REASON_REQUIRED", missing.code());
        assertEquals(422, blank.status());
        assertEquals("REASON_REQUIRED", blank.code());
        assertTrue(open.actions().isEmpty());
        assertEquals(OperationalWorkState.OPEN, open.state());
    }

    /**
     * Intended API proof, held back until the operator command boundary exists.
     * A later task enables this when an authorized operator can call the
     * assign, resolve, retry and escalate commands over HTTP and every command
     * requires a reason and commits the action with an audit row.
     */
    @Test
    @Disabled(GAP)
    void authorizedOperatorAssignsResolvesRetriesAndEscalatesWithReason() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:8080/api/v1/admin/operational-queues/" + ITEM;

        CommandResponse assigned = command(client, base + "/assign", SUBJECT, ORG, OWNER, REASON, 1);
        assertEquals(200, assigned.status());
        assertTrue(assigned.body().contains("\"state\":\"ASSIGNED\""));
        assertTrue(assigned.body().contains(REASON));

        // Replaying the same Idempotency-Key must not append a second assign action.
        CommandResponse replay = command(client, base + "/assign", SUBJECT, ORG, OWNER, REASON, 1);
        assertEquals(200, replay.status());
        assertEquals(assigned.body(), replay.body());

        // Resolution is terminal in the kernel, so retry and escalate come first.
        CommandResponse retried = command(client, base + "/retry", SUBJECT, ORG, null,
                "replay failed projection", 2);
        assertEquals(200, retried.status());
        assertTrue(retried.body().contains("\"type\":\"retry\""));
        assertTrue(retried.body().contains("replay failed projection"));

        CommandResponse escalated = command(client, base + "/escalate", SUBJECT, ORG, "supervisor-1",
                "overdue SLA", 3);
        assertEquals(200, escalated.status());
        assertTrue(escalated.body().contains("\"type\":\"escalate\""));
        assertTrue(escalated.body().contains("overdue SLA"));

        CommandResponse resolved = command(client, base + "/resolve", SUBJECT, ORG, null,
                "encounter signed", 4);
        assertEquals(200, resolved.status());
        assertTrue(resolved.body().contains("\"state\":\"RESOLVED\""));
        assertTrue(resolved.body().contains("encounter signed"));

        // Every command must retain its reason on a fresh read and in the audit trail.
        CommandResponse stored = read(client, base, SUBJECT, ORG);
        assertEquals(200, stored.status());
        assertTrue(stored.body().contains(REASON));
        assertTrue(stored.body().contains("encounter signed"));
        assertTrue(stored.body().contains("replay failed projection"));
        assertTrue(stored.body().contains("overdue SLA"));

        CommandResponse audit = read(client, base + "/audit", SUBJECT, ORG);
        assertEquals(200, audit.status());
        assertTrue(audit.body().contains(REASON));
        assertTrue(audit.body().contains("encounter signed"));
        assertTrue(audit.body().contains("replay failed projection"));
        assertTrue(audit.body().contains("overdue SLA"));
    }

    /**
     * Intended negative proof for the missing boundary: a caller from another
     * tenant or without the operator role must be refused before any action.
     */
    @Test
    @Disabled(GAP)
    void operatorFromAnotherTenantOrWithoutTheOperatorRoleIsRefused() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = "http://127.0.0.1:8080/api/v1/admin/operational-queues/" + ITEM + "/assign";

        CommandResponse otherTenant = command(client, url, "operator-b", OTHER_ORG, OWNER, REASON, 1);
        assertEquals(404, otherTenant.status());

        CommandResponse noRole = command(client, url, "reader-c", ORG, OWNER, REASON, 1);
        assertEquals(403, noRole.status());

        CommandResponse missingReason = command(client, url, SUBJECT, ORG, OWNER, "", 1);
        assertEquals(422, missingReason.status());
        assertTrue(missingReason.body().contains("REASON_REQUIRED"));
    }

    /** Intended negative proof for the missing boundary: a stale version must be refused. */
    @Test
    @Disabled(GAP)
    void staleVersionActionIsRefused() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = "http://127.0.0.1:8080/api/v1/admin/operational-queues/" + ITEM + "/resolve";

        CommandResponse stale = command(client, url, SUBJECT, ORG, null, "done", 0);

        assertEquals(409, stale.status());
        assertTrue(stale.body().contains("STALE_VERSION"));

        CommandResponse current = command(client, url, SUBJECT, ORG, null, "done", 1);
        assertEquals(200, current.status());
    }
}
