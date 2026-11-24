package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

class TenantContextInterceptorTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private MockEnvironment environment;
    private TenantContextInterceptor interceptor;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        interceptor = new TenantContextInterceptor(environment);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MDC.remove("correlationId");
    }

    private MockHttpServletRequest request(String subject, String organization, String correlation) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (subject != null) {
            request.addHeader("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            request.addHeader("X-Active-Organization", organization);
        }
        if (correlation != null) {
            request.addHeader("X-Correlation-Id", correlation);
        }
        return request;
    }

    @Test
    void generatesAndEchoesCorrelationId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("admin", ORG, null), response, new Object()));
        assertNotNull(response.getHeader("X-Correlation-Id"));
        assertNotNull(TenantContext.current().correlationId());
        interceptor.afterCompletion(null, response, new Object(), null);
        assertNull(TenantContext.current());
    }

    @Test
    void publishesAndClearsCorrelationIdInMdc() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request("admin", ORG, "corr-mdc"), response, new Object());
        assertEquals("corr-mdc", MDC.get("correlationId"));
        interceptor.afterCompletion(null, response, new Object(), null);
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("admin", ORG, "  "), response, new Object()));
        assertNotNull(response.getHeader("X-Correlation-Id"));
    }

    @Test
    void echoesProvidedCorrelationId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("admin", ORG, "corr-1"), response, new Object()));
        assertEquals("corr-1", response.getHeader("X-Correlation-Id"));
    }

    @Test
    void rejectsInvalidCorrelationId() {
        ApiException exception = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request("admin", ORG, "bad id!"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(422, exception.status());
    }

    @Test
    void rejectsWhenSyntheticProfileInactive() {
        MockEnvironment inactive = new MockEnvironment();
        TenantContextInterceptor guarded = new TenantContextInterceptor(inactive);
        ApiException exception = assertThrows(ApiException.class,
                () -> guarded.preHandle(request("admin", ORG, "corr-1"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(401, exception.status());
    }

    @Test
    void rejectsMissingSubject() {
        ApiException exception = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request(null, ORG, "corr-1"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(401, exception.status());
    }

    @Test
    void rejectsBlankSubject() {
        ApiException exception = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request("  ", ORG, "corr-1"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(401, exception.status());
    }

    @Test
    void rejectsMissingOrganization() {
        ApiException exception = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request("admin", null, "corr-1"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(422, exception.status());
    }

    @Test
    void rejectsMalformedOrganization() {
        ApiException exception = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request("admin", "not-a-uuid", "corr-1"),
                        new MockHttpServletResponse(), new Object()));
        assertEquals(422, exception.status());
    }

    @Test
    void registersWithoutError() {
        org.springframework.web.servlet.config.annotation.InterceptorRegistry registry =
                new org.springframework.web.servlet.config.annotation.InterceptorRegistry();
        interceptor.addInterceptors(registry);
    }
}
