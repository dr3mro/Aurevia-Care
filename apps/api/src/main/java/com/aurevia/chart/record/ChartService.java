package com.aurevia.chart.record;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.SqlFailureTranslator;
import com.aurevia.audit.AuditAppender;
import com.aurevia.chart.record.ChartRepository.CatalogHit;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-03 chart invariants: validation, authorization, provenance, optional
 * diagnosis mapping and version-bound verification.
 *
 * <p>Every command runs inside one tenant-guarded transaction. Membership and
 * roles are re-derived inside it, denials are audited before they are thrown,
 * and the outcome plus its audit event commit together.</p>
 */
@Service
public class ChartService {

    private static final String SCOPE = "CHART";
    private static final String RESOURCE = "CHART_ENTRY";
    private static final String RECORD_ACTION = "CHART_RECORD";
    private static final String VERIFY_ACTION = "CHART_VERIFY";
    private static final String READ_ACTION = "CHART_READ";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ChartRepository entries;
    private final ChartActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param entries      RLS-bounded chart repository
     * @param policy       chart role matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        application clock for recorded and verified times
     */
    public ChartService(ChartRepository entries, ChartActionPolicy policy,
                        TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.entries = entries;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Record a new, unverified chart entry.
     *
     * @param command validated chart command
     * @return the persisted entry
     * @throws ApiException on invalid input, denial, unknown patient or conflict
     */
    public ChartEntry record(RecordEntryCommand command) {
        TenantContext context = context();
        validate(command, context);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ChartEntry created = transactions.execute(status -> {
            ApiException denial = authorize(context, ChartActionPolicy.CHART_ENTRY_CREATE,
                    command.patientId(), RECORD_ACTION);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!entries.patientExists(command.patientId())) {
                audit.append(context, RECORD_ACTION, RECORD_ACTION, RESOURCE, SCOPE,
                        command.patientId(), "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            String mappedCode = null;
            String mappedText = null;
            Integer catalogVersion = null;
            if (command.type() == EntryType.PROBLEM && command.originalCode() != null) {
                Optional<CatalogHit> hit = entries.lookupCatalog(
                        command.codeSystem(), command.originalCode());
                if (hit.isPresent()) {
                    mappedCode = hit.get().code();
                    mappedText = hit.get().text();
                    catalogVersion = hit.get().version();
                }
            }
            ChartEntry entry = new ChartEntry(
                    UUID.randomUUID().toString(),
                    command.patientId(),
                    command.encounterId(),
                    command.type(),
                    command.originalText(),
                    command.originalCode(),
                    command.codeSystem(),
                    mappedCode,
                    mappedText,
                    catalogVersion,
                    command.source(),
                    command.presence(),
                    normalizedReason(command.unknownReason()),
                    VerificationState.UNVERIFIED,
                    null,
                    null,
                    command.effectiveFrom(),
                    command.effectiveTo(),
                    command.measuredAt(),
                    command.value(),
                    command.unit(),
                    command.clinicalAuthor(),
                    context.subject(),
                    1,
                    clock.instant());
            try {
                entries.insert(entry);
            } catch (DataIntegrityViolationException exception) {
                ApiException translated = SqlFailureTranslator.translate(exception);
                audit.append(context, RECORD_ACTION, RECORD_ACTION, RESOURCE, SCOPE, entry.id(),
                        translated.status() == 404 ? "NOT_FOUND" : "CONFLICT");
                failure.set(translated);
                return null;
            }
            audit.append(context, RECORD_ACTION, RECORD_ACTION, RESOURCE, SCOPE, entry.id(), "SUCCESS");
            return entry;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Verify an entry at the version the caller read.
     *
     * @param id       entry identifier
     * @param expected version from the {@code If-Match} precondition
     * @return the verified entry at the next version
     * @throws ApiException on invalid input, denial, unknown entry, stale or
     *                      already verified entry
     */
    public ChartEntry verify(String id, int expected) {
        requireUuid(id, "INVALID_ENTRY_ID");
        TenantContext context = context();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ChartEntry verified = transactions.execute(status -> {
            ApiException denial = authorize(context, ChartActionPolicy.CHART_ENTRY_VERIFY,
                    id, VERIFY_ACTION);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ChartEntry existing = entries.find(id).orElse(null);
            if (existing == null) {
                audit.append(context, VERIFY_ACTION, VERIFY_ACTION, RESOURCE, SCOPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "chart entry not found"));
                return null;
            }
            // Check the If-Match precondition before the lifecycle state so a
            // concurrent loser always gets 412, whether it read the entry before
            // or after the winner's commit. A stale precondition takes precedence
            // over the already-verified state: a concurrent verifier that read
            // version N reports 412, not 409 (ARC-04: 412 for stale versions).
            if (existing.version() != expected) {
                audit.append(context, VERIFY_ACTION, VERIFY_ACTION, RESOURCE, SCOPE, id, "CONFLICT");
                failure.set(new ApiException(412, "STALE_VERSION", "entry version has changed"));
                return null;
            }
            if (existing.verification() == VerificationState.VERIFIED) {
                audit.append(context, VERIFY_ACTION, VERIFY_ACTION, RESOURCE, SCOPE, id, "CONFLICT");
                failure.set(new ApiException(409, "ALREADY_VERIFIED", "entry is already verified"));
                return null;
            }
            Instant at = clock.instant();
            if (!entries.verify(id, expected, context.subject(), at)) {
                audit.append(context, VERIFY_ACTION, VERIFY_ACTION, RESOURCE, SCOPE, id, "CONFLICT");
                failure.set(new ApiException(412, "STALE_VERSION", "entry version has changed"));
                return null;
            }
            audit.append(context, VERIFY_ACTION, VERIFY_ACTION, RESOURCE, SCOPE, id, "SUCCESS");
            return verified(existing, context.subject(), at);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return verified;
    }

    /**
     * Read one chart entry.
     *
     * @param id entry identifier
     * @return the visible entry
     * @throws ApiException on invalid input, denial or concealment
     */
    public ChartEntry get(String id) {
        requireUuid(id, "INVALID_ENTRY_ID");
        TenantContext context = context();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ChartEntry found = transactions.execute(status -> {
            ApiException denial = authorize(context, ChartActionPolicy.CHART_READ, id, READ_ACTION);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ChartEntry entry = entries.find(id).orElse(null);
            if (entry == null) {
                audit.append(context, READ_ACTION, READ_ACTION, RESOURCE, SCOPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "chart entry not found"));
                return null;
            }
            audit.append(context, READ_ACTION, READ_ACTION, RESOURCE, SCOPE, id, "SUCCESS");
            return entry;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return found;
    }

    /**
     * List a patient's longitudinal chart.
     *
     * @param patientId patient identifier
     * @return an immutable chart view
     * @throws ApiException on invalid input or denial
     */
    public ChartView listForPatient(String patientId) {
        requireUuid(patientId, "INVALID_PATIENT_ID");
        TenantContext context = context();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<ChartEntry> found = transactions.execute(status -> {
            ApiException denial = authorize(context, ChartActionPolicy.CHART_READ,
                    patientId, READ_ACTION);
            if (denial != null) {
                failure.set(denial);
                return List.of();
            }
            List<ChartEntry> list = List.copyOf(entries.chart(patientId));
            audit.append(context, READ_ACTION, READ_ACTION, RESOURCE, SCOPE, patientId, "SUCCESS");
            return list;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return new ChartView(found);
    }

    private ApiException authorize(TenantContext context, String action, String resourceId,
                                   String auditAction) {
        if (!entries.hasActiveMembership(context.subject())) {
            audit.append(context, auditAction, auditAction, RESOURCE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        for (String role : entries.activeRoles(context.subject())) {
            if (policy.allows(action, role)) {
                return null;
            }
        }
        audit.append(context, auditAction, auditAction, RESOURCE, SCOPE, resourceId, "DENIED");
        return new ApiException(403, "FORBIDDEN", "role not permitted");
    }

    private void validate(RecordEntryCommand command, TenantContext context) {
        if (command == null) {
            throw new ApiException(422, "INVALID_COMMAND", "a chart entry command is required");
        }
        requireUuid(command.patientId(), "INVALID_PATIENT_ID");
        if (command.type() == null) {
            throw new ApiException(422, "INVALID_TYPE", "entry type is required");
        }
        String text = command.originalText();
        if (text == null || text.isBlank() || text.length() > 2000) {
            throw new ApiException(422, "INVALID_TEXT", "original text must be 1-2000 characters");
        }
        // Optional text crosses an external trust boundary; bound it before it reaches
        // an audit row or a catalog lookup key (defence in depth beside the V13 checks).
        requireBounded(command.encounterId(), 120, "INVALID_ENCOUNTER_ID");
        requireBounded(command.originalCode(), 120, "INVALID_CODE");
        requireBounded(command.codeSystem(), 120, "INVALID_CODE");
        requireBounded(command.unknownReason(), 500, "INVALID_UNKNOWN_REASON");
        requireBounded(command.value(), 500, "INVALID_VALUE");
        requireBounded(command.unit(), 120, "INVALID_UNIT");
        if (command.source() == null) {
            throw new ApiException(422, "INVALID_SOURCE", "source is required");
        }
        if (command.presence() == null) {
            throw new ApiException(422, "INVALID_PRESENCE", "presence is required");
        }
        String reason = normalizedReason(command.unknownReason());
        if (command.presence() == PresenceState.UNKNOWN) {
            if (reason == null) {
                throw new ApiException(422, "INVALID_UNKNOWN_REASON",
                        "an unknown assertion requires a reason");
            }
        } else if (reason != null) {
            throw new ApiException(422, "INVALID_UNKNOWN_REASON",
                    "only an unknown assertion may carry a reason");
        }
        if ((command.originalCode() == null) != (command.codeSystem() == null)) {
            throw new ApiException(422, "INVALID_CODE", "code and code system must be paired");
        }
        if (command.effectiveFrom() != null && command.effectiveTo() != null
                && command.effectiveTo().isBefore(command.effectiveFrom())) {
            throw new ApiException(422, "INVALID_EFFECTIVE_PERIOD",
                    "effective end must not precede effective start");
        }
        if (command.type() == EntryType.VITAL
                && (command.value() == null || command.value().isBlank()
                    || command.unit() == null || command.unit().isBlank()
                    || command.measuredAt() == null)) {
            throw new ApiException(422, "INVALID_VITAL",
                    "a vital requires value, unit and measurement time");
        }
        String author = command.clinicalAuthor();
        if (author != null && (author.isBlank() || author.length() > 120)) {
            throw new ApiException(422, "INVALID_CLINICAL_AUTHOR",
                    "clinical author must be 1-120 characters");
        }
        if (author != null && !author.equals(context.subject())) {
            throw new ApiException(422, "INVALID_CLINICAL_AUTHOR",
                    "an entry may only attribute clinical authorship to its recorder");
        }
    }

    private ChartEntry verified(ChartEntry entry, String actor, Instant at) {
        return new ChartEntry(entry.id(), entry.patientId(), entry.encounterId(), entry.type(),
                entry.originalText(), entry.originalCode(), entry.codeSystem(), entry.mappedCode(),
                entry.mappedText(), entry.catalogVersion(), entry.source(), entry.presence(),
                entry.unknownReason(), VerificationState.VERIFIED, actor, at, entry.effectiveFrom(),
                entry.effectiveTo(), entry.measuredAt(), entry.value(), entry.unit(),
                // A recorder may leave clinical authorship open; the verifying clinician
                // becomes the accountable author. The repository applies the same COALESCE
                // so the returned view and the stored row agree (CLN-03).
                entry.clinicalAuthor() == null ? actor : entry.clinicalAuthor(),
                entry.dataEntryActor(), entry.version() + 1,
                entry.recordedAt());
    }

    /**
     * Treat a blank reason as absent so it is never persisted. V13 permits a
     * reason only on an UNKNOWN assertion; storing an empty string on a
     * PRESENT/ABSENT row would fail that CHECK and surface as a misleading 409.
     *
     * @param reason submitted reason, possibly {@code null} or blank
     * @return the reason, or {@code null} when it is absent or blank
     */
    private static String normalizedReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason;
    }

    private static void requireBounded(String value, int max, String code) {
        if (value != null && value.length() > max) {
            throw new ApiException(422, code, "text field exceeds " + max + " characters");
        }
    }

    private static void requireUuid(String value, String code) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new ApiException(422, code, "a valid identifier is required");
        }
    }

    private static TenantContext context() {
        TenantContext current = TenantContext.current();
        if (current == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return current;
    }
}
