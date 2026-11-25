package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.ActionRule;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;

/** PRD-06/IAM-01 server-side command authorization: fail closed, ignore client claims, own each policy. */
class CommandAuthorizationTest {

    private static final String ORG = "org-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    private static final List<String> ALL_ROLES = List.of(
            "tenant_admin", "reception", "clinician", "clinical_assistant", "lab_collector",
            "lab_verifier", "radiology_staff", "radiology_interpreter", "pt_clinician",
            "pharmacy_staff", "finance", "privacy_auditor", "patient");

    private static final class Resolver implements MembershipResolver {

        private List<String> roles = List.of();
        private boolean active = true;
        private boolean unavailable;
        private ServerIdentity last;

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            last = identity;
            return unavailable ? null : new MemberContext(roles, active);
        }
    }

    private static ServerIdentity id(String organization, String subject) {
        return new ServerIdentity(organization, subject, "corr-1");
    }

    private static CommandRequest request(String command) {
        return new CommandRequest(command, null, null);
    }

    @Test
    void definitionRejectsAbsentUnsafeAndNonPostCommands() {
        assertEquals("ok", CommandDefinition.token(" ok ", "field"));
        for (String bad : new String[] {null, "", "   ", "bad value", "quote\"x"}) {
            ApiException failure = assertThrows(ApiException.class, () -> CommandDefinition.token(bad, "field"));
            assertEquals(422, failure.status());
            assertEquals("INVALID_COMMAND_DEFINITION", failure.code());
        }
        CommandDefinition definition = new CommandDefinition("id", "ACTION", "POST", "/api/v1/{id}", "Policy");
        assertEquals("id", definition.id());
        assertEquals("ACTION", definition.action());
        assertEquals("POST", definition.method());
        assertEquals("/api/v1/{id}", definition.path());
        assertEquals("Policy", definition.policy());
        ApiException failure = assertThrows(ApiException.class,
                () -> new CommandDefinition("id", "ACTION", "GET", "/api/v1", "Policy"));
        assertTrue(failure.getMessage().contains("POST"));
    }

    @Test
    void definitionsRejectTokensThatCannotBeGeneratedSafely() {
        for (String badId : List.of("accept-order", "1accept", "/accept", "accept.order")) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> new CommandDefinition(badId, "ACTION", "POST", "/api/v1/x", "Policy"));
            assertEquals(422, failure.status());
            assertEquals("INVALID_COMMAND_DEFINITION", failure.code());
        }
        for (String badPath : List.of("api/v1/x", "/api/v1/x/{user-id}", "/api/v1/x/{body}",
                "/api/v1/x/{ctx}", "/api/v1/x/{ifMatch}", "/api/v1/x/{id}/{id}", "/api/v1/x/{id",
                "/api/v1/x/id}")) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> new CommandDefinition("okCommand", "ACTION", "POST", badPath, "Policy"));
            assertEquals(422, failure.status());
            assertEquals("INVALID_COMMAND_DEFINITION", failure.code());
        }
        assertEquals("/api/v1/x/{subject}", new CommandDefinition("okCommand", "ACTION", "POST",
                "/api/v1/x/{subject}", "Policy").path());
    }

    @Test
    void catalogRejectsDuplicateRoutes() {
        CommandDefinition first = new CommandDefinition("first", "ACTION", "POST", "/api/v1/same", "Policy");
        CommandDefinition second = new CommandDefinition("second", "ACTION", "POST", "/api/v1/same", "Policy");
        assertThrows(IllegalArgumentException.class, () -> new CommandCatalog(List.of(first, second)));
    }

    @Test
    void catalogIsImmutableUniqueAndUnknownCommandsFailClosed() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        assertEquals(24, catalog.commands().size());
        assertTrue(catalog.find("acceptOrder").isPresent());
        assertTrue(catalog.find("notACommand").isEmpty());
        assertEquals("EXCHANGE_ACCEPT", catalog.require("acceptOrder").action());
        assertTrue(catalog.actions().contains("RELEASE_AUTHORIZE"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.commands().add(
                new CommandDefinition("extra", "ACTION", "POST", "/api/v1/extra", "Policy")));
        ApiException failure = assertThrows(ApiException.class, () -> catalog.require("notACommand"));
        assertEquals(422, failure.status());
        assertEquals("UNKNOWN_COMMAND", failure.code());
        assertEquals(List.of(), new CommandCatalog(List.of()).actions());
        CommandDefinition duplicate = new CommandDefinition("acceptOrder", "ACTION", "POST", "/x", "Policy");
        assertThrows(IllegalArgumentException.class, () -> new CommandCatalog(List.of(duplicate, duplicate)));
    }

    @Test
    void everyCatalogCommandHasAtLeastOneAuthorizedRole() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        for (CommandDefinition definition : catalog.commands()) {
            boolean authorized = false;
            for (String role : ALL_ROLES) {
                Resolver resolver = new Resolver();
                resolver.roles = List.of(role);
                InMemoryDecisionSink sink = new InMemoryDecisionSink();
                CommandAuthorization authorization = CommandAuthorization.synthetic(catalog, resolver, sink, CLOCK);
                try {
                    Decision decision = authorization.authorize(request(definition.id()), id(ORG, "user-" + role));
                    assertTrue(decision.allowed());
                    authorized = true;
                    break;
                } catch (ApiException denied) {
                    assertEquals(403, denied.status());
                }
            }
            assertTrue(authorized, "no active role may perform " + definition.id());
        }
    }

    @Test
    void clientClaimedAuthorityNeverGrantsAPermission() {
        Resolver resolver = new Resolver();
        resolver.roles = List.of("reception");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        CommandRequest forged = new CommandRequest("createFacility", "tenant_admin", List.of("*", "FACILITY_WRITE"));
        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(forged, id(ORG, "reception-1")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        Decision decision = sink.decisions().get(0);
        assertEquals("COMMAND_FORBIDDEN", decision.reasonCode());
        assertEquals(List.of("reception"), decision.rolesUsed());
        assertFalse(decision.allowed());
    }

    @Test
    void allowedDecisionCarriesOps02FieldsAndSkipsDisallowedRoles() {
        Resolver resolver = new Resolver();
        resolver.roles = List.of("reception", "tenant_admin");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        Decision decision = authorization.authorize(request("createFacility"), id(ORG, "admin-1"));
        assertTrue(decision.allowed());
        assertEquals("ALLOWED", decision.reasonCode());
        assertEquals("FACILITY_WRITE", decision.action());
        assertEquals(ORG, decision.organizationId());
        assertEquals("admin-1", decision.subject());
        assertEquals("corr-1", decision.correlationId());
        assertEquals(Instant.parse("2026-11-19T12:00:00Z"), decision.occurredAt());
        assertEquals(List.of("reception", "tenant_admin"), decision.rolesUsed());
        assertEquals(List.of(decision), sink.decisions());
        assertEquals(ORG, resolver.last.organizationId());
    }

    @Test
    void membershipAndRolesAreReResolvedOnEveryCall() {
        Resolver resolver = new Resolver();
        resolver.roles = List.of("reception");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        assertTrue(authorization.authorize(request("verifyIdentity"), id(ORG, "reception-1")).allowed());

        resolver.active = false;
        ApiException inactive = assertThrows(ApiException.class,
                () -> authorization.authorize(request("verifyIdentity"), id(ORG, "reception-1")));
        Decision inactiveDecision = sink.decisions().get(sink.decisions().size() - 1);
        assertEquals("MEMBERSHIP_INACTIVE", inactiveDecision.reasonCode());
        assertEquals(List.of("reception"), inactiveDecision.rolesUsed());

        resolver.active = true;
        resolver.roles = List.of("lab_verifier");
        ApiException forbidden = assertThrows(ApiException.class,
                () -> authorization.authorize(request("verifyIdentity"), id(ORG, "verifier-1")));
        assertEquals("COMMAND_FORBIDDEN", sink.decisions().get(sink.decisions().size() - 1).reasonCode());
        assertEquals(403, inactive.status());
        assertEquals(403, forbidden.status());
    }

    @Test
    void unauthenticatedIdentitiesAreDeniedAndRecorded() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        ServerIdentity[] identities = {
                null,
                new ServerIdentity(ORG, null, "corr-1"),
                new ServerIdentity(ORG, "   ", "corr-1"),
                new ServerIdentity(null, "admin-1", "corr-1"),
                new ServerIdentity("  ", "admin-1", "corr-1"),
        };
        for (ServerIdentity candidate : identities) {
            Resolver resolver = new Resolver();
            InMemoryDecisionSink sink = new InMemoryDecisionSink();
            CommandAuthorization authorization = CommandAuthorization.synthetic(catalog, resolver, sink, CLOCK);
            ApiException denial = assertThrows(ApiException.class,
                    () -> authorization.authorize(request("createFacility"), candidate));
            assertEquals(401, denial.status());
            assertEquals("UNAUTHENTICATED", denial.code());
            Decision decision = sink.decisions().get(0);
            assertEquals("UNAUTHENTICATED", decision.reasonCode());
            assertEquals("", decision.action());
            if (candidate == null) {
                assertEquals("", decision.subject());
                assertEquals("", decision.organizationId());
                assertEquals("", decision.correlationId());
            }
        }
        Resolver resolver = new Resolver();
        resolver.roles = List.of("reception");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = CommandAuthorization.synthetic(catalog, resolver, sink, CLOCK);
        Decision noCorrelation = authorization.authorize(request("verifyIdentity"),
                new ServerIdentity(ORG, "reception-1", null));
        assertEquals("", noCorrelation.correlationId());
    }

    @Test
    void malformedAndUnknownCommandsAreRecordedDenials() {
        Resolver resolver = new Resolver();
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        for (CommandRequest malformed : new CommandRequest[] {
                null, new CommandRequest(null, null, null), new CommandRequest("  ", null, null)}) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> authorization.authorize(malformed, id(ORG, "admin-1")));
            assertEquals(422, failure.status());
            assertEquals("INVALID_COMMAND", failure.code());
            assertEquals("INVALID_COMMAND", sink.decisions().get(sink.decisions().size() - 1).reasonCode());
        }
        ApiException unknown = assertThrows(ApiException.class,
                () -> authorization.authorize(request("notACommand"), id(ORG, "admin-1")));
        assertEquals(422, unknown.status());
        assertEquals("UNKNOWN_COMMAND", unknown.code());
        assertEquals("UNKNOWN_COMMAND", sink.decisions().get(sink.decisions().size() - 1).reasonCode());
    }

    @Test
    void unavailableMembershipAndUnregisteredOwnerFailClosed() {
        Resolver resolver = new Resolver();
        resolver.unavailable = true;
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        ApiException failure = assertThrows(ApiException.class,
                () -> authorization.authorize(request("createFacility"), id(ORG, "admin-1")));
        assertEquals(403, failure.status());
        assertEquals("MEMBERSHIP_UNAVAILABLE", sink.decisions().get(0).reasonCode());

        CommandCatalog orphan = new CommandCatalog(List.of(
                new CommandDefinition("orphan", "ACTION", "POST", "/api/v1/orphan", "NotRegistered")));
        Resolver activeResolver = new Resolver();
        activeResolver.roles = List.of("tenant_admin");
        InMemoryDecisionSink orphanSink = new InMemoryDecisionSink();
        CommandAuthorization noOwner = CommandAuthorization.synthetic(orphan, activeResolver, orphanSink, CLOCK);
        ApiException orphanFailure = assertThrows(ApiException.class,
                () -> noOwner.authorize(request("orphan"), id(ORG, "admin-1")));
        assertEquals(403, orphanFailure.status());
        assertEquals("POLICY_UNAVAILABLE", orphanSink.decisions().get(0).reasonCode());
    }

    @Test
    void aCollidingActionInAnotherPolicyCannotWidenACommand() {
        CommandDefinition command = new CommandDefinition("checkIn", "APPOINTMENT_CHECK_IN", "POST",
                "/api/v1/checkin/{id}/complete", CommandCatalog.WORKLIST_POLICY);
        CommandCatalog catalog = new CommandCatalog(List.of(command));
        ActionRule strict = (action, role) -> "clinician".equals(role);
        ActionRule widened = (action, role) -> "tenant_admin".equals(role);
        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization widenedCatalog = new CommandAuthorization(catalog,
                Map.of(CommandCatalog.WORKLIST_POLICY, strict, CommandCatalog.ACTION_POLICY, widened),
                resolver, sink, CLOCK);
        assertEquals(403, assertThrows(ApiException.class,
                () -> widenedCatalog.authorize(request("checkIn"), id(ORG, "admin-1"))).status());
        assertEquals("COMMAND_FORBIDDEN", sink.decisions().get(0).reasonCode());

        InMemoryDecisionSink ownerSink = new InMemoryDecisionSink();
        CommandAuthorization ownerRules = new CommandAuthorization(catalog,
                Map.of(CommandCatalog.WORKLIST_POLICY, widened), resolver, ownerSink, CLOCK);
        assertTrue(ownerRules.authorize(request("checkIn"), id(ORG, "admin-1")).allowed());
    }

    @Test
    void inertClaimsAndDecisionValuesAreDefensivelyCopied() {
        CommandRequest nullClaims = new CommandRequest("createFacility", "tenant_admin", null);
        assertTrue(nullClaims.claimedPermissions().isEmpty());
        CommandRequest claims = new CommandRequest("createFacility", "tenant_admin", List.of("FACILITY_WRITE"));
        assertThrows(UnsupportedOperationException.class, () -> claims.claimedPermissions().add("x"));
        assertThrows(NullPointerException.class, () -> new MemberContext(null, true));
        Decision decision = new Decision("id", "ACTION", ORG, "admin-1", "corr-1", true,
                "ALLOWED", List.of("clinician"), CLOCK.instant());
        assertThrows(UnsupportedOperationException.class, () -> decision.rolesUsed().add("x"));
        assertThrows(NullPointerException.class, () -> new Decision("id", "ACTION", ORG, "admin-1",
                "corr-1", true, "ALLOWED", null, CLOCK.instant()));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorization(null, Map.of(), new Resolver(), new InMemoryDecisionSink(), CLOCK));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorization(CommandCatalog.synthetic(), null, new Resolver(),
                        new InMemoryDecisionSink(), CLOCK));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorization(CommandCatalog.synthetic(), Map.of(), null,
                        new InMemoryDecisionSink(), CLOCK));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorization(CommandCatalog.synthetic(), Map.of(), new Resolver(), null, CLOCK));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorization(CommandCatalog.synthetic(), Map.of(), new Resolver(),
                        new InMemoryDecisionSink(), null));
    }

    @Test
    void decisionLedgerCopiesClearsAndRejectsNull() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        assertThrows(NullPointerException.class, () -> sink.record(null));
        Decision decision = new Decision("id", "ACTION", ORG, "admin-1", "corr-1", true,
                "ALLOWED", List.of(), CLOCK.instant());
        sink.record(decision);
        assertEquals(1, sink.decisions().size());
        assertNotNull(sink.decisions().get(0));
        assertThrows(UnsupportedOperationException.class, () -> sink.decisions().clear());
        sink.clear();
        assertTrue(sink.decisions().isEmpty());
    }

    @Test
    void malformedInertClaimsCannotBypassDecisionRecording() {
        CommandRequest malformed = new CommandRequest("createFacility", "tenant_admin",
                Arrays.asList("FACILITY_WRITE", null));
        assertEquals(List.of("FACILITY_WRITE"), malformed.claimedPermissions());
        assertThrows(UnsupportedOperationException.class, () -> malformed.claimedPermissions().add("x"));
        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        Decision decision = authorization.authorize(malformed, id(ORG, "admin-1"));
        assertTrue(decision.allowed());
        assertEquals(1, sink.decisions().size());
        assertEquals("ALLOWED", sink.decisions().get(0).reasonCode());
    }

    @Test
    void collaboratorFailuresAreRecordedAndReturnedAsMachineReadableErrors() {
        MembershipResolver brokenResolver = identity -> {
            throw new IllegalStateException("membership store unavailable");
        };
        InMemoryDecisionSink resolverSink = new InMemoryDecisionSink();
        CommandAuthorization resolverFailure = CommandAuthorization.synthetic(
                CommandCatalog.synthetic(), brokenResolver, resolverSink, CLOCK);
        ApiException memberFailure = assertThrows(ApiException.class,
                () -> resolverFailure.authorize(request("createFacility"), id(ORG, "admin-1")));
        assertEquals(403, memberFailure.status());
        assertEquals("FORBIDDEN", memberFailure.code());
        assertEquals(1, resolverSink.decisions().size());
        assertEquals("MEMBERSHIP_UNAVAILABLE", resolverSink.decisions().get(0).reasonCode());

        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        InMemoryDecisionSink ruleSink = new InMemoryDecisionSink();
        ActionRule brokenRule = (action, role) -> {
            throw new IllegalStateException("policy evaluation unavailable");
        };
        CommandAuthorization ruleFailure = new CommandAuthorization(CommandCatalog.synthetic(),
                Map.of(CommandCatalog.ACTION_POLICY, brokenRule), resolver, ruleSink, CLOCK);
        ApiException decisionFailure = assertThrows(ApiException.class,
                () -> ruleFailure.authorize(request("createFacility"), id(ORG, "admin-1")));
        assertEquals(403, decisionFailure.status());
        assertEquals("FORBIDDEN", decisionFailure.code());
        assertEquals(1, ruleSink.decisions().size());
        assertEquals("POLICY_UNAVAILABLE", ruleSink.decisions().get(0).reasonCode());
    }

    @Test
    void untrustedCommandIdentifiersAreBoundedBeforeTheyAreAudited() {
        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);
        String oversized = "a".repeat(10_000) + "\nFORGED";
        ApiException failure = assertThrows(ApiException.class,
                () -> authorization.authorize(new CommandRequest(oversized, null, null), id(ORG, "admin-1")));
        assertEquals(422, failure.status());
        assertEquals("UNKNOWN_COMMAND", failure.code());
        assertEquals("a".repeat(128), sink.decisions().get(sink.decisions().size() - 1).commandId());

        ApiException control = assertThrows(ApiException.class,
                () -> authorization.authorize(new CommandRequest("bad\nid", null, null), id(ORG, "admin-1")));
        assertEquals(422, control.status());
        assertEquals("badid", sink.decisions().get(sink.decisions().size() - 1).commandId());
    }

    @Test
    void sinkFailureFailsClosedWithAMachineReadableError() {
        CommandAuthorization.DecisionSink broken = decision -> {
            throw new IllegalStateException("sink unavailable");
        };
        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, broken, CLOCK);
        ApiException failure = assertThrows(ApiException.class,
                () -> authorization.authorize(request("createFacility"), id(ORG, "admin-1")));
        assertEquals(500, failure.status());
        assertEquals("DECISION_NOT_RECORDED", failure.code());
    }

    @Test
    void auditUnavailableSinkFailureDeniesWithItsOwnCode() {
        CommandAuthorization.DecisionSink unavailable = decision -> {
            throw new ApiException(403, "AUDIT_UNAVAILABLE", "SECRET_SQL_TOKEN authorization_decision");
        };
        Resolver resolver = new Resolver();
        resolver.roles = List.of("tenant_admin");
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, unavailable, CLOCK);
        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(request("createFacility"), id(ORG, "admin-1")));
        assertEquals(403, denial.status());
        assertEquals("AUDIT_UNAVAILABLE", denial.code());
        assertEquals("authorization decision could not be recorded", denial.getMessage());
        assertFalse(denial.getMessage().contains("SECRET_SQL_TOKEN"));
    }

    @Test
    void otherSinkApiFailuresRemainMachineReadableServerErrors() {
        ApiException[] sinkFailures = {
                new ApiException(500, "OTHER_FAILURE", "unavailable"),
                new ApiException(403, "FORBIDDEN", "unavailable")
        };
        for (ApiException sinkFailure : sinkFailures) {
            Resolver resolver = new Resolver();
            resolver.roles = List.of("tenant_admin");
            CommandAuthorization authorization = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                    resolver, decision -> {
                        throw sinkFailure;
                    }, CLOCK);
            ApiException denial = assertThrows(ApiException.class,
                    () -> authorization.authorize(request("createFacility"), id(ORG, "admin-1")));
            assertEquals(500, denial.status());
            assertEquals("DECISION_NOT_RECORDED", denial.code());
        }
    }
}
