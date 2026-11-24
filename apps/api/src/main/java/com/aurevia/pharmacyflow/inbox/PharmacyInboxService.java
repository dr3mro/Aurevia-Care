package com.aurevia.pharmacyflow.inbox;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Cursor;
import com.aurevia.api.contract.PageRequest;
import com.aurevia.audit.AuditAppender;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxItem;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxPage;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxRepository.InboxRow;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxInputs;
import com.aurevia.tenant.TenantContext;

/**
 * RX-02 / XCH-01 pharmacy inbox domain service.
 *
 * <p>Every read runs in one tenant-guarded transaction. Active membership, the
 * {@code pharmacy_staff} role and the subject's approved pharmacy assignment are
 * re-derived from authoritative database folds on every request; a denial is
 * audited before it is returned. Only prescriptions explicitly routed to the
 * assigned pharmacy are projected, newest first, with a stable tenant-bound
 * cursor. No chart data is read or returned.</p>
 */
@Service
public class PharmacyInboxService {

    private static final String PURPOSE = "PHARMACY_INBOX";
    private static final String RESOURCE_TYPE = "PRESCRIPTION";
    private static final String SCOPE = "PHARMACY_INBOX";
    private static final String ALL = "ALL";
    private static final Pattern PHARMACY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private final PharmacyInboxRepository repository;
    private final PharmacyInboxActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded pharmacy inbox repository
     * @param actions      bounded pharmacy role matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public PharmacyInboxService(PharmacyInboxRepository repository, PharmacyInboxActions actions,
                                TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * List prescriptions routed to the reading pharmacy, newest first.
     *
     * @param status optional prescription state filter, or {@code null}/blank for all
     * @param cursor opaque tenant-bound cursor from the previous page, or {@code null}
     * @param limit  page size, or {@code null}/blank for the default
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input, denial or a missing pharmacy assignment
     */
    public InboxPage list(String status, String cursor, String limit) {
        String parsedStatus = parseStatus(status);
        TenantContext context = TenantContext.current();
        PageRequest page = PageRequest.parse(cursor, limit, context.organizationId());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        InboxPage result = transactions.execute(ignored -> {
            Authorization authorization = authorize(context);
            if (authorization.denial() != null) {
                failure.set(authorization.denial());
                return null;
            }
            String pharmacy = authorization.pharmacy();
            List<InboxRow> rows = repository.query(pharmacy, parsedStatus,
                    page.cursor() == null ? null : page.cursor().sortKey(),
                    page.cursor() == null ? null : page.cursor().id(), page.limit() + 1);
            boolean hasMore = rows.size() > page.limit();
            List<InboxRow> pageRows = hasMore ? rows.subList(0, page.limit()) : rows;
            String next = null;
            if (hasMore) {
                InboxRow last = pageRows.get(pageRows.size() - 1);
                next = Cursor.encode(new Cursor(context.organizationId(), last.sortKey(), last.item().id()));
            }
            audit.append(context, PURPOSE, PharmacyInboxActions.READ, RESOURCE_TYPE, SCOPE, pharmacy, "SUCCESS");
            return new InboxPage(pageRows.stream().map(InboxRow::item).toList(), next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Read one prescription only when it is explicitly routed to the reading pharmacy.
     *
     * @param id prescription identifier
     * @return the routed projection
     * @throws ApiException on invalid input, denial, a missing assignment or an invisible prescription
     */
    public InboxItem get(String id) {
        String parsedId = RxInputs.uuid(id, "id");
        TenantContext context = TenantContext.current();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        InboxItem result = transactions.execute(ignored -> {
            Authorization authorization = authorize(context);
            if (authorization.denial() != null) {
                failure.set(authorization.denial());
                return null;
            }
            InboxItem item = repository.findRouted(parsedId, authorization.pharmacy())
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "prescription not found"));
            audit.append(context, PURPOSE, PharmacyInboxActions.READ, RESOURCE_TYPE, SCOPE, parsedId, "SUCCESS");
            return item;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return State.from(status.trim()).wire();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_STATUS", "status must be a prescription state");
        }
    }

    private Authorization authorize(TenantContext context) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, PharmacyInboxActions.READ, RESOURCE_TYPE, SCOPE, ALL, "DENIED");
            return denied("no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> actions.allows(PharmacyInboxActions.READ, role))) {
            audit.append(context, PURPOSE, PharmacyInboxActions.READ, RESOURCE_TYPE, SCOPE, ALL, "DENIED");
            return denied("missing pharmacy inbox permission");
        }
        String pharmacy = repository.assignedPharmacy(context.subject()).orElse(null);
        if (pharmacy == null || !PHARMACY_KEY.matcher(pharmacy).matches()) {
            audit.append(context, PURPOSE, PharmacyInboxActions.READ, RESOURCE_TYPE, SCOPE, ALL, "DENIED");
            return denied("no valid pharmacy assignment");
        }
        return new Authorization(pharmacy, null);
    }

    private static Authorization denied(String message) {
        return new Authorization(null, new ApiException(403, "FORBIDDEN", message));
    }

    /** Authorization outcome carrying the assigned pharmacy when access is allowed. */
    private record Authorization(String pharmacy, ApiException denial) {
    }
}
