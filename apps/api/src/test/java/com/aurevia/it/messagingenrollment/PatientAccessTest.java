package com.aurevia.it.messagingenrollment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountView;
import com.aurevia.messaging.enrollment.EnrollmentContract.CaseState;
import com.aurevia.messaging.enrollment.EnrollmentContract.ChangeContactRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RecoverRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveAction;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind;
import com.aurevia.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessService;

/**
 * S-G3-03 acceptance for PAT-03: privacy-safe enrollment, recovery, lockout,
 * renewed authentication and an identity-dispute support channel. All fixtures
 * are synthetic and the module is exercised through its public domain service
 * with no external infrastructure.
 */
class PatientAccessTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CHART_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHART_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String OTHER_CHART = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SUBJECT_A = "syn-patient-a";
    private static final String SUBJECT_B = "syn-patient-b";
    private static final String SUBJECT_PEER = "syn-patient-peer";
    private static final String FACTS = "site-verified-facts";
    private static final String CONTACT = "syn-contact";

    private InMemoryPatientAccessStore store;
    private EnrollmentActions actions;
    private MutableClock clock;
    private PatientAccessService service;

    private final PatientAccessActor staffA = PatientAccessActor.of(ORG_A, "reception-a", EnrollmentActions.RECEPTION);
    private final PatientAccessActor adminA = PatientAccessActor.of(ORG_A, "admin-a", EnrollmentActions.TENANT_ADMIN);
    private final PatientAccessActor privacyA = PatientAccessActor.of(ORG_A, "privacy-a", EnrollmentActions.PRIVACY_AUDITOR);
    private final PatientAccessActor supportA = PatientAccessActor.of(ORG_A, "support-a", EnrollmentActions.SUPPORT_AGENT);
    private final PatientAccessActor patientA = PatientAccessActor.of(ORG_A, SUBJECT_A, EnrollmentActions.PATIENT);
    private final PatientAccessActor patientB = PatientAccessActor.of(ORG_B, SUBJECT_B, EnrollmentActions.PATIENT);
    private final PatientAccessActor staffB = PatientAccessActor.of(ORG_B, "reception-b", EnrollmentActions.RECEPTION);
    private final PatientAccessActor clinicianA = PatientAccessActor.of(ORG_A, "clinician-a", "clinician");

    @BeforeEach
    void setUp() {
        store = new InMemoryPatientAccessStore();
        actions = new EnrollmentActions();
        clock = new MutableClock();
        service = new PatientAccessService(store, actions, clock);
    }

    // --- contract value coverage -------------------------------------------------

    @Test
    void contractStateEnumsRoundTripAndRejectUnknown() {
        for (AccountState value : AccountState.values()) {
            assertEquals(value, AccountState.from(value.wire()));
        }
        for (InvitationState value : InvitationState.values()) {
            assertEquals(value, InvitationState.from(value.wire()));
        }
        for (SupportKind value : SupportKind.values()) {
            assertEquals(value, SupportKind.from(value.wire()));
        }
        for (CaseState value : CaseState.values()) {
            assertEquals(value, CaseState.from(value.wire()));
        }
        for (SensitiveAction value : SensitiveAction.values()) {
            assertEquals(value, SensitiveAction.from(value.wire()));
        }
        for (EventType value : EventType.values()) {
            assertEquals(value, EventType.from(value.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> AccountState.from("bad"));
        assertThrows(IllegalArgumentException.class, () -> InvitationState.from("bad"));
        assertThrows(IllegalArgumentException.class, () -> SupportKind.from("bad"));
        assertThrows(IllegalArgumentException.class, () -> CaseState.from("bad"));
        assertThrows(IllegalArgumentException.class, () -> SensitiveAction.from("bad"));
        assertThrows(IllegalArgumentException.class, () -> EventType.from("bad"));
    }

    @Test
    void caseStateAllowsOnlyForwardTransitionsAndNoOp() {
        assertTrue(CaseState.OPEN.canTransitionTo(CaseState.OPEN));
        assertTrue(CaseState.OPEN.canTransitionTo(CaseState.IN_REVIEW));
        assertFalse(CaseState.OPEN.canTransitionTo(CaseState.RESOLVED));
        assertTrue(CaseState.IN_REVIEW.canTransitionTo(CaseState.RESOLVED));
        assertFalse(CaseState.IN_REVIEW.canTransitionTo(CaseState.OPEN));
        assertFalse(CaseState.RESOLVED.canTransitionTo(CaseState.OPEN));
    }

    @Test
    void actorAndAccountFreezeTheirSets() {
        Set<String> roles = new HashSet<>(Set.of(EnrollmentActions.PATIENT));
        PatientAccessActor actor = new PatientAccessActor(ORG_A, SUBJECT_A, roles);
        roles.add(EnrollmentActions.TENANT_ADMIN);
        assertEquals(Set.of(EnrollmentActions.PATIENT), actor.roles());
        assertThrows(UnsupportedOperationException.class, () -> actor.roles().add("x"));

        PatientAccount account = enrollAccount();
        Set<String> links = account.linkedChartIds();
        assertThrows(UnsupportedOperationException.class, () -> links.add(OTHER_CHART));
    }

    @Test
    void actionMatrixAllowsCorrectRolesAndDeniesOthers() {
        assertTrue(actions.allows(EnrollmentActions.INVITE_ISSUE, Set.of(EnrollmentActions.RECEPTION)));
        assertFalse(actions.allows(EnrollmentActions.INVITE_ISSUE, Set.of("clinician")));
        assertFalse(actions.allows("UNKNOWN_ACTION", Set.of(EnrollmentActions.PATIENT)));
        assertFalse(actions.allows(EnrollmentActions.INVITE_ISSUE, Set.of()));
        assertTrue(actions.allows(EnrollmentActions.INVITE_REDEEM, Set.of(EnrollmentActions.PATIENT)));
        assertTrue(actions.allows(EnrollmentActions.RECOVERY_ISSUE, Set.of(EnrollmentActions.PRIVACY_AUDITOR)));
        assertTrue(actions.allows(EnrollmentActions.RECOVERY_REDEEM, Set.of(EnrollmentActions.PATIENT)));
        assertTrue(actions.allows(EnrollmentActions.ACCOUNT_UNLOCK, Set.of(EnrollmentActions.TENANT_ADMIN)));
        assertTrue(actions.allows(EnrollmentActions.SENSITIVE_CHANGE, Set.of(EnrollmentActions.PATIENT)));
        assertTrue(actions.allows(EnrollmentActions.CONTACT_EDIT_STAFF, Set.of(EnrollmentActions.RECEPTION)));
        assertTrue(actions.allows(EnrollmentActions.SUPPORT_OPEN, Set.of(EnrollmentActions.PATIENT)));
        assertTrue(actions.allows(EnrollmentActions.SUPPORT_ASSIGN, Set.of(EnrollmentActions.SUPPORT_AGENT)));
        assertTrue(actions.allows(EnrollmentActions.SUPPORT_RESOLVE, Set.of(EnrollmentActions.PRIVACY_AUDITOR)));
        assertTrue(actions.allows(EnrollmentActions.SUPPORT_READ, Set.of(EnrollmentActions.TENANT_ADMIN)));
        assertTrue(actions.allows(EnrollmentActions.AUDIT_READ, Set.of(EnrollmentActions.PRIVACY_AUDITOR)));
        assertTrue(actions.allows(EnrollmentActions.ACCESS_CHART, Set.of(EnrollmentActions.PATIENT)));
    }

    // --- store coverage ----------------------------------------------------------

    @Test
    void storeIsTenantScopedAndCoversAbsentLookups() {
        store.seedChart(ORG_A, CHART_A);
        assertTrue(store.chartExists(ORG_A, CHART_A));
        assertFalse(store.chartExists(ORG_B, CHART_A));
        assertFalse(store.chartExists(ORG_A, OTHER_CHART));
        assertTrue(store.invitation(ORG_A, "none").isEmpty());
        assertTrue(store.account(ORG_A, "none").isEmpty());
        assertTrue(store.accountBySubject(ORG_A, SUBJECT_A).isEmpty());
        assertTrue(store.token(ORG_A, "none").isEmpty());
        assertTrue(store.supportCase(ORG_A, "none").isEmpty());
        assertTrue(store.supportCases(ORG_A).isEmpty());
        assertTrue(store.audit(ORG_A).isEmpty());
        assertTrue(store.notifications(ORG_A).isEmpty());
    }

    // --- enrollment --------------------------------------------------------------

    @Test
    void enrollCreatesVerifiedAccountWithSingleUseInvitation() {
        Invitation invitation = issue(staffA, CHART_A, FACTS);
        assertEquals(InvitationState.PENDING, invitation.state());
        assertEquals(ORG_A, invitation.organizationId());

        PatientAccount account = service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS));
        assertEquals(SUBJECT_A, account.subject());
        assertEquals(Set.of(CHART_A), account.linkedChartIds());
        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(ORG_A, account.organizationId());
        assertEquals(invitation.factDigest(), account.factDigest());
        assertTrue(store.accountBySubject(ORG_A, SUBJECT_A).isPresent());
        assertEquals(InvitationState.REDEEMED, store.invitation(ORG_A, invitation.code()).orElseThrow().state());
        assertEquals(2, store.audit(ORG_A).stream()
                .filter(event -> event.eventType() == EventType.INVITATION_ISSUED
                        || event.eventType() == EventType.ENROLL_SUCCESS).count());
        assertEquals("enrollment_confirmed", store.notifications(ORG_A).get(0).eventType());
    }

    @Test
    void invitationCannotBeReusedOrRedeemedByAnotherTenant() {
        Invitation invitation = issue(staffA, CHART_A, FACTS);
        ApiException crossTenant = assertThrows(ApiException.class,
                () -> service.enroll(patientB, new EnrollRequest(invitation.code(), FACTS)));
        assertEquals(404, crossTenant.status());
        assertTrue(store.accountBySubject(ORG_B, SUBJECT_B).isEmpty());

        service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS));
        ApiException reused = assertThrows(ApiException.class,
                () -> service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS)));
        assertEquals(409, reused.status());
        assertEquals("INVITATION_REUSED", reused.code());
    }

    @Test
    void expiredInvitationIsRejected() {
        Invitation invitation = issue(staffA, CHART_A, FACTS, 60L);
        clock.advanceSeconds(61);
        ApiException expired = assertThrows(ApiException.class,
                () -> service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS)));
        assertEquals(409, expired.status());
        assertEquals("INVITATION_EXPIRED", expired.code());
        assertTrue(store.accountBySubject(ORG_A, SUBJECT_A).isEmpty());
    }

    @Test
    void repeatedFailedEnrollmentFactsLockTheInvitation() {
        Invitation invitation = issue(staffA, CHART_A, FACTS);
        for (int attempt = 0; attempt < PatientAccessService.MAX_FAILURES; attempt++) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> service.enroll(patientA, new EnrollRequest(invitation.code(), "wrong-facts")));
            assertEquals(401, failure.status());
        }
        ApiException locked = assertThrows(ApiException.class,
                () -> service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS)));
        assertEquals(423, locked.status());
        assertTrue(store.accountBySubject(ORG_A, SUBJECT_A).isEmpty());
    }

    @Test
    void issueInvitationValidatesInputAndAuthorization() {
        ApiException denied = assertThrows(ApiException.class, () -> service.issueInvitation(clinicianA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), FACTS, null)));
        assertEquals(403, denied.status());

        store.seedChart(ORG_A, CHART_A);
        assertApi(422, "INVALID_CHARTID", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest("bad", new RelationshipRef("enc", true), FACTS, null)));
        assertApi(422, "INVALID_FACTDIGEST", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), null, null)));
        assertApi(422, "INVALID_TTL", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), FACTS, 10L)));
        assertApi(422, "INVALID_TTL", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), FACTS, 100_000L)));
        assertApi(422, "UNVERIFIED_RELATIONSHIP", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, null, FACTS, null)));
        assertApi(422, "UNVERIFIED_RELATIONSHIP", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", false), FACTS, null)));
        assertApi(422, "UNVERIFIED_RELATIONSHIP", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef(" ", true), FACTS, null)));
        assertApi(404, "NOT_FOUND", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(OTHER_CHART, new RelationshipRef("enc", true), FACTS, null)));

        Invitation explicit = service.issueInvitation(staffA,
                new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), FACTS, 120L));
        assertEquals(clock.instant().plusSeconds(120), explicit.expiresAt());
    }

    @Test
    void enrollValidatesInput() {
        Invitation invitation = issue(staffA, CHART_A, FACTS);
        assertApi(403, "FORBIDDEN", () -> service.enroll(clinicianA, new EnrollRequest(invitation.code(), FACTS)));
        assertApi(422, "INVALID_CODE", () -> service.enroll(patientA, new EnrollRequest(" ", FACTS)));
        assertApi(422, "INVALID_FACTDIGEST", () -> service.enroll(patientA,
                new EnrollRequest(invitation.code(), null)));
    }

    // --- recovery and lockout ----------------------------------------------------

    @Test
    void recoveryRequiresSingleUseCodeAndReverification() {
        PatientAccount account = enrollAccount();
        var recoveryCode = service.issueRecovery(privacyA, account.id());
        assertEquals("recovery", recoveryCode.purpose());

        PatientAccount restored = service.recover(patientA,
                new RecoverRequest(account.id(), recoveryCode.token(), FACTS));
        assertEquals(AccountState.ACTIVE, restored.state());
        assertEquals(0, restored.failedAttempts());
        assertTrue(store.token(ORG_A, recoveryCode.token()).orElseThrow().used());

        ApiException reused = assertThrows(ApiException.class, () -> service.recover(patientA,
                new RecoverRequest(account.id(), recoveryCode.token(), FACTS)));
        assertEquals(401, reused.status());
        assertEquals("STALE_REAUTH", reused.code());
    }

    @Test
    void recoveryFailureLocksAccountAndReviewClearsIt() {
        PatientAccount account = enrollAccount();
        for (int attempt = 0; attempt < PatientAccessService.MAX_FAILURES; attempt++) {
            String token = service.issueRecovery(privacyA, account.id()).token();
            ApiException failure = assertThrows(ApiException.class, () -> service.recover(patientA,
                    new RecoverRequest(account.id(), token, "wrong-facts")));
            assertEquals(401, failure.status());
        }
        PatientAccount locked = store.account(ORG_A, account.id()).orElseThrow();
        assertEquals(AccountState.LOCKED, locked.state());
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.ACCOUNT_LOCKED));

        ApiException stillLocked = assertThrows(ApiException.class, () -> service.issueRecovery(privacyA, account.id()));
        assertEquals(423, stillLocked.status());

        PatientAccount unlocked = service.unlock(adminA, account.id());
        assertEquals(AccountState.ACTIVE, unlocked.state());
        assertEquals(0, unlocked.failedAttempts());
    }

    @Test
    void patientCannotRecoverAnotherPatientsAccountEvenWithAValidCode() {
        PatientAccount ownAccount = enrollAccount();
        PatientAccount peer = enrollPeerAccount();
        assertNotEquals(ownAccount.id(), peer.id());

        // The code is bound to patientA's subject. Supplying a peer account id
        // must still be denied: role and token checks alone are not object
        // authorization (PRD-03 self-access, IAM-01 domain authorization).
        var ownCode = service.issueRecovery(privacyA, ownAccount.id());
        assertApi(404, "NOT_FOUND", () -> service.recover(patientA,
                new RecoverRequest(peer.id(), ownCode.token(), FACTS)));
        assertFalse(store.token(ORG_A, ownCode.token()).orElseThrow().used());
        assertEquals(0, store.account(ORG_A, peer.id()).orElseThrow().failedAttempts());
        assertNotOwnerAudited();

        // Positive control: the same code still restores the owner's account.
        assertEquals(AccountState.ACTIVE, service.recover(patientA,
                new RecoverRequest(ownAccount.id(), ownCode.token(), FACTS)).state());
        assertTrue(store.token(ORG_A, ownCode.token()).orElseThrow().used());
    }

    @Test
    void patientCannotApplySensitiveChangeOrCheckAccessOnAnotherPatientsAccount() {
        PatientAccount ownAccount = enrollAccount();
        PatientAccount peer = enrollPeerAccount();

        // patientA holds valid step-up tokens for their own account, but the
        // caller-supplied accountId points at a peer in the same tenant.
        assertApi(404, "NOT_FOUND", () -> service.sensitiveChange(patientA, new SensitiveChangeRequest(
                peer.id(), SensitiveAction.UNLINK_CHART,
                reauthToken(SensitiveAction.UNLINK_CHART.wire()), CHART_B)));
        assertEquals(Set.of(CHART_B), store.account(ORG_A, peer.id()).orElseThrow().linkedChartIds());

        assertApi(404, "NOT_FOUND", () -> service.sensitiveChange(patientA, new SensitiveChangeRequest(
                peer.id(), SensitiveAction.CHANGE_CONTACT,
                reauthToken(SensitiveAction.CHANGE_CONTACT.wire()), CONTACT)));
        assertEquals(peer.contactDigest(), store.account(ORG_A, peer.id()).orElseThrow().contactDigest());

        assertApi(404, "NOT_FOUND", () -> service.checkChartAccess(patientA, peer.id(), CHART_B));
        assertEquals(3, store.audit(ORG_A).stream()
                .filter(event -> event.eventType() == EventType.ACCESS_DENIED
                        && "not_owner".equals(event.detail()))
                .count());
        // Positive control: the owner can still unlink their own chart after step-up.
        assertEquals(Set.of(), service.sensitiveChange(patientA, new SensitiveChangeRequest(
                ownAccount.id(), SensitiveAction.UNLINK_CHART,
                reauthToken(SensitiveAction.UNLINK_CHART.wire()), CHART_A)).linkedChartIds());
    }

    @Test
    void recoveryRejectsForeignStaleAndMismatchedTokens() {
        PatientAccount account = enrollAccount();
        var recovery = service.issueRecovery(privacyA, account.id());

        assertApi(404, "NOT_FOUND", () -> service.recover(patientB,
                new RecoverRequest(account.id(), recovery.token(), FACTS)));
        assertApi(404, "NOT_FOUND", () -> service.recover(patientA,
                new RecoverRequest(account.id(), "missing", FACTS)));
        assertApi(401, "STALE_REAUTH", () -> service.recover(patientA,
                new RecoverRequest(account.id(), reauthToken(SensitiveAction.UNLINK_CHART.wire()), FACTS)));

        clock.advanceSeconds(901);
        ApiException expired = assertThrows(ApiException.class, () -> service.recover(patientA,
                new RecoverRequest(account.id(), recovery.token(), FACTS)));
        assertEquals(401, expired.status());
    }

    @Test
    void recoveryValidatesInputAndAuthorization() {
        PatientAccount account = enrollAccount();
        assertApi(403, "FORBIDDEN", () -> service.issueRecovery(clinicianA, account.id()));
        assertApi(422, "INVALID_ACCOUNTID", () -> service.issueRecovery(privacyA, "bad"));
        assertApi(404, "NOT_FOUND", () -> service.issueRecovery(privacyA, OTHER_CHART));
        assertApi(403, "FORBIDDEN", () -> service.recover(clinicianA,
                new RecoverRequest(account.id(), "token", FACTS)));
        assertApi(422, "INVALID_ACCOUNTID", () -> service.recover(patientA,
                new RecoverRequest("bad", "token", FACTS)));
        assertApi(422, "INVALID_CODE", () -> service.recover(patientA,
                new RecoverRequest(account.id(), " ", FACTS)));
        assertApi(404, "NOT_FOUND", () -> service.unlock(adminA, OTHER_CHART));
        assertApi(403, "FORBIDDEN", () -> service.unlock(patientA, account.id()));
        assertApi(422, "INVALID_ACCOUNTID", () -> service.unlock(adminA, "bad"));
    }

    // --- step-up sensitive changes ------------------------------------------------

    @Test
    void unlinkEndsFutureAccessButOtherVerifiedLinksSurvive() {
        PatientAccount account = enrollAccount();
        store.seedChart(ORG_A, OTHER_CHART);
        PatientAccount withTwoLinks = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                Set.of(CHART_A, OTHER_CHART), account.factDigest(), account.state(), 0, account.contactDigest(),
                account.createdAt());
        store.putAccount(ORG_A, withTwoLinks);

        String token = reauthToken(SensitiveAction.UNLINK_CHART.wire());
        PatientAccount unlinked = service.sensitiveChange(patientA, new SensitiveChangeRequest(
                account.id(), SensitiveAction.UNLINK_CHART, token, CHART_A));
        assertEquals(Set.of(OTHER_CHART), unlinked.linkedChartIds());

        assertFalse(service.checkChartAccess(patientA, account.id(), CHART_A).allowed());
        assertTrue(service.checkChartAccess(patientA, account.id(), OTHER_CHART).allowed());
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.ACCESS_DENIED));
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.CHART_UNLINKED));
    }

    @Test
    void unlinkingAnAbsentLinkIsANoOpAndContactChangeRequiresDigest() {
        PatientAccount account = enrollAccount();
        PatientAccount unchanged = service.sensitiveChange(patientA, new SensitiveChangeRequest(
                account.id(), SensitiveAction.UNLINK_CHART,
                reauthToken(SensitiveAction.UNLINK_CHART.wire()), OTHER_CHART));
        assertEquals(Set.of(CHART_A), unchanged.linkedChartIds());

        PatientAccount withContact = service.sensitiveChange(patientA, new SensitiveChangeRequest(
                account.id(), SensitiveAction.CHANGE_CONTACT,
                reauthToken(SensitiveAction.CHANGE_CONTACT.wire()), CONTACT));
        assertNotNull(withContact.contactDigest());
    }

    @Test
    void sensitiveChangeValidatesStepUpAndInputs() {
        PatientAccount account = enrollAccount();
        assertApi(422, "INVALID_ACTION", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), null, "token", CONTACT)));
        assertApi(403, "FORBIDDEN", () -> service.sensitiveChange(clinicianA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT, "token", CONTACT)));
        assertApi(404, "NOT_FOUND", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(OTHER_CHART, SensitiveAction.CHANGE_CONTACT, "token", CONTACT)));
        assertApi(422, "INVALID_ACCOUNTID", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest("bad", SensitiveAction.CHANGE_CONTACT, "token", CONTACT)));
        assertApi(401, "STALE_REAUTH", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT, "missing", CONTACT)));
        assertApi(401, "STALE_REAUTH", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT,
                        reauthToken(SensitiveAction.UNLINK_CHART.wire()), CONTACT)));
        assertApi(422, "INVALID_CHARTID", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.UNLINK_CHART,
                        reauthToken(SensitiveAction.UNLINK_CHART.wire()), "bad")));
        assertApi(422, "INVALID_CONTACTDIGEST", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT,
                        reauthToken(SensitiveAction.CHANGE_CONTACT.wire()), " ")));

        String wrongSubject = service.issueReauth(
                PatientAccessActor.of(ORG_A, "other-patient", EnrollmentActions.PATIENT),
                SensitiveAction.CHANGE_CONTACT.wire()).token();
        assertApi(401, "STALE_REAUTH", () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT, wrongSubject, CONTACT)));

        String token = reauthToken(SensitiveAction.CHANGE_CONTACT.wire());
        clock.advanceSeconds(301);
        ApiException expired = assertThrows(ApiException.class, () -> service.sensitiveChange(patientA,
                new SensitiveChangeRequest(account.id(), SensitiveAction.CHANGE_CONTACT, token, CONTACT)));
        assertEquals(401, expired.status());
    }

    @Test
    void issueReauthValidatesInputAndAuthorization() {
        assertApi(403, "FORBIDDEN", () -> service.issueReauth(clinicianA, "unlink_chart"));
        assertApi(422, "INVALID_PURPOSE", () -> service.issueReauth(patientA, " "));
        assertNotNull(service.issueReauth(patientA, "unlink_chart").token());
    }

    @Test
    void staffContactChangeNeverChangesOwnership() {
        PatientAccount account = enrollAccount();
        PatientAccount changed = service.changeStaffContact(staffA,
                new ChangeContactRequest(account.id(), CONTACT + "-new"));
        assertEquals(account.id(), changed.id());
        assertEquals(account.subject(), changed.subject());
        assertEquals(account.organizationId(), changed.organizationId());
        assertEquals(account.linkedChartIds(), changed.linkedChartIds());
        assertEquals(account.factDigest(), changed.factDigest());
        assertNotEquals(account.contactDigest(), changed.contactDigest());
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.CONTACT_CHANGED));

        assertApi(403, "FORBIDDEN", () -> service.changeStaffContact(clinicianA,
                new ChangeContactRequest(account.id(), CONTACT)));
        assertApi(404, "NOT_FOUND", () -> service.changeStaffContact(staffA,
                new ChangeContactRequest(OTHER_CHART, CONTACT)));
        assertApi(422, "INVALID_CONTACTDIGEST", () -> service.changeStaffContact(staffA,
                new ChangeContactRequest(account.id(), " ")));
    }

    // --- support channel and reads ------------------------------------------------

    @Test
    void supportCaseLifecycleIsAuditedAndGeneric() {
        SupportCase opened = service.openCase(patientA,
                new OpenCaseRequest(SUBJECT_A, SupportKind.IDENTITY_DISPUTE, "I cannot access my chart"));
        assertEquals(CaseState.OPEN, opened.state());
        assertEquals(SupportKind.IDENTITY_DISPUTE, opened.kind());

        SupportCase assigned = service.assignCase(supportA, opened.id(), "owner-1");
        assertEquals(CaseState.IN_REVIEW, assigned.state());
        assertEquals("owner-1", assigned.assignee());

        SupportCase resolved = service.resolveCase(privacyA, opened.id(), "reviewed and closed");
        assertEquals(CaseState.RESOLVED, resolved.state());
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.CASE_OPENED));
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.CASE_ASSIGNED));
        assertTrue(store.audit(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.CASE_RESOLVED));

        List<GenericNotificationView> views = store.notifications(ORG_A).stream()
                .map(notification -> new GenericNotificationView(notification.eventType(), notification.subject()))
                .toList();
        assertEquals(1, views.size());
        assertTrue(views.stream().allMatch(view -> view.eventType.startsWith("support_case")));
    }

    private record GenericNotificationView(String eventType, String subject) {
    }

    @Test
    void supportCaseValidatesStatesAndInputs() {
        SupportCase opened = service.openCase(patientA,
                new OpenCaseRequest(SUBJECT_A, SupportKind.ACCESSIBILITY, "Please provide large print"));
        assertApi(403, "FORBIDDEN", () -> service.openCase(clinicianA,
                new OpenCaseRequest(SUBJECT_A, SupportKind.RECORD_ACCESS, "a valid summary")));
        assertApi(422, "INVALID_KIND", () -> service.openCase(patientA,
                new OpenCaseRequest(SUBJECT_A, null, "a valid summary")));
        assertApi(422, "INVALID_SUMMARY", () -> service.openCase(patientA,
                new OpenCaseRequest(SUBJECT_A, SupportKind.RECORD_ACCESS, "x")));
        assertApi(422, "INVALID_PATIENTID", () -> service.openCase(patientA,
                new OpenCaseRequest(" ", SupportKind.RECORD_ACCESS, "a valid summary")));
        assertApi(403, "FORBIDDEN", () -> service.assignCase(patientA, opened.id(), "owner"));
        assertApi(422, "INVALID_CASEID", () -> service.assignCase(supportA, "bad", "owner"));
        assertApi(404, "NOT_FOUND", () -> service.assignCase(supportA, OTHER_CHART, "owner"));
        assertApi(409, "INVALID_STATE", () -> service.resolveCase(privacyA, opened.id(), "too early"));
        assertApi(403, "FORBIDDEN", () -> service.resolveCase(patientA, opened.id(), "note"));
        assertApi(422, "INVALID_CASEID", () -> service.resolveCase(privacyA, "bad", "note"));
        assertApi(404, "NOT_FOUND", () -> service.resolveCase(privacyA, OTHER_CHART, "note"));
        service.assignCase(supportA, opened.id(), "owner");
        assertApi(409, "INVALID_STATE", () -> service.assignCase(supportA, opened.id(), "owner"));
        assertApi(422, "INVALID_NOTE", () -> service.resolveCase(privacyA, opened.id(), " "));
        service.resolveCase(privacyA, opened.id(), "valid resolution note");
        assertApi(409, "INVALID_STATE", () -> service.resolveCase(privacyA, opened.id(), "again"));
    }

    @Test
    void readsAreRoleScopedAndTenantIsolated() {
        PatientAccount account = enrollAccount();
        service.openCase(patientA, new OpenCaseRequest(SUBJECT_A, SupportKind.RECORD_ACCESS, "copy of record"));

        AccountView view = service.accountView(privacyA, account.id());
        assertEquals(account.id(), view.account().id());
        assertEquals(1, view.cases().size());
        assertEquals(3, view.events().size());
        assertThrows(UnsupportedOperationException.class, () -> view.cases().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.events().clear());

        assertApi(403, "FORBIDDEN", () -> service.accountView(patientA, account.id()));
        assertApi(404, "NOT_FOUND", () -> service.accountView(privacyA, OTHER_CHART));
        assertApi(403, "FORBIDDEN", () -> service.audit(patientA));
        assertApi(403, "FORBIDDEN", () -> service.notifications(patientA));
        // Two successful writes plus one DENIED audit for each of the three failed reads.
        assertEquals(6, service.audit(privacyA).size());
        assertEquals(2, service.notifications(supportA).size());
    }

    @Test
    void chartAccessDeniesLockedAccountsAndCrossTenantIds() {
        PatientAccount account = enrollAccount();
        assertTrue(service.checkChartAccess(patientA, account.id(), CHART_A).allowed());
        assertApi(403, "FORBIDDEN", () -> service.checkChartAccess(clinicianA, account.id(), CHART_A));
        // A peer tenant with the same local identifier cannot even learn that the account exists.
        assertApi(404, "NOT_FOUND", () -> service.checkChartAccess(patientB, account.id(), CHART_A));
        assertApi(404, "NOT_FOUND", () -> service.checkChartAccess(patientA, OTHER_CHART, CHART_A));
        assertApi(422, "INVALID_CHARTID", () -> service.checkChartAccess(patientA, account.id(), "bad"));

        store.seedChart(ORG_B, CHART_B);
        Invitation crossTenant = service.issueInvitation(staffB,
                new IssueInvitationRequest(CHART_B, new RelationshipRef("enc-b", true), FACTS, null));
        assertThrows(ApiException.class,
                () -> service.enroll(patientA, new EnrollRequest(crossTenant.code(), FACTS)));
    }

    @Test
    void nullAndOversizedInputsAreRejected() {
        String tooLong = "x".repeat(501);
        assertApi(422, "INVALID_CHARTID", () -> service.issueInvitation(staffA,
                new IssueInvitationRequest(null, new RelationshipRef("enc", true), FACTS, null)));
        assertApi(422, "INVALID_CODE", () -> service.enroll(patientA, new EnrollRequest(tooLong, FACTS)));
        assertApi(422, "INVALID_SUMMARY", () -> service.openCase(patientA,
                new OpenCaseRequest(SUBJECT_A, SupportKind.RECORD_ACCESS, tooLong)));
    }

    @Test
    void lockedAccountDeniesChartAccess() {
        PatientAccount account = enrollAccount();
        store.putAccount(ORG_A, new PatientAccount(account.id(), account.organizationId(), account.subject(),
                account.linkedChartIds(), account.factDigest(), AccountState.LOCKED, 5,
                account.contactDigest(), account.createdAt()));
        assertFalse(service.checkChartAccess(patientA, account.id(), CHART_A).allowed());
    }

    @Test
    void accountBySubjectFilterIsTenantScoped() {
        PatientAccount account = enrollAccount();
        store.seedChart(ORG_B, CHART_B);
        Invitation other = service.issueInvitation(staffB,
                new IssueInvitationRequest(CHART_B, new RelationshipRef("enc-b", true), FACTS, null));
        service.enroll(patientB, new EnrollRequest(other.code(), FACTS));

        assertTrue(store.accountBySubject(ORG_A, SUBJECT_A).isPresent());
        assertTrue(store.accountBySubject(ORG_B, SUBJECT_B).isPresent());
        assertTrue(store.accountBySubject(ORG_B, SUBJECT_A).isEmpty());
        assertTrue(store.accountBySubject(ORG_A, SUBJECT_B).isEmpty());
        assertTrue(store.accountBySubject(ORG_A, "unknown-subject").isEmpty());
        assertEquals(account.id(), store.account(ORG_A, account.id()).orElseThrow().id());
    }

    @Test
    void unavailableDigestFactoryFailsClosed() {
        store.seedChart(ORG_A, CHART_A);
        PatientAccessService failing = new PatientAccessService(store, actions, clock,
                () -> {
                    throw new NoSuchAlgorithmException("synthetic failure");
                });
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> failing.issueInvitation(
                staffA, new IssueInvitationRequest(CHART_A, new RelationshipRef("enc", true), FACTS, null)));
        assertTrue(exception.getMessage().contains("SHA-256"));
    }

    // --- helpers -----------------------------------------------------------------

    private PatientAccount enrollAccount() {
        Invitation invitation = issue(staffA, CHART_A, FACTS);
        return service.enroll(patientA, new EnrollRequest(invitation.code(), FACTS));
    }

    /** A second verified account inside ORG_A with the same synthetic facts. */
    private PatientAccount enrollPeerAccount() {
        Invitation invitation = issue(staffA, CHART_B, FACTS);
        return service.enroll(peerActor(), new EnrollRequest(invitation.code(), FACTS));
    }

    private static PatientAccessActor peerActor() {
        return PatientAccessActor.of(ORG_A, SUBJECT_PEER, EnrollmentActions.PATIENT);
    }

    private void assertNotOwnerAudited() {
        assertTrue(store.audit(ORG_A).stream()
                .anyMatch(event -> event.eventType() == EventType.ACCESS_DENIED
                        && "not_owner".equals(event.detail())));
    }

    private Invitation issue(PatientAccessActor staff, String chart, String facts) {
        return issue(staff, chart, facts, null);
    }

    private Invitation issue(PatientAccessActor staff, String chart, String facts, Long ttl) {
        store.seedChart(staff.organizationId(), chart);
        return service.issueInvitation(staff,
                new IssueInvitationRequest(chart, new RelationshipRef("enc-1", true), facts, ttl));
    }

    private String reauthToken(String purpose) {
        return service.issueReauth(patientA, purpose).token();
    }

    private static void assertApi(int status, String code, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status, exception.status());
        assertEquals(code, exception.code());
    }

    /** Deterministic clock so expiry and lockout windows are reproducible (test only). */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
