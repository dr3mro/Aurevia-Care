package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.InMemoryDecisionSink;
import com.aurevia.observability.CorrelationIdFilter;
import com.aurevia.observability.RequestLogCapture;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.CommandAuthorizationInterceptor;
import com.aurevia.web.TenantContextInterceptor;

/**
 * OPS-02 HTTP gate: one interceptor resolves a POST to a catalog command, records its decision
 * through the injected sink and maps the gate's machine-readable failures to the shared error body.
 * All subjects, tenants and command payloads here are synthetic.
 */
@ExtendWith(OutputCaptureExtension.class)
class CommandAuthorizationInterceptorHttpTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "admin-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

    /** Minimal handlers: one catalogued POST, one unmapped POST and one ordinary GET. */
    @RestController
    static class ProbeController {

        /** @return a fixed 201 body; the probe only observes interceptor decisions */
        @PostMapping("/api/v1/facilities")
        ResponseEntity<String> create(@RequestBody(required = false) String body) {
            return ResponseEntity.status(201).body("created");
        }

        /** @return a fixed 201 body for a route that has no catalog entry */
        @PostMapping("/api/v1/unknown-command")
        ResponseEntity<String> unknown(@RequestBody(required = false) String body) {
            return ResponseEntity.status(201).body("created");
        }

        /** @return a fixed body; reads are never gated by the command interceptor */
        @GetMapping("/api/v1/facilities")
        String list() {
            return "ok";
        }
    }

    private static final class Resolver implements MembershipResolver {

        private final List<String> roles;
        private final boolean active;

        Resolver(String role) {
            this(List.of(role), true);
        }

        Resolver(List<String> roles, boolean active) {
            this.roles = roles;
            this.active = active;
        }

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(roles, active);
        }
    }

    private static CommandAuthorizationInterceptor interceptor(MembershipResolver resolver, DecisionSink sink) {
        CommandCatalog catalog = CommandCatalog.synthetic();
        CommandAuthorization gate = CommandAuthorization.synthetic(catalog, resolver, sink, CLOCK);
        return new CommandAuthorizationInterceptor(catalog, gate);
    }

    private static MockMvc mvc(CommandAuthorizationInterceptor gate, boolean bindTenant) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        var builder = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler());
        if (bindTenant) {
            builder.addInterceptors(new TenantContextInterceptor(environment), gate);
        } else {
            builder.addInterceptors(gate);
        }
        return builder.build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postCommand(
            String path, String organization, String subject, String correlation) {
        var request = post(path).contentType("application/json").content("{}");
        if (organization != null) {
            request.header("X-Active-Organization", organization);
        }
        if (subject != null) {
            request.header("X-Synthetic-Subject", subject);
        }
        if (correlation != null) {
            request.header("X-Correlation-Id", correlation);
        }
        return request;
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void allowedCommandReachesTheHandlerAndRecordsAnAllowDecision() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("tenant_admin"), sink), true);

        mvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-allow"))
                .andExpect(status().isCreated());

        assertEquals(1, sink.decisions().size());
        Decision decision = sink.decisions().get(0);
        assertTrue(decision.allowed());
        assertEquals("ALLOWED", decision.reasonCode());
        assertEquals("FACILITY_WRITE", decision.action());
        assertEquals("createFacility", decision.commandId());
        assertEquals(ORG_A, decision.organizationId());
        assertEquals(SUBJECT, decision.subject());
        assertEquals("corr-allow", decision.correlationId());
    }

    @Test
    void oneCorrelationIdJoinsResponseLedgerAndRequestLogWithoutAHeader(CapturedOutput output)
            throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addFilters(new CorrelationIdFilter(CLOCK))
                .addInterceptors(new TenantContextInterceptor(environment),
                        interceptor(new Resolver("tenant_admin"), sink))
                .build();

        String echoed = mvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, null))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getHeader("X-Correlation-Id");

        assertNotNull(echoed);
        assertEquals(1, sink.decisions().size());
        assertEquals(echoed, sink.decisions().get(0).correlationId());
        assertEquals(echoed, RequestLogCapture.requestLog(output).get("correlationId").asString());
    }

    @Test
    void forbiddenRoleRecordsADenyAndLeavesEnforcementToTheOwningService() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("reception"), sink), true);

        // The gate's durable deny is recorded, but the probe (standing in for the owning
        // service) answers with its own contract. An early gate error here would break the
        // service's error body, application audit and ARC-04 concealment semantics. The
        // real 403 and application audit are proven by
        // CommandAuthorizationWiringHttpTest.forbiddenRoleIsDeniedAndAppendsADenyRow;
        // this harness only isolates the interceptor's decision-ledger contract.
        mvc.perform(postCommand("/api/v1/facilities", ORG_A, "reception-a", "corr-deny"))
                .andExpect(status().isCreated());

        assertEquals(1, sink.decisions().size());
        Decision decision = sink.decisions().get(0);
        assertFalse(decision.allowed());
        assertEquals("COMMAND_FORBIDDEN", decision.reasonCode());
        assertEquals(List.of("reception"), decision.rolesUsed());
        assertEquals("corr-deny", decision.correlationId());
    }

    @Test
    void unmappedCommandFailsClosedAndIsRecorded() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("tenant_admin"), sink), true);

        mvc.perform(postCommand("/api/v1/unknown-command", ORG_A, SUBJECT, "corr-unknown"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_COMMAND"));

        Decision decision = sink.decisions().get(0);
        assertFalse(decision.allowed());
        assertEquals("UNKNOWN_COMMAND", decision.reasonCode());
    }

    @Test
    void absentMatchedPatternFailsClosedWithoutTrustingTheRawUri() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorizationInterceptor gate = interceptor(new Resolver("tenant_admin"), sink);
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, TenantContext.PURPOSE, "corr-direct"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/facilities");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiException denial = assertThrows(ApiException.class,
                () -> gate.preHandle(request, response, mock(HandlerMethod.class)));

        assertEquals(422, denial.status());
        assertEquals("UNKNOWN_COMMAND", denial.code());
        assertFalse(sink.decisions().get(0).allowed());
    }

    @Test
    void auditFailureDeniesAndNeverEchoesSinkDetail() throws Exception {
        DecisionSink auditUnavailable = decision -> {
            throw new ApiException(403, "AUDIT_UNAVAILABLE", "SECRET_SQL_DETAIL authorization_decision");
        };
        MockMvc auditMvc = mvc(interceptor(new Resolver("tenant_admin"), auditUnavailable), true);
        auditMvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("authorization decision could not be recorded"));

        DecisionSink broken = decision -> {
            throw new IllegalStateException("SECRET_SQL_DETAIL authorization_decision");
        };
        MockMvc brokenMvc = mvc(interceptor(new Resolver("tenant_admin"), broken), true);
        brokenMvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("authorization decision could not be recorded"));

        DecisionSink otherApiFailure = decision -> {
            throw new ApiException(500, "SECRET_CODE", "unavailable");
        };
        MockMvc otherMvc = mvc(interceptor(new Resolver("tenant_admin"), otherApiFailure), true);
        otherMvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-other"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_RECORDED"));
    }

    @Test
    void sameSubjectInTwoTenantsProducesTwoIndependentlyScopedDecisions() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("tenant_admin"), sink), true);

        mvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-org-a"))
                .andExpect(status().isCreated());
        mvc.perform(postCommand("/api/v1/facilities", ORG_B, SUBJECT, "corr-org-b"))
                .andExpect(status().isCreated());

        assertEquals(2, sink.decisions().size());
        assertEquals(ORG_A, sink.decisions().get(0).organizationId());
        assertEquals(ORG_B, sink.decisions().get(1).organizationId());
        assertEquals("corr-org-a", sink.decisions().get(0).correlationId());
        assertEquals("corr-org-b", sink.decisions().get(1).correlationId());
        assertTrue(sink.decisions().stream().allMatch(Decision::allowed));
    }

    @Test
    void tenantlessRequestIsDeniedAsUnauthenticatedWithoutAnAllow() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("tenant_admin"), sink), false);

        mvc.perform(postCommand("/api/v1/facilities", null, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertEquals(1, sink.decisions().size());
        assertFalse(sink.decisions().get(0).allowed());
        assertEquals("UNAUTHENTICATED", sink.decisions().get(0).reasonCode());
    }

    @Test
    void inactiveMembershipIsRecordedWithoutEvaluatingAnyPolicy() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver(List.of("tenant_admin"), false), sink), true);

        // A durable MEMBERSHIP_INACTIVE deny is written before the owning service decides.
        mvc.perform(postCommand("/api/v1/facilities", ORG_A, SUBJECT, "corr-inactive"))
                .andExpect(status().isCreated());

        assertEquals(1, sink.decisions().size());
        assertEquals("MEMBERSHIP_INACTIVE", sink.decisions().get(0).reasonCode());
        assertFalse(sink.decisions().get(0).allowed());
    }

    @Test
    void nonControllerHandlersOnCatalogPathsAreNotGated() {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorizationInterceptor gate = interceptor(new Resolver("reception"), sink);
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, TenantContext.PURPOSE, "corr-resource"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/facilities");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/facilities");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // A static-resource fallback handler shares the catalog path pattern but is not a
        // command; gating it would turn a native 404 into a spurious 422.
        assertTrue(gate.preHandle(request, response, new Object()));

        assertTrue(sink.decisions().isEmpty());
    }

    @Test
    void readsAreNotGatedByTheCommandInterceptor() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        MockMvc mvc = mvc(interceptor(new Resolver("tenant_admin"), sink), true);

        mvc.perform(get("/api/v1/facilities")
                        .header("X-Active-Organization", ORG_A)
                        .header("X-Synthetic-Subject", SUBJECT))
                .andExpect(status().isOk());

        assertTrue(sink.decisions().isEmpty());
    }

    @Test
    void collaboratorsAreRequired() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        CommandAuthorization gate = CommandAuthorization.synthetic(
                catalog, new Resolver("tenant_admin"), new InMemoryDecisionSink(), CLOCK);
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorizationInterceptor(null, gate));
        assertThrows(NullPointerException.class,
                () -> new CommandAuthorizationInterceptor(catalog, null));
    }
}
