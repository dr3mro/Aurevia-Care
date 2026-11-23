package com.aurevia.smartclinic.staffmsg.security;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.staffmsg.instant.InstantConversationStore;
import com.aurevia.smartclinic.staffmsg.realtime.MessageEventHub;
import com.aurevia.smartclinic.staffmsg.realtime.RealtimeService;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * Synthetic fixtures shared by the staff-messaging security tests (MSG-03, MSG-07).
 *
 * <p>Every helper builds the real production controller/service chain over a
 * deterministic in-memory or mocked boundary. Identifiers and payloads are
 * synthetic: no tenant identifier from a real deployment and no patient data is
 * ever used. Response bodies are compared after normalising only the request
 * correlation identifier, so a different error code, message or leaked payload
 * still fails the comparison.</p>
 */
final class StaffMessagingSecurityFixture {

    /** Owning tenant of the seeded conversations, messages and attachments. */
    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    /** Attacking tenant, with the same local identifier vocabulary as ORG_A. */
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    /** Facility in scope for the ORG_A actors. */
    static final String FACILITY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    /** Facility outside the ORG_A actors' scope. */
    static final String FACILITY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    /** ORG_A sender with an ordinary sending role. */
    static final String SENDER = "nurse-a";
    /** ORG_A independent quarantine scanner principal. */
    static final String SCANNER = "scanner-a";
    /** ORG_A recipient of a published attachment. */
    static final String RECIPIENT = "clinician-b";
    /** ORG_A second sender, used to test uploader binding. */
    static final String OTHER_SENDER = "nurse-c";
    /** Subject with no active staff membership. */
    static final String GHOST = "ghost-1111";
    /** Unauthorised ORG_B actor. */
    static final String OUTSIDER = "outsider-b";
    /** A synthetic scan finding carried as the scanner's detail. */
    static final String SCAN_FINDING = "EICAR synthetic threat";

    /** Synthetic PDF bytes with the leading signature the upload validator requires. */
    static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private StaffMessagingSecurityFixture() {
    }

    /**
     * Build a standalone MockMvc over the given real controllers with the real
     * request interceptor and uniform error advice.
     *
     * @param controllers controllers under test
     * @return a MockMvc that derives identity only from the request headers
     */
    static MockMvc mvc(Object... controllers) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /**
     * Add the synthetic authentication and organization headers to one request.
     *
     * @param request request to decorate
     * @param org     active organization UUID text, or {@code null} to omit it
     * @param subject authenticated subject, or {@code null} to omit it
     * @return the same builder with the requested headers
     */
    static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request,
                                                 String org, String subject) {
        if (subject != null) {
            request.header("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            request.header("X-Active-Organization", org);
        }
        return request;
    }

    /**
     * Read a response body as UTF-8 text.
     *
     * @param result MVC result to read
     * @return the response body
     * @throws Exception when the response cannot be decoded
     */
    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Normalise only the per-request correlation identifier in an error body.
     *
     * @param json response body
     * @return the body with a stable correlation identifier
     */
    static String normalise(String json) {
        return json.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalised\"");
    }

    /**
     * Assert that a refusal body carries no implementation or payload detail.
     *
     * @param json response body to inspect
     * @param bodyText message body of a published message, used as a leak marker
     */
    static void assertNoLeak(String json, String bodyText) {
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("Exception")
                || json.contains("exception") || json.contains("\tat "), "stack trace leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("SELECT ")
                || json.contains("java.sql."), "SQL detail leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("com.aurevia")
                || json.contains("java.lang."), "class name leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains(bodyText), "message payload leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("objectKey"), "private object key leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("checksum"), "checksum leaked");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("fileName"), "file name leaked");
    }

    /**
     * Create a synthetic multipart PDF upload.
     *
     * @return a multipart file with a valid PDF signature and extension
     */
    static MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "note.pdf", "application/pdf", PDF);
    }

    /**
     * Create an opaque, unused identifier for a guessed-id request.
     *
     * @return random UUID text
     */
    static String randomId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Bind a real service to a captured emitter through its package-private factory seam.
     *
     * <p>The emitter factory is intentionally package-private so production callers
     * cannot inject an emitter. The security tests live in a sibling package and
     * reach the seam through a reflective proxy, avoiding a production visibility
     * change purely for a test.</p>
     *
     * @param store  authorization store for the service
     * @param hub    process-local event hub
     * @param emitter emitter to hand to the service
     * @return the service bound to the supplied emitter
     */
    static RealtimeService serviceWithEmitter(InstantConversationStore store, MessageEventHub hub,
                                              SseEmitter emitter) {
        try {
            Class<?> factoryType = Class.forName(
                    "com.aurevia.smartclinic.staffmsg.realtime.RealtimeService$EmitterFactory");
            Object factory = Proxy.newProxyInstance(factoryType.getClassLoader(),
                    new Class<?>[] {factoryType}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(emitter, args);
                        }
                        return emitter;
                    });
            Constructor<RealtimeService> constructor = RealtimeService.class.getDeclaredConstructor(
                    InstantConversationStore.class, MessageEventHub.class, factoryType);
            constructor.setAccessible(true);
            return constructor.newInstance(store, hub, factory);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot bind the package-private emitter factory", failure);
        }
    }
}
