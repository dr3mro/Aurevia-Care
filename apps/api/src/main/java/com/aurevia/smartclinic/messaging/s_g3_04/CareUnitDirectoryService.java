package com.aurevia.smartclinic.messaging.s_g3_04;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.ConnectRequest;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.ConnectionList;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.EligibleRelationship;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.MessagingAudit;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.PatientUnitConnection;
import com.aurevia.smartclinic.messaging.s_g3_04.MessagingContract.UnitDirectory;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-01 / ARC-07 domain service for the care-unit directory and verified,
 * connection-scoped patient-unit relationships.
 *
 * <p>Every protected method derives the organization and subject from the
 * request context, re-checks active membership and role, and re-checks the
 * verified chart link. A directory entry is never an authorization: a unit is
 * listed and connectable only when an independently verified relationship ties
 * that exact patient to that exact unit in the active organization.</p>
 */
@Service
public class CareUnitDirectoryService {

    private static final String RESOURCE_TYPE = "PATIENT_UNIT_CONNECTION";

    private final MessagingStore store;
    private final MessagingActions actions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store   tenant-scoped messaging store
     * @param actions bounded action matrix
     * @param clock   application clock for invitation expiry and timestamps
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public CareUnitDirectoryService(MessagingStore store, MessagingActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * List the care units the authenticated patient is eligible to contact.
     *
     * @return units backed by an active verified relationship in this tenant
     * @throws ApiException on missing context, denial or no verified chart
     */
    public UnitDirectory eligibleUnits() {
        TenantContext context = requireContext();
        authorize(context, MessagingActions.UNIT_LIST, "DIRECTORY");
        Set<String> charts = verifiedCharts(context, MessagingActions.UNIT_LIST);
        Set<String> unitIds = new LinkedHashSet<>();
        for (String patientId : charts) {
            for (EligibleRelationship relationship : store.relationshipsFor(context.organizationId(), patientId)) {
                if (isUsable(relationship) && store.findUnit(context.organizationId(), relationship.unitId())
                        .filter(CareUnit::active).isPresent()) {
                    unitIds.add(relationship.unitId());
                }
            }
        }
        List<CareUnit> units = new ArrayList<>();
        for (String unitId : unitIds) {
            store.findUnit(context.organizationId(), unitId).ifPresent(units::add);
        }
        audit(context, MessagingActions.UNIT_LIST, "DIRECTORY", "SUCCESS");
        return new UnitDirectory(units);
    }

    /**
     * Connect a verified patient chart to one eligible recipient unit.
     *
     * <p>The same verified reference returns the original connection so retries
     * cannot create parallel conversations. A single-use invitation is consumed
     * on the first successful connection.</p>
     *
     * @param request validated connection command
     * @return the granted active connection
     * @throws ApiException on invalid input, denial, concealment or ineligible relationship
     */
    public PatientUnitConnection connect(ConnectRequest request) {
        String patientId = MessagingInputs.uuid(request.patientId(), "patientId");
        String unitId = MessagingInputs.uuid(request.unitId(), "unitId");
        ConnectionContext contextKind = MessagingInputs.context(request.context());
        String reference = MessagingInputs.text(request.contextReference(), "contextReference", 128);
        TenantContext context = requireContext();
        authorize(context, MessagingActions.CONNECTION_CREATE, unitId);
        if (!store.patientExists(context.organizationId(), patientId)) {
            throw deny(context, MessagingActions.CONNECTION_CREATE, unitId, 404, "NOT_FOUND",
                    "patient not found");
        }
        Set<String> charts = verifiedCharts(context, MessagingActions.CONNECTION_CREATE);
        if (!charts.contains(patientId)) {
            throw deny(context, MessagingActions.CONNECTION_CREATE, unitId, 403, "FORBIDDEN",
                    "the chart is not verified for this account");
        }
        CareUnit unit = store.findUnit(context.organizationId(), unitId)
                .filter(CareUnit::active)
                .orElseThrow(() -> deny(context, MessagingActions.CONNECTION_CREATE, unitId, 404,
                        "NOT_FOUND", "care unit not found"));
        String selectedReference = reference;
        Optional<PatientUnitConnection> existing = store.findConnectionByReference(
                context.organizationId(), patientId, unitId, selectedReference);
        if (existing.isPresent()) {
            PatientUnitConnection previous = existing.get();
            if (previous.state() != ConnectionState.ACTIVE || previous.context() != contextKind) {
                throw deny(context, MessagingActions.CONNECTION_CREATE, unitId, 409, "CONFLICT",
                        "the verified reference already has an inactive or different connection");
            }
            return previous;
        }
        Instant now = clock.instant();
        EligibleRelationship relationship = store.relationshipsFor(context.organizationId(), patientId).stream()
                .filter(candidate -> unitId.equals(candidate.unitId()))
                .filter(candidate -> contextKind == candidate.context())
                .filter(candidate -> selectedReference.equals(candidate.reference()))
                .filter(this::isUsable)
                .findFirst()
                .orElseThrow(() -> deny(context, MessagingActions.CONNECTION_CREATE, unit.id(), 422,
                        "NO_ELIGIBLE_RELATIONSHIP",
                        "no active verified relationship ties this patient to this unit"));
        if (contextKind == ConnectionContext.INVITATION) {
            store.consumeRelationship(context.organizationId(), relationship.id());
        }
        PatientUnitConnection connection = new PatientUnitConnection(MessagingInputs.newId(),
                context.organizationId(), patientId, unit.id(), relationship.context(),
                relationship.reference(), relationship.id(), ConnectionState.ACTIVE,
                context.subject(), now, now, null);
        PatientUnitConnection stored = store.insertConnection(context.organizationId(), connection);
        audit(context, MessagingActions.CONNECTION_CREATE, stored.id(), "SUCCESS");
        return stored;
    }

    /**
     * List the connections visible for one verified patient chart.
     *
     * @param patientId verified chart identifier
     * @return the chart's connections
     * @throws ApiException on invalid input, denial or an unverified chart
     */
    public ConnectionList listConnections(String patientId) {
        String id = MessagingInputs.uuid(patientId, "patientId");
        TenantContext context = requireContext();
        authorize(context, MessagingActions.CONNECTION_LIST, id);
        if (!verifiedCharts(context, MessagingActions.CONNECTION_LIST).contains(id)) {
            throw deny(context, MessagingActions.CONNECTION_LIST, id, 404, "NOT_FOUND", "patient not found");
        }
        List<PatientUnitConnection> connections = store.connectionsFor(context.organizationId(), id);
        audit(context, MessagingActions.CONNECTION_LIST, id, "SUCCESS");
        return new ConnectionList(connections);
    }

    /**
     * Revoke a connection so it grants no future access.
     *
     * @param connectionId connection identifier
     * @return the revoked connection
     * @throws ApiException on invalid input, denial, concealment or a stale state
     */
    public PatientUnitConnection revoke(String connectionId) {
        String id = MessagingInputs.uuid(connectionId, "connectionId");
        TenantContext context = requireContext();
        authorize(context, MessagingActions.CONNECTION_REVOKE, id);
        PatientUnitConnection current = store.findConnection(context.organizationId(), id)
                .orElseThrow(() -> deny(context, MessagingActions.CONNECTION_REVOKE, id, 404,
                        "NOT_FOUND", "connection not found"));
        boolean staffRevoker = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> "tenant_admin".equals(role));
        if (!staffRevoker && !verifiedCharts(context, MessagingActions.CONNECTION_REVOKE)
                .contains(current.patientId())) {
            throw deny(context, MessagingActions.CONNECTION_REVOKE, id, 404, "NOT_FOUND",
                    "connection not found");
        }
        if (current.state() != ConnectionState.ACTIVE) {
            throw deny(context, MessagingActions.CONNECTION_REVOKE, id, 409, "ALREADY_REVOKED",
                    "the connection is already revoked");
        }
        store.revokeConnection(context.organizationId(), id, clock.instant());
        audit(context, MessagingActions.CONNECTION_REVOKE, id, "SUCCESS");
        return store.findConnection(context.organizationId(), id).orElseThrow();
    }

