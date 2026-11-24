package com.aurevia.messaging.enrollment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;

/**
 * Synthetic, thread-safe in-memory patient-access store.
 *
 * <p>Keys embed the organization identifier so two tenants with identical local
 * identifiers remain isolated. A synthetic chart registry is seeded through
 * {@code seedChart} for tests and integrations. No real patient
 * data is ever held here.</p>
 */
public final class InMemoryPatientAccessStore implements PatientAccessStore {

    private final Map<String, Invitation> invitations = new ConcurrentHashMap<>();
    private final Map<String, PatientAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, AccessToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, SupportCase> cases = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> charts = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<AccessEvent>> audit = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<GenericNotification>> notifications = new ConcurrentHashMap<>();

    /**
     * Seed one synthetic chart in a tenant for isolation and existence checks.
     *
     * @param organizationId active organization
     * @param chartId        tenant-local chart identifier
     */
    public void seedChart(String organizationId, String chartId) {
        charts.computeIfAbsent(organizationId, key -> ConcurrentHashMap.newKeySet()).add(chartId);
    }

    @Override
    public boolean chartExists(String organizationId, String chartId) {
        return charts.getOrDefault(organizationId, Set.of()).contains(chartId);
    }

    @Override
    public Optional<Invitation> invitation(String organizationId, String code) {
        return Optional.ofNullable(invitations.get(key(organizationId, code)));
    }

    @Override
    public void putInvitation(String organizationId, Invitation invitation) {
        invitations.put(key(organizationId, invitation.code()), invitation);
    }

    @Override
    public boolean consumeInvitation(String organizationId, String code) {
        boolean[] consumed = { false };
        invitations.computeIfPresent(key(organizationId, code), (unused, current) -> {
            if (current.state() != InvitationState.PENDING) {
                return current;
            }
            consumed[0] = true;
            return new Invitation(current.code(), current.organizationId(), current.chartId(),
                    current.factDigest(), current.expiresAt(), InvitationState.REDEEMED,
                    current.failedAttempts(), current.issuedBy(), current.issuedAt());
        });
        return consumed[0];
    }

    @Override
    public void recordInvitationFailure(String organizationId, String code, int failedAttempts) {
        invitations.computeIfPresent(key(organizationId, code), (unused, current) -> {
            if (current.state() != InvitationState.PENDING) {
                return current;
            }
            return new Invitation(current.code(), current.organizationId(), current.chartId(),
                    current.factDigest(), current.expiresAt(), current.state(), failedAttempts,
                    current.issuedBy(), current.issuedAt());
        });
    }

    @Override
    public Optional<PatientAccount> account(String organizationId, String accountId) {
        return Optional.ofNullable(accounts.get(key(organizationId, accountId)));
    }

    @Override
    public Optional<PatientAccount> accountBySubject(String organizationId, String subject) {
        return accounts.values().stream()
                .filter(account -> organizationId.equals(account.organizationId()) && account.subject().equals(subject))
                .findFirst();
    }

    @Override
    public void putAccount(String organizationId, PatientAccount account) {
        accounts.put(key(organizationId, account.id()), account);
    }

    @Override
    public Optional<AccessToken> token(String organizationId, String token) {
        return Optional.ofNullable(tokens.get(key(organizationId, token)));
    }

    @Override
    public void putToken(String organizationId, AccessToken accessToken) {
        tokens.put(key(organizationId, accessToken.token()), accessToken);
    }

    @Override
    public boolean consumeToken(String organizationId, String token) {
        boolean[] consumed = { false };
        tokens.computeIfPresent(key(organizationId, token), (unused, current) -> {
            if (current.used()) {
                return current;
            }
            consumed[0] = true;
            return new AccessToken(current.token(), current.organizationId(), current.subject(),
                    current.purpose(), current.expiresAt(), true);
        });
        return consumed[0];
    }

    @Override
    public Optional<SupportCase> supportCase(String organizationId, String caseId) {
        return Optional.ofNullable(cases.get(key(organizationId, caseId)));
    }

    @Override
    public void putSupportCase(String organizationId, SupportCase supportCase) {
        cases.put(key(organizationId, supportCase.id()), supportCase);
    }

    @Override
    public List<SupportCase> supportCases(String organizationId) {
        return cases.values().stream()
                .filter(supportCase -> organizationId.equals(supportCase.organizationId()))
                .toList();
    }

    @Override
    public List<AccessEvent> audit(String organizationId) {
        return List.copyOf(audit.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void appendAudit(AccessEvent event) {
        audit.computeIfAbsent(event.organizationId(), unused -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<GenericNotification> notifications(String organizationId) {
        return List.copyOf(notifications.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void appendNotification(GenericNotification notification) {
        notifications.computeIfAbsent(notification.organizationId(), unused -> new CopyOnWriteArrayList<>())
                .add(notification);
    }

    private static String key(String organizationId, String localId) {
        return organizationId + "|" + localId;
    }
}
