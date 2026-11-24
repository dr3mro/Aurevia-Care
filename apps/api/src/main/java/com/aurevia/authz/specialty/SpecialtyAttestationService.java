package com.aurevia.authz.specialty;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.specialty.SpecialtyContract.Attestation;
import com.aurevia.authz.specialty.SpecialtyContract.AttestationRequest;
import com.aurevia.authz.specialty.SpecialtyContract.PermissionView;
import com.aurevia.tenant.TenantContext;

/**
 * S-G4-06 specialty authorization and human attestation workflow.
 *
 * <p>Every command runs in one tenant-guarded transaction. Active membership,
 * active roles and the declared role are re-derived inside that transaction, so
 * a deactivation or role change takes effect on the next protected request
 * (IAM-01, IAM-02). The decision class is derived from the attested action, never
 * accepted from the caller: a person decides and attests, and the platform does
 * not make autonomous clinical decisions (PRD-06). A durable failure is carried
 * out of the committed transaction in a {@code Sink}
 * so its DENIED audit row
 * survives while the caller still receives an error.</p>
 */
@Service
public class SpecialtyAttestationService {

    private static final String SCOPE = "SPECIALTY";
    private static final String RESOURCE_TYPE = "SPECIALTY_ATTESTATION";
    private static final String AUDIT_ATTEST = "SPECIALTY_ATTEST";
    private static final String AUDIT_READ = "SPECIALTY_ATTESTATION_READ";
    private static final String AUDIT_PERMISSIONS = "SPECIALTY_PERMISSIONS_READ";
    private static final int MAX_LIMIT = 100;
    private static final int MAX_ATTESTED_TEXT = 1000;
    private static final int MAX_RESOURCE_REF = 128;

    private final SpecialtyAttestationRepository repository;
    private final SpecialtyActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded attestation repository
     * @param actions      bounded specialty action matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public SpecialtyAttestationService(SpecialtyAttestationRepository repository, SpecialtyActions actions,
                                       TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Record one human clinical attestation for an approved specialty action.
     *
     * @param request          validated command fields
     * @param attestedRevision revision of the artifact the human read first
     * @return the immutable attestation
     * @throws ApiException on invalid input, denial or an invisible patient
     */
    public Attestation attest(AttestationRequest request, int attestedRevision) {
        String action = SpecialtyInputs.action(request.actionKey());
        String role = SpecialtyInputs.role(request.roleKey());
        String patientId = SpecialtyInputs.uuid(request.patientId(), "patientId");
        String resourceRef = SpecialtyInputs.text(request.resourceRef(), "resourceRef", MAX_RESOURCE_REF);
        String attestedText = SpecialtyInputs.text(request.attestedText(), "attestedText", MAX_ATTESTED_TEXT);
        String id = SpecialtyInputs.newId();
        return execute(AUDIT_ATTEST, patientId, sink -> {
            TenantContext context = context();
            ApiException denial = membershipDenial(context, AUDIT_ATTEST, patientId);
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            List<String> activeRoles = repository.activeRoles(context.subject());
            if (!activeRoles.contains(role) || !actions.allows(SpecialtyActions.ATTEST, role)
                    || !actions.allows(action, role)) {
                sink.failure = deny(context, AUDIT_ATTEST, patientId,
                        "the declared role may not attest this action");
                return null;
            }
            if (!actions.isAttestable(action)) {
                throw new ApiException(422, "ACTION_NOT_ATTESTABLE",
                        "the action does not represent a PRD-06 clinical decision");
            }
            SpecialtyDecision decision = actions.decisionFor(action);
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            repository.insert(id, patientId, action, role, decision, attestedRevision,
                    resourceRef, attestedText);
            success(context, AUDIT_ATTEST, patientId);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Project the caller's effective specialty permissions in the active organization.
     *
     * @return active roles and the union of their permitted actions
     * @throws ApiException when the caller has no active membership
     */
    public PermissionView permissions() {
        return execute(AUDIT_PERMISSIONS, "PERMISSIONS", sink -> {
            TenantContext context = context();
            ApiException denial = membershipDenial(context, AUDIT_PERMISSIONS, "PERMISSIONS");
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            List<String> roles = repository.activeRoles(context.subject());
            List<String> allowed = actions.allowedActions(roles);
            success(context, AUDIT_PERMISSIONS, "PERMISSIONS");
            return new PermissionView(context.subject(), context.organizationId(), roles, allowed);
        });
    }

    /**
     * Read one attestation with its human provenance.
     *
     * @param id attestation identifier
     * @return the visible attestation
     * @throws ApiException on denial, invalid input or an invisible resource
     */
    public Attestation get(String id) {
        SpecialtyInputs.uuid(id, "id");
        return execute(AUDIT_READ, id, sink -> {
            TenantContext context = context();
            ApiException denial = readDenial(context, id);
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            Attestation attestation = repository.find(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "specialty attestation not found"));
            success(context, AUDIT_READ, id);
            return attestation;
        });
    }

    /**
     * List attestations visible in the active tenant.
     *
     * @param limit maximum number of rows, 1-100
     * @return newest attestations first
     * @throws ApiException on denial, an out-of-range limit or a missing membership
     */
    public List<Attestation> list(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-" + MAX_LIMIT);
        }
        return execute(AUDIT_READ, "LIST", sink -> {
            TenantContext context = context();
            ApiException denial = readDenial(context, "LIST");
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            List<Attestation> attestations = repository.list(limit);
            success(context, AUDIT_READ, "LIST");
            return attestations;
        });
    }

    private <T> T execute(String auditAction, String resourceId, Function<Sink, T> work) {
        Sink sink = new Sink();
        T value = transactions.execute(status -> work.apply(sink));
        if (sink.failure != null) {
            throw sink.failure;
        }
        return value;
    }

    private ApiException membershipDenial(TenantContext context, String auditAction, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            return deny(context, auditAction, resourceId, "no active membership");
        }
        return null;
    }

    private ApiException readDenial(TenantContext context, String resourceId) {
        ApiException denial = membershipDenial(context, AUDIT_READ, resourceId);
        if (denial != null) {
            return denial;
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> actions.allows(SpecialtyActions.ATTESTATION_READ, role))) {
            return deny(context, AUDIT_READ, resourceId, "missing attestation read permission");
        }
        return null;
    }

    private ApiException deny(TenantContext context, String auditAction, String resourceId, String message) {
        audit.append(context, auditAction, auditAction, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
        return new ApiException(403, "FORBIDDEN", message);
    }

    private void success(TenantContext context, String auditAction, String resourceId) {
        audit.append(context, auditAction, auditAction, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private static TenantContext context() {
        return TenantContext.current();
    }

    /**
     * Carries a durable authorization failure out of the committed transaction.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