    /**
     * Assert that a protected messaging action may use the given connection.
     *
     * <p>Future messaging commands (MSG-02–07) call this on every request so an
     * unlinked chart, a revoked connection or a wrong-unit route fails closed.</p>
     *
     * @param patientId verified chart identifier
     * @param unitId    recipient unit identifier
     * @throws ApiException when the chart or an active connection is missing
     */
    public void requireActiveConnection(String patientId, String unitId) {
        String chart = MessagingInputs.uuid(patientId, "patientId");
        String unit = MessagingInputs.uuid(unitId, "unitId");
        TenantContext context = requireContext();
        authorize(context, MessagingActions.CONNECTION_USE, unit);
        if (!verifiedCharts(context, MessagingActions.CONNECTION_USE).contains(chart)
                || store.connectionsFor(context.organizationId(), chart).stream()
                        .noneMatch(connection -> unit.equals(connection.unitId())
                                && connection.state() == ConnectionState.ACTIVE)) {
            throw deny(context, MessagingActions.CONNECTION_USE, unit, 403, "FORBIDDEN",
                    "no active connection for this patient and unit");
        }
    }

    private boolean isUsable(EligibleRelationship relationship) {
        return relationship.active() && !relationship.consumed()
                && (relationship.expiresAt() == null || relationship.expiresAt().isAfter(clock.instant()));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private Set<String> verifiedCharts(TenantContext context, String action) {
        Set<String> charts = store.verifiedPatientCharts(context.organizationId(), context.subject());
        if (charts.isEmpty()) {
            throw deny(context, action, "ACCOUNT", 403, "NO_VERIFIED_CHART",
                    "the account has no verified patient chart");
        }
        return charts;
    }

    private void authorize(TenantContext context, String action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "no active membership");
        }
        boolean permitted = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> actions.allows(action, role));
        if (!permitted) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private ApiException deny(TenantContext context, String action, String resourceId,
                              int status, String code, String message) {
        audit(context, action, resourceId, status == 404 ? "NOT_FOUND" : status == 409 ? "CONFLICT" : "DENIED");
        return new ApiException(status, code, message);
    }

    private void audit(TenantContext context, String action, String resourceId, String outcome) {
        store.appendAudit(new MessagingAudit(context.organizationId(), context.subject(),
                action, resourceId, outcome, clock.instant()));
    }
}
