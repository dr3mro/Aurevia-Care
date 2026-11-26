package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.CommandAuthorizationInterceptor;
import com.aurevia.web.TenantContextInterceptor;

/**
 * HTTP proof that a delivered handler template resolves to one catalog command (ARC-03, ARC-04).
 *
 * <p>The probe controller stands in for the owning service; the interceptor and
 * authorizer are the real production classes. The route is registered on this
 * standalone MockMvc explicitly, so the proof is about command resolution and
 * decision recording, not about broadening live interceptor registration.
 * Tenants, subjects and payloads are synthetic.</p>
 */
class CommandCatalogRealRouteHttpTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "clinician-a";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

    /** Minimal handlers standing in for the delivered exchange controller and one near miss. */
    @RestController
    static class ProbeController {

        /** @return a fixed 201 body; the probe only observes interceptor decisions */
        @PostMapping("/api/v1/xch03/orders/{id}/accept")
        ResponseEntity<String> accept(@RequestBody(required = false) String body) {
            return ResponseEntity.status(201).body("accepted");
        }

        /** @return a fixed 201 body for a route with no command mapping */
        @PostMapping("/api/v1/xch03/orders/{id}/decline")
        ResponseEntity<String> decline(@RequestBody(required = false) String body) {
            return ResponseEntity.status(201).body("declined");
        }
    }

    private static final class Resolver implements MembershipResolver {

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(List.of("clinician"), true);
        }
    }

    private static MockMvc mvc(InMemoryDecisionSink sink) {
        CommandCatalog catalog = CommandCatalog.synthetic();
        CommandAuthorization gate = CommandAuthorization.synthetic(catalog, new Resolver(), sink, CLOCK);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment),
                        new CommandAuthorizationInterceptor(catalog, gate))
                .build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void deliveredRouteResolvesItsCommandAndRecordsTheDecision() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();

        mvc(sink).perform(post("/api/v1/xch03/orders/123/accept")
                        .contentType("application/json").content("{}")
                        .header("X-Active-Organization", ORG_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Correlation-Id", "corr-real-route"))
                .andExpect(status().isCreated());

        assertEquals(1, sink.decisions().size());
        Decision decision = sink.decisions().get(0);
        assertTrue(decision.allowed());
        assertEquals("acceptExchangeResponse", decision.commandId());
        assertEquals("EXCHANGE_ACCEPT", decision.action());
        assertEquals(ORG_A, decision.organizationId());
        assertEquals(SUBJECT, decision.subject());
        assertEquals("corr-real-route", decision.correlationId());
    }

    @Test
    void nearMissRouteFailsClosedWithoutATrustedCommand() throws Exception {
        InMemoryDecisionSink sink = new InMemoryDecisionSink();

        mvc(sink).perform(post("/api/v1/xch03/orders/123/decline")
                        .contentType("application/json").content("{}")
                        .header("X-Active-Organization", ORG_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Correlation-Id", "corr-near-miss"))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(1, sink.decisions().size());
        Decision decision = sink.decisions().get(0);
        assertEquals("UNKNOWN_COMMAND", decision.reasonCode());
        assertEquals("UNRESOLVED_COMMAND", decision.commandId());
    }
}
