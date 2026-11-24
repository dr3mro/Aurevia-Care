package com.aurevia.lab.accession;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionCommands.AccessionRequest;
import com.aurevia.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.lab.accession.AccessionCommands.ReceiveRequest;
import com.aurevia.lab.accession.AccessionCommands.RejectRequest;
import com.aurevia.lab.accession.AccessionCommands.RecollectRequest;
import com.aurevia.lab.accession.LabAccessionRepository.AccessionRow;
import com.aurevia.tenant.TenantContext;

/**
 * Laboratory accession invariants (LAB-01, ARC-03, OPS-02).
 *
 * <p>Each command re-derives membership and role inside one tenant-guarded
 * transaction, validates patient and accession identity at both collection and
 * receipt, and commits its state change, immutable history event and audit row
 * together. A transmission acknowledgment is never treated as a specimen
 * verification, and a rejection never removes the original specimen.</p>
 */
@Service
public class LabAccessionService {

    private static final String PURPOSE = "LAB_ACCESSION";
    private static final String SCOPE = "LAB";
    private static final String RESOURCE_TYPE = "ACCESSION";
    private static final Pattern CURSOR = Pattern.compile(
            "(.+):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final List<String> STATES = Arrays.stream(AccessionState.values())
            .map(AccessionState::wire).toList();

    private final LabAccessionRepository repository;
    private final LabActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded accession repository
     * @param policy       bounded laboratory action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public LabAccessionService(LabAccessionRepository repository, LabActionPolicy policy,
                               TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Accession a requisition against an issuer-scoped unique number.
     *
     * @param request accession command
     * @return the created accession
     * @throws ApiException on invalid input, denial, unknown patient or duplicate number
     */
    public LabAccession accession(AccessionRequest request) {
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        String patientId = LabInputs.patientId(request.patientId());
        String orderRef = LabInputs.sourceOrderRef(request.sourceOrderRef());

        AtomicReference<ApiException> failure = new AtomicReference<>();
        LabAccession created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.ACCESSION_CREATE, number);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.accessionExists(issuer, number)) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_CREATE, RESOURCE_TYPE,
                        SCOPE, number, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            if (!repository.patientExists(patientId)) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_CREATE, RESOURCE_TYPE,
                        SCOPE, number, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            String id = UUID.randomUUID().toString();
            try {
                repository.insert(context.organizationId(), id, number, issuer, patientId, orderRef,
                        AccessionState.ACCESSIONED, null);
            } catch (DuplicateKeyException exception) {
                // A concurrent accession can still win the unique index. Audit before
                // returning so the failed attempt survives while the row change rolls back.
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_CREATE, RESOURCE_TYPE,
                        SCOPE, number, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            repository.appendEvent(id, 1, "accessioned", null, AccessionState.ACCESSIONED, null, null);
            audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_CREATE, RESOURCE_TYPE,
                    SCOPE, id, "SUCCESS");
            return repository.findById(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Record collection after matching patient and accession at the bedside.
     *
     * @param id      specimen identifier
     * @param request collection command
     * @return the collected specimen
     * @throws ApiException on invalid input, denial, mismatch, stale version or illegal state
     */
    public LabAccession collect(String id, CollectRequest request) {
        String specimenId = LabInputs.id(id);
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        String specimenType = LabInputs.specimenType(request.specimenType());
        String siteCode = LabInputs.siteCode(request.siteCode());
        String collector = LabInputs.collector(request.collector());
        String collectedAt = LabInputs.instant(request.collectedAt(), "collectedAt").toString();
        int expectedVersion = LabInputs.version(request.expectedVersion());

        return transition(specimenId, expectedVersion, AccessionState.COLLECTED,
                LabActionPolicy.SPECIMEN_COLLECT, "collected", current -> {
                    match(current, patientId, number, issuer);
                    return new UpdateFields(specimenType, siteCode, collector, collectedAt,
                            null, null, null, null);
                }, patientId);
    }

    /**
     * Receive a specimen after matching patient and accession again.
     *
     * @param id      specimen identifier
     * @param request receipt command
     * @return the received specimen
     * @throws ApiException on invalid input, denial, mismatch, stale version or illegal state
     */
    public LabAccession receive(String id, ReceiveRequest request) {
        String specimenId = LabInputs.id(id);
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        String quality = LabInputs.quality(request.quality());
        String receivedAt = LabInputs.instant(request.receivedAt(), "receivedAt").toString();
        int expectedVersion = LabInputs.version(request.expectedVersion());

        return transition(specimenId, expectedVersion, AccessionState.RECEIVED,
                LabActionPolicy.SPECIMEN_RECEIVE, "received", current -> {
                    match(current, patientId, number, issuer);
                    return new UpdateFields(current.specimenType(), current.siteCode(), current.collector(),
                            current.collectedAt(), receivedAt, quality, null, null);
                }, patientId);
    }

    /**
     * Reject a specimen, preserving the reason, notification and prior state history.
     *
     * @param id      specimen identifier
     * @param request rejection command
     * @return the rejected specimen
     * @throws ApiException on invalid input, denial, stale version or illegal state
     */
    public LabAccession reject(String id, RejectRequest request) {
        String specimenId = LabInputs.id(id);
        String reason = LabInputs.reason(request.reason());
        String notification = LabInputs.notification(request.notification());
        int expectedVersion = LabInputs.version(request.expectedVersion());

        return transition(specimenId, expectedVersion, AccessionState.REJECTED,
                LabActionPolicy.SPECIMEN_REJECT, "rejected", current -> new UpdateFields(
                        current.specimenType(), current.siteCode(), current.collector(),
                        current.collectedAt(), current.receivedAt(), current.quality(),
                        reason, notification), null);
    }

    /**
     * Create a linked recollection of a rejected specimen on the same source order.
     *
     * @param id      rejected specimen identifier
     * @param request recollection command
     * @return the new accession with its link to the rejected specimen
     * @throws ApiException on invalid input, denial, duplicate number, stale version or illegal state
     */
    public LabAccession recollect(String id, RecollectRequest request) {
        String specimenId = LabInputs.id(id);
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        int expectedVersion = LabInputs.version(request.expectedVersion());

        AtomicReference<ApiException> failure = new AtomicReference<>();
        LabAccession created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.SPECIMEN_RECOLLECT, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = repository.findById(specimenId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "accession not found"));
            requireVersion(current, expectedVersion);
            if (current.state() != AccessionState.REJECTED) {
                throw new ApiException(409, "INVALID_TRANSITION", "only a rejected specimen can be recollected");
            }
            if (repository.accessionExists(issuer, number)) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            String newId = UUID.randomUUID().toString();
            try {
                repository.insert(context.organizationId(), newId, number, issuer, current.patientId(),
                        current.sourceOrderRef(), AccessionState.ACCESSIONED, specimenId);
            } catch (DuplicateKeyException exception) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            int updated = repository.update(context.organizationId(), specimenId, expectedVersion,
                    AccessionState.RECOLLECTED, current.specimenType(), current.siteCode(),
                    current.collector(), current.collectedAt(), current.receivedAt(), current.quality(),
                    current.rejectionReason(), current.rejectionNotification());
            if (updated == 0) {
                throw staleVersion();
            }
            repository.appendEvent(specimenId, expectedVersion + 1, "recollected",
                    AccessionState.REJECTED, AccessionState.RECOLLECTED, null, null);
            repository.appendEvent(newId, 1, "accessioned", null, AccessionState.ACCESSIONED, null, null);
            audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                    SCOPE, newId, "SUCCESS");
            return repository.findById(newId).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Read one accession and its immutable history.
     *
     * @param id specimen identifier
     * @return the projection and its history
     * @throws ApiException on invalid input, denial or concealment
     */
    public AccessionView get(String id) {
        String specimenId = LabInputs.id(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AccessionView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.ACCESSION_READ, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession accession = repository.findById(specimenId).orElse(null);
            if (accession == null) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "accession not found"));
                return null;
            }
            List<AccessionEvent> events = repository.history(specimenId);
            audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                    SCOPE, specimenId, "SUCCESS");
            return new AccessionView(accession, events);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    /**
     * Read one cursor-paginated page of the active tenant's accessions.
     *
     * <p>The single-record read action is reused: a caller who may read one
     * accession may list the same tenant's accessions. Input is validated before
     * any transaction and the page read is audited inside the guarded transaction.
     * One extra row is fetched to decide whether a next page exists; its last
     * returned row carries the opaque cursor.</p>
     *
     * @param cursor opaque {@code <createdAt>:<id>} of the previous page's last row, or {@code null}
     * @param limit  page size, or {@code null} for the default of 50
     * @param state  optional exact accession lifecycle state filter
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denial
     */
    public AccessionPage list(String cursor, String limit, String state) {
        AccessionCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        String parsedState = parseState(state);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AccessionPage page = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.ACCESSION_READ, "ALL");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<AccessionRow> rows = repository.query(
                    parsedCursor == null ? null : parsedCursor.createdAt(),
                    parsedCursor == null ? null : parsedCursor.id(), parsedState, parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<AccessionRow> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            String next = null;
            if (hasMore) {
                AccessionRow last = pageRows.get(pageRows.size() - 1);
                next = last.createdAt() + ":" + last.accession().id();
            }
            audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                    SCOPE, "ALL", "SUCCESS");
            return new AccessionPage(pageRows.stream()
                    .map(row -> new AccessionView(row.accession(), List.of())).toList(), next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return page;
    }

    private AccessionCursor parseCursor(String cursor) {
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
        return new AccessionCursor(canonical, matcher.group(2));
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

    private String parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        if (STATES.contains(state)) {
            return state;
        }
        throw new ApiException(422, "INVALID_STATE", "state must be an accession lifecycle state");
    }

    /** Parsed opaque cursor: the canonical creation instant and the immutable row id. */
    private record AccessionCursor(String createdAt, String id) {
    }

    private LabAccession transition(String specimenId, int expectedVersion, AccessionState target,
                                    String action, String eventAction,
                                    java.util.function.Function<LabAccession, UpdateFields> fields,
                                    String matchedPatientId) {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        LabAccession result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, action, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = repository.findById(specimenId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "accession not found"));
            requireVersion(current, expectedVersion);
            requireState(current, target);
            UpdateFields update = fields.apply(current);
            int updated = repository.update(context.organizationId(), specimenId, expectedVersion, target,
                    update.specimenType(), update.siteCode(), update.collector(), update.collectedAt(),
                    update.receivedAt(), update.quality(), update.rejectionReason(),
                    update.rejectionNotification());
            if (updated == 0) {
                throw staleVersion();
            }
            repository.appendEvent(specimenId, expectedVersion + 1, eventAction, current.state(), target,
                    update.rejectionReason(), matchedPatientId);
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, specimenId, "SUCCESS");
            return repository.findById(specimenId).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private void match(LabAccession current, String patientId, String number, String issuer) {
        if (!current.patientId().equals(patientId)
                || !current.accessionNo().equals(number)
                || !current.accessionIssuer().equals(issuer)) {
            throw new ApiException(422, "PATIENT_ACCESSION_MISMATCH",
                    "patient and accession identity do not match the stored requisition");
        }
    }

    private void requireVersion(LabAccession current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private void requireState(LabAccession current, AccessionState target) {
        if (!current.state().canTransitionTo(target)) {
            throw new ApiException(409, "INVALID_TRANSITION",
                    "the specimen cannot move to " + target.wire());
        }
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the specimen changed since it was read");
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing laboratory permission");
        }
        return null;
    }

    /** Mutable columns carried forward through a state transition. */
    private record UpdateFields(String specimenType, String siteCode, String collector,
                                String collectedAt, String receivedAt, String quality,
                                String rejectionReason, String rejectionNotification) {
    }
}
