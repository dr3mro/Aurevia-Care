package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.ActionRule;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.CommandDefinition;
import com.aurevia.api.authorization.InMemoryDecisionSink;

/**
 * IAM-01 clause 3 proof: the server-side command gate refuses a request unless
 * the subject has active membership in the selected organization, an active role
 * permitted by the policy that owns the command's action, and a decision from
 * that owning domain policy. Authority is never taken from the command body;
 * membership and roles are re-resolved on every call. Synthetic fixtures only.
 */
class DenyAccessWithoutActiveMembershipRequiredActionTest {

    private static final String ORG = "org-clause-three";
    private static final String SUBJECT = "subject-clause-three";
    private static final String INVITE_STAFF = "inviteStaff";
    private static final String STAFF_INVITE = "STAFF_INVITE";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
    private static final ServerIdentity IDENTITY = new ServerIdentity(ORG, SUBJECT, "corr-clause-three");

    /** Fresh membership fold with a fixed, synthetic role set and activation flag. */
    private static final class Resolver implements MembershipResolver {

        private final List<String> roles;
        private final boolean active;

        private Resolver(List<String> roles, boolean active) {
            this.roles = roles;
            this.active = active;
        }

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(roles, active);
        }
    }

    private static CommandRequest request(String commandId) {
        // A client may claim anything; the gate must ignore these inert fields.
        return new CommandRequest(commandId, "tenant_admin", List.of("STAFF_INVITE", "AUDIT_READ"));
    }

    private static CommandAuthorization realPolicies(Resolver resolver, InMemoryDecisionSink sink) {
        CommandCatalog catalog = CommandCatalog.synthetic();
        return CommandAuthorization.synthetic(catalog, resolver, sink, CLOCK);
    }

    @Test
    void allowsWhenMembershipActionPermissionAndOwningDomainPolicyHold() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = realPolicies(
                new Resolver(List.of("tenant_admin"), true), sink);

        Decision decision = authorization.authorize(request(INVITE_STAFF), IDENTITY);

        assertTrue(decision.allowed());
        assertEquals("ALLOWED", decision.reasonCode());
        assertEquals(STAFF_INVITE, decision.action());
        assertEquals(List.of("tenant_admin"), decision.rolesUsed());
        assertEquals(1, sink.decisions().size());
        assertEquals(decision, sink.decisions().get(0));
    }

    @Test
    void deniesWhenActiveMembershipIsMissingEvenForAnOtherwiseQualifiedRole() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = realPolicies(
                new Resolver(List.of("tenant_admin"), false), sink);

        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(request(INVITE_STAFF), IDENTITY));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("no active membership"));
        Decision recorded = sink.decisions().get(0);
        assertFalse(recorded.allowed());
        assertEquals("MEMBERSHIP_INACTIVE", recorded.reasonCode());
    }

    @Test
    void deniesWhenActiveRolesLackTheRequiredActionPermission() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        // Reception has active membership but STAFF_INVITE is tenant-admin only.
        CommandAuthorization authorization = realPolicies(
                new Resolver(List.of("reception"), true), sink);

        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(request(INVITE_STAFF), IDENTITY));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("command is not permitted"));
        Decision recorded = sink.decisions().get(0);
        assertFalse(recorded.allowed());
        assertEquals("COMMAND_FORBIDDEN", recorded.reasonCode());
        assertEquals(List.of("reception"), recorded.rolesUsed());
    }

    @Test
    void deniesWhenOnlyANonOwningDomainPolicyWouldAllowTheAction() {
        // One command whose action is owned by OWNING. A colliding action in
        // another domain policy must never widen access to the resource.
        CommandCatalog catalog = new CommandCatalog(List.of(
                new CommandDefinition("domainAction", "SHARED_ACTION", "POST",
                        "/api/v1/domain/action", "OWNING")));
        ActionRule owningPolicy = (action, role) -> false;
        ActionRule otherPolicy = (action, role) -> true;
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization = new CommandAuthorization(catalog,
                Map.of("OWNING", owningPolicy, "OTHER", otherPolicy),
                new Resolver(List.of("intruder"), true), sink, CLOCK);

        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(request("domainAction"), IDENTITY));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        Decision recorded = sink.decisions().get(0);
        assertFalse(recorded.allowed());
        assertEquals("COMMAND_FORBIDDEN", recorded.reasonCode());
        assertEquals("SHARED_ACTION", recorded.action());
    }
}
