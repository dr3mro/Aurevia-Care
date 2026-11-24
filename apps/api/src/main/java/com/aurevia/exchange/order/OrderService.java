package com.aurevia.exchange.order;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-01 order release commands.
 *
 * <p>One command runs inside one tenant-guarded transaction: membership and role
 * are re-derived, the sender's approved authorization is checked against the
 * payload, recipient, purpose and duration, the patient is resolved in the
 * sending tenant, and the order, immutable snapshot, disclosure ledger entry and
 * bounded destination work item commit together. The receiver gets only the
 * exchange projection; no sender chart access is granted by sharing an order.</p>
 *
 * <p>The authorization is scoped to an approved payload class (order code),
 * recipient, purpose and validity window. The exact released content is bound by
 * an immutable, versioned snapshot and its deterministic fingerprint, which the
 * disclosure record and the work item both carry.</p>
 */
@Service
public class OrderService {

    private static final String SCOPE = "EXCHANGE";
    private static final String ORDER_RESOURCE = "ORDER";
    private static final String WORK_RESOURCE = "WORK_ITEM";
    private static final String AUTHORIZATION_RESOURCE = "AUTHORIZATION";
    private static final String CREATE_ACTION = "ORDER_DISCLOSURE";
    private static final String READ_ACTION = "ORDER_READ";
    private static final String AUTHORIZE_ACTION = "AUTHORIZATION_CREATE";
    private static final String LIST_ACTION = "WORK_ITEM_LIST";
    private static final String VERIFY_ACTION = "WORK_ITEM_VERIFY";
    private static final String ORDERING_ROLE = "clinician";
    private static final String ADMIN_ROLE = "tenant_admin";
    private static final int MAX_DURATION_MINUTES = 43200;
    private static final int MAX_VALID_DAYS = 365;
    private static final Pattern CURSOR = Pattern.compile(
            "(.+):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    // The order's projected work-item state values from the XCH-01 migration and WorkItemView.
    private static final List<String> STATUSES = List.of("awaiting_local_patient", "ready");

    private final OrderRepository repository;
    private final AuditAppender audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded exchange repository
     * @param audit        transactional audit appender
     * @param transactions tenant-guarded transaction template
     * @param clock        injected clock used for validity windows
     */
    public OrderService(OrderRepository repository, AuditAppender audit,
                        TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Release an order to its approved destination.
     *
     * @param request validated command
     * @return the released order with its stamped identifiers
     * @throws ApiException on invalid input, denial or an unknown patient
     */
    public OrderView create(OrderRequest request) {
        String patientId = OrderInputs.uuid(request.patientId(), "patientId");
        String encounterRef = OrderInputs.text(request.encounterRef(), "encounterRef", 120);
        String orderCode = OrderInputs.text(request.orderCode(), "orderCode", 64);
        String priority = OrderInputs.priority(request.priority());
        String question = OrderInputs.text(request.clinicalQuestion(), "clinicalQuestion", 2000);
        String destination = OrderInputs.uuid(request.destinationOrg(), "destinationOrg");
        String purpose = OrderInputs.text(request.purpose(), "purpose", 120);
        String authorizationId = OrderInputs.uuid(request.authorizationId(), "authorizationId");
        String responsible = OrderInputs.text(request.responsibleClinician(), "responsibleClinician", 120);
        String workQueue = OrderInputs.text(request.workQueue(), "workQueue", 120);
        int duration = OrderInputs.bounded(request.durationMinutes(), "durationMinutes", 1,
                MAX_DURATION_MINUTES);

        AtomicReference<ApiException> failure = new AtomicReference<>();
        OrderView released = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, CREATE_ACTION, authorizationId, ORDERING_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            AuthorizationView grant = repository.authorization(authorizationId).orElse(null);
            // One instant for the window check and the ledger stamp: a live clock
            // advancing between two reads could otherwise record a disclosure
            // expiry past the authorization that was just validated (XCH-01).
            Instant now = clock.instant();
            ApiException grantFailure = checkGrant(grant, context, CREATE_ACTION, authorizationId,
                    orderCode, destination, purpose, duration, now);
            if (grantFailure != null) {
                failure.set(grantFailure);
                return null;
            }
            OrderRepository.PatientIdentity identity = repository.patientIdentity(patientId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "patient not found"));
            String id = UUID.randomUUID().toString();
            String snapshotId = UUID.randomUUID().toString();
            String disclosureId = UUID.randomUUID().toString();
            String workItemId = UUID.randomUUID().toString();
            String content = String.join(OrderInputs.SEPARATOR, "v1", orderCode, patientId,
                    encounterRef, priority, question, destination, purpose, responsible, workQueue,
                    String.valueOf(duration));
            String fingerprint = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
            OrderView order = new OrderView(id, 1, patientId, encounterRef, orderCode, priority,
                    question, destination, context.subject(), responsible, workQueue, authorizationId,
                    snapshotId, fingerprint, disclosureId, workItemId, now);
            repository.insertOrder(order);
            repository.appendSnapshot(snapshotId, id, 1, content, fingerprint);
            repository.appendDisclosure(disclosureId, id, destination, purpose, duration,
                    disclosureExpiry(now, duration), fingerprint);
            repository.insertWorkItem(workItemId, destination, authorizationId, id, patientId,
                    identity.mrn(), identity.issuer(), priority, question, workQueue, fingerprint);
            audit.append(context, SCOPE, CREATE_ACTION, ORDER_RESOURCE, SCOPE, id, "SUCCESS");
            return order;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return released;
    }

    /**
     * Record the sending organization's approval for a bounded exchange.
     *
     * @param request validated authorization command
     * @return the appended authorization
     * @throws ApiException on invalid input or a missing admin role
     */
    public AuthorizationView authorize(AuthorizationRequest request) {
        String orderCode = OrderInputs.text(request.orderCode(), "orderCode", 64);
        String destination = OrderInputs.uuid(request.destinationOrg(), "destinationOrg");
        String purpose = OrderInputs.text(request.purpose(), "purpose", 120);
        int maxDuration = OrderInputs.bounded(request.maxDurationMinutes(), "maxDurationMinutes", 1,
                MAX_DURATION_MINUTES);
        int validForDays = OrderInputs.bounded(request.validForDays(), "validForDays", 1, MAX_VALID_DAYS);

        AtomicReference<ApiException> failure = new AtomicReference<>();
        AuthorizationView created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, AUTHORIZE_ACTION, orderCode, ADMIN_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Instant now = clock.instant();
            AuthorizationView authorization = new AuthorizationView(UUID.randomUUID().toString(),
                    context.organizationId(), orderCode, destination, purpose, maxDuration, now,
                    now.plusSeconds(validForDays * 24L * 3600), true);
            repository.insertAuthorization(authorization);
            audit.append(context, SCOPE, AUTHORIZE_ACTION, AUTHORIZATION_RESOURCE, SCOPE,
                    authorization.id(), "SUCCESS");
            return authorization;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Read one released order of the active tenant.
     *
     * @param id opaque order identifier
     * @return the released order
     * @throws ApiException on invalid input, denial or concealment
     */
    public OrderView get(String id) {
        String orderId = OrderInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        OrderView found = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, READ_ACTION, orderId, ORDERING_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            OrderView order = repository.find(orderId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "order not found"));
            audit.append(context, SCOPE, READ_ACTION, ORDER_RESOURCE, SCOPE, orderId, "SUCCESS");
            return order;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return found;
    }

    /**
     * Read one cursor-paginated page of the active tenant's released orders.
     *
     * <p>The single-record read action is reused: a caller who may read one order
     * may list the same tenant's orders. Input is validated before any transaction
     * and the page read is audited inside the guarded transaction. One extra row is
     * fetched to decide whether a next page exists; its last returned row carries
     * the opaque cursor.</p>
     *
     * @param cursor opaque {@code <createdAt>:<id>} of the previous page's last row, or {@code null}
     * @param limit  page size, or {@code null} for the default of 50
     * @param status optional exact work-item state filter
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denial
     */
    public OrderPage list(String cursor, String limit, String status) {
        OrderCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        String parsedStatus = parseStatus(status);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        OrderPage page = transactions.execute(txStatus -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, READ_ACTION, "ALL", ORDERING_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<OrderView> rows = repository.query(
                    parsedCursor == null ? null : parsedCursor.createdAt(),
                    parsedCursor == null ? null : parsedCursor.id(), parsedStatus, parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<OrderView> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            String next = null;
            if (hasMore) {
                OrderView last = pageRows.get(pageRows.size() - 1);
                next = last.createdAt() + ":" + last.id();
            }
            audit.append(context, SCOPE, READ_ACTION, ORDER_RESOURCE, SCOPE, "ALL", "SUCCESS");
            return new OrderPage(pageRows, next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return page;
    }

    private OrderCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <createdAt>:<id>");
        }
        String canonical;
        try {
            canonical = Instant.parse(matcher.group(1)).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <createdAt>:<id>");
        }
        if (!cursor.equals(canonical + ":" + matcher.group(2))) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be canonical <createdAt>:<id>");
        }
        return new OrderCursor(canonical, matcher.group(2));
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    private String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (STATUSES.contains(status)) {
            return status;
        }
        throw new ApiException(422, "INVALID_STATUS", "status must be awaiting_local_patient or ready");
    }

    /** Parsed opaque cursor: the canonical creation instant and the immutable row id. */
    private record OrderCursor(String createdAt, String id) {
    }

    /**
     * List the bounded work items projected to the active receiving organization.
     *
     * @return the destination's exchange work items
     * @throws ApiException on denial
     */
    public List<WorkItemView> workItems() {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<WorkItemView> items = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, LIST_ACTION, "ALL", ORDERING_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<WorkItemView> found = repository.workItems();
            audit.append(context, SCOPE, LIST_ACTION, WORK_RESOURCE, SCOPE, "ALL", "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return items;
    }

    /**
     * Bind a locally verified receiver patient to a work item.
     *
     * @param id      work item identifier
     * @param request receiver identity check with attestation
     * @return the verified work item
     * @throws ApiException on invalid input, denial, concealment, an identity mismatch or a repeat
     */
    public WorkItemView verifyPatient(String id, VerifyPatientRequest request) {
        String workItemId = OrderInputs.uuid(id, "id");
        String patientId = OrderInputs.uuid(request.localPatientId(), "localPatientId");
        String mrn = OrderInputs.text(request.localMrn(), "localMrn", 64);
        String issuer = OrderInputs.text(request.mrnIssuer(), "mrnIssuer", 64);
        OrderInputs.text(request.attestation(), "attestation", 500);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        WorkItemView verified = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = deny(context, VERIFY_ACTION, workItemId, ORDERING_ROLE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            WorkItemView item = repository.workItem(workItemId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "work item not found"));
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "local patient not found");
            }
            if (!mrn.equals(item.patientMrn()) || !issuer.equals(item.mrnIssuer())
                    || !repository.localPatientMatches(patientId, mrn, issuer)) {
                throw new ApiException(403, "IDENTITY_MISMATCH",
                        "the local patient does not match the referred patient identity");
            }
            if (repository.verifyPatient(workItemId, patientId) == 0) {
                throw new ApiException(409, "ALREADY_VERIFIED", "the work item is already verified");
            }
            audit.append(context, SCOPE, VERIFY_ACTION, WORK_RESOURCE, SCOPE, workItemId, "SUCCESS");
            return repository.workItem(workItemId).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return verified;
    }

    private ApiException deny(TenantContext context, String action, String resourceId, String role) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, ORDER_RESOURCE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (!repository.activeRoles(context.subject()).contains(role)) {
            audit.append(context, action, action, ORDER_RESOURCE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing exchange permission");
        }
        return null;
    }

    /**
     * Compute the instant at which a released disclosure stops being valid.
     *
     * <p>Validation and the ledger stamp call this single helper so the value
     * checked against the authorization is structurally the value recorded.</p>
     *
     * @param now      the single release instant shared by validation and recording
     * @param duration authorized validity in minutes
     * @return the disclosure expiry instant
     */
    private static Instant disclosureExpiry(Instant now, int duration) {
        return now.plusSeconds(duration * 60L);
    }

    private ApiException checkGrant(AuthorizationView grant, TenantContext context, String action,
                                    String resourceId, String orderCode, String destination,
                                    String purpose, int duration, Instant now) {
        // The disclosure must stop being valid when the authorization that
        // permitted it stops applying; otherwise a still-effective grant could
        // authorize sharing past its own expiry (XCH-01 duration boundary).
        // The caller supplies the same instant used to stamp the disclosure, so
        // clock drift between validation and recording cannot widen the window.
        Instant disclosureExpiresAt = disclosureExpiry(now, duration);
        if (grant == null || !grant.active() || grant.effectiveFrom().isAfter(now)
                || (grant.expiresAt() != null && !grant.expiresAt().isAfter(now))
                || (grant.expiresAt() != null && disclosureExpiresAt.isAfter(grant.expiresAt()))
                || !grant.orderCode().equals(orderCode)
                || !grant.destinationOrg().equals(destination)
                || !grant.purpose().equals(purpose)
                || duration > grant.maxDurationMinutes()) {
            audit.append(context, action, action, ORDER_RESOURCE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "DISCLOSURE_NOT_AUTHORIZED", "the disclosure is not authorized");
        }
        return null;
    }
}
