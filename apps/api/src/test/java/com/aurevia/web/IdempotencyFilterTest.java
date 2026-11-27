package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.api.contract.IdempotencyLedger.Decision;
import com.aurevia.api.contract.IdempotencyLedger.Scope;
import com.aurevia.api.contract.IdempotencyResponseStore;
import com.aurevia.api.contract.RequestDigest;
import com.aurevia.api.contract.ResponseResolution;
import com.aurevia.api.contract.StoredResponse;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-03/ARC-04 HTTP proof for the keyed replay filter.
 *
 * <p>The real {@link com.aurevia.web.IdempotencyFilter},
 * {@link com.aurevia.web.IdempotencyBodyCaptureFilter},
 * {@link com.aurevia.web.TenantContextInterceptor} and
 * {@link com.aurevia.api.ApiErrorHandler} run in the Spring MVC pipeline. A
 * counting test controller makes "the handler ran twice" a deterministic
 * failure instead of a timing guess. The fake store lives in test code only;
 * the JDBC adapter is covered separately and by the HTTP integration suite.
 * All subjects, keys and bodies are synthetic.</p>
 */
class IdempotencyFilterTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "idem-actor";
    private static final String PEER_SUBJECT = "idem-peer";
    private static final String PATH = "/api/v1/idempotency/probe";

    private CountingController controller;
    private FakeStore store;
    private MockMvc mvc;
    private IdempotencyFilter filter;
    private java.util.concurrent.atomic.AtomicBoolean replayAuthorized;

    /** Wire the real filters and interceptors around a synthetic counting route. */
    @BeforeEach
    void setUp() {
        controller = new CountingController();
        store = new FakeStore();
        replayAuthorized = new java.util.concurrent.atomic.AtomicBoolean(true);
        filter = new IdempotencyFilter(store, (context, command) -> replayAuthorized.get());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment), filter)
                .addFilters(new IdempotencyBodyCaptureFilter(store))
                .build();
    }

    /** Clear the thread-local tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void identicalRetryReplaysTheFirstStatusAndBodyWithoutRunningTheHandlerTwice() throws Exception {
        String first = postBody("idem-key-1", "{\"marker\":\"a\"}", SUBJECT, ORG)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retry = postBody("idem-key-1", "{\"marker\":\"a\"}", SUBJECT, ORG)
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("charset=UTF-8")))
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, retry);
        assertEquals(1, controller.calls.get());
        assertEquals(1, store.rows.size());
    }

    @Test
    void aReplayIsConcealedWhenAuthorizationNoLongerHolds() throws Exception {
        postBody("idem-key-auth", "{\"marker\":\"a\"}", SUBJECT, ORG)
                .andExpect(status().isCreated());
        replayAuthorized.set(false);

        String denial = postBody("idem-key-auth", "{\"marker\":\"a\"}", SUBJECT, ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(denial.contains("marker"), "a lapsed authorization must not disclose the cached body");
        assertEquals(1, controller.calls.get());
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefusedWith422AndDoesNotRunTheHandler() throws Exception {
        postBody("idem-key-2", "{\"marker\":\"a\"}", SUBJECT, ORG).andExpect(status().isCreated());
        postBody("idem-key-2", "{\"marker\":\"b\"}", SUBJECT, ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(1, controller.calls.get());
        assertEquals(1, store.rows.size());
    }

    @Test
    void aRequestWithoutTheHeaderBehavesExactlyAsBefore() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"a\"}")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"a\"}")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());

        assertEquals(2, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aBlankHeaderIsTreatedAsAnUnkeyedRequest() throws Exception {
        postBody("   ", "{\"marker\":\"a\"}", SUBJECT, ORG).andExpect(status().isCreated());

        assertEquals(1, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aNonPostRequestWithTheHeaderBypassesTheLedger() throws Exception {
        mvc.perform(get(PATH).header("Idempotency-Key", "idem-key-3")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());

        assertEquals(1, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aMalformedKeyIsRejectedWithoutRunningTheHandler() throws Exception {
        postBody("bad key!", "{\"marker\":\"a\"}", SUBJECT, ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertEquals(0, controller.calls.get());
    }

    @Test
    void aPeerSubjectAndAPeerOrganizationEachOwnTheirOwnKey() throws Exception {
        postBody("idem-key-4", "{\"marker\":\"a\"}", SUBJECT, ORG).andExpect(status().isCreated());
        postBody("idem-key-4", "{\"marker\":\"b\"}", PEER_SUBJECT, ORG).andExpect(status().isCreated());
        postBody("idem-key-4", "{\"marker\":\"c\"}", SUBJECT, PEER_ORG).andExpect(status().isCreated());

        assertEquals(3, controller.calls.get());
        assertEquals(3, store.rows.size());
    }

    @Test
    void theSameKeyOnTwoCommandsOwnsTwoIndependentResponses() throws Exception {
        postBody("idem-key-command", "{\"marker\":\"a\"}", SUBJECT, ORG).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/idempotency/{id}/variable", "one")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-command")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());

        assertEquals(2, controller.calls.get());
        assertEquals(2, store.rows.size());
    }

    @Test
    void aFailedFirstResponseIsNotCachedSoTheRetryRunsAgain() throws Exception {
        postBody("idem-key-5", "{\"fail\":true}", SUBJECT, ORG)
                .andExpect(status().isUnprocessableEntity());
        postBody("idem-key-5", "{\"fail\":true}", SUBJECT, ORG)
                .andExpect(status().isUnprocessableEntity());

        assertEquals(2, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void noTenantContextLeavesTheRequestUnchanged() throws Exception {
        MockMvc unguarded = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(filter)
                .addFilters(new IdempotencyBodyCaptureFilter(store))
                .build();

        unguarded.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-6").content("{\"marker\":\"a\"}"))
                .andExpect(status().isCreated());

        assertEquals(1, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aRequestWithNoMatchedHandlerPatternSkipsTheLedger() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("Idempotency-Key", "idem-key-7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));

        assertTrue(filter.preHandle(request, response, new Object()));
        assertFalse(request.getAttributeNames().hasMoreElements()
                && request.getAttribute(IdempotencyBodyCaptureFilter.STORE_ATTRIBUTE) != null);
    }

    /**
     * The conversation reply route keeps replay inside its own service, which
     * re-derives live thread access, the patient link and writable state on
     * every retry. The interceptor must therefore let a repeated key reach the
     * handler instead of freezing and replaying the first response.
     */
    @Test
    void aSelfManagedReplyRouteNeverFreezesItsResponse() throws Exception {
        String first = mvc.perform(post("/api/v1/conversations/{id}/reply", "conv-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-self-managed")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(post("/api/v1/conversations/{id}/reply", "conv-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-self-managed")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The handler stamps an incrementing call count into its body, so a frozen
        // replay would repeat the first body instead of showing the second call.
        assertNotEquals(first, retry, "a frozen replay would repeat the first body");
        assertEquals(2, controller.calls.get(), "both calls must reach the self-managed handler");
        assertTrue(store.rows.isEmpty(), "the reply route must not claim an idempotency key");
    }

    @Test
    void anEmptySuccessfulResponseIsNotFrozenAsJson() throws Exception {
        mvc.perform(post("/api/v1/idempotency/empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-8")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNoContent());

        assertEquals(1, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void theSameKeyAndBodyOnDifferentResourcePathsConflict() throws Exception {
        mvc.perform(post("/api/v1/idempotency/{id}/variable", "one")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-9")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/idempotency/{id}/variable", "two")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-9")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(1, controller.calls.get());
    }

    @Test
    void aMultipartPostIsNotBufferedOrFrozen() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .content("synthetic-binary")
                        .header("Idempotency-Key", "idem-key-10")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());

        assertEquals(1, controller.calls.get());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void anOversizedOrNonApiPostIsNotBuffered() throws Exception {
        IdempotencyBodyCaptureFilter capture = new IdempotencyBodyCaptureFilter(store);
        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", PATH);
        oversized.setContentType(MediaType.APPLICATION_JSON_VALUE);
        oversized.addHeader("Idempotency-Key", "idem-key-11");
        oversized.setContent(new byte[IdempotencyBodyCaptureFilter.MAX_CAPTURE_BYTES + 1]);
        capture.doFilter(oversized, new MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());
        assertNull(oversized.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));

        MockHttpServletRequest nonApi = new MockHttpServletRequest("POST", "/health/probe");
        nonApi.setContentType(MediaType.APPLICATION_JSON_VALUE);
        nonApi.addHeader("Idempotency-Key", "idem-key-12");
        nonApi.setContent("{}".getBytes(StandardCharsets.UTF_8));
        capture.doFilter(nonApi, new MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());
        assertNull(nonApi.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));

        MockHttpServletRequest noType = new MockHttpServletRequest("POST", PATH);
        noType.addHeader("Idempotency-Key", "idem-key-15");
        noType.setContent("{}".getBytes(StandardCharsets.UTF_8));
        capture.doFilter(noType, new MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());
        assertNull(noType.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));

        MockHttpServletRequest plainText = new MockHttpServletRequest("POST", PATH);
        plainText.setContentType(MediaType.TEXT_PLAIN_VALUE);
        plainText.addHeader("Idempotency-Key", "idem-key-16");
        plainText.setContent("plain".getBytes(StandardCharsets.UTF_8));
        capture.doFilter(plainText, new MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());
        assertNull(plainText.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));
    }

    @Test
    void anUnknownLengthOversizedBodyIsRefusedWith413BeforeItIsBuffered() throws Exception {
        IdempotencyBodyCaptureFilter capture = new IdempotencyBodyCaptureFilter(store);
        // A chunked stream reports an unknown length, so the declared-size guard
        // cannot protect this request.
        MockHttpServletRequest chunked = unknownLengthRequest(
                new byte[IdempotencyBodyCaptureFilter.MAX_CAPTURE_BYTES + 1], "idem-key-chunked");
        org.springframework.mock.web.MockFilterChain chain = new org.springframework.mock.web.MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        capture.doFilter(chunked, response, chain);

        assertEquals(413, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("REQUEST_BODY_TOO_LARGE"));
        assertNull(chain.getRequest(), "an oversized body must not reach the handler");
        assertNull(chunked.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));
    }

    @Test
    void anUnknownLengthSmallBodyIsStillCaptured() throws Exception {
        IdempotencyBodyCaptureFilter capture = new IdempotencyBodyCaptureFilter(store);
        MockHttpServletRequest chunked = unknownLengthRequest(
                "{\"marker\":\"a\"}".getBytes(StandardCharsets.UTF_8), "idem-key-chunked-small");
        MockHttpServletResponse response = new MockHttpServletResponse();

        capture.doFilter(chunked, response, new org.springframework.mock.web.MockFilterChain());

        assertEquals("{\"marker\":\"a\"}",
                chunked.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE));
    }

    @Test
    void aLedgerFailureDoesNotBreakAnAlreadySuccessfulResponse() throws Exception {
        IdempotencyResponseStore failing = mock(IdempotencyResponseStore.class);
        when(failing.evaluate(any(), any(), any()))
                .thenReturn(new ResponseResolution(Decision.PROCEED, null));
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(failing).store(any(), any(), any(), any());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        MockMvc failingMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment), filter)
                .addFilters(new IdempotencyBodyCaptureFilter(failing))
                .build();

        failingMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"a\"}")
                        .header("Idempotency-Key", "idem-key-13")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calls").value(1));
    }

    @Test
    void aMissingBodyAttributeLeavesTheRequestUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("Idempotency-Key", "idem-key-14");
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATH);
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));

        assertTrue(filter.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(request.getAttribute(IdempotencyBodyCaptureFilter.STORE_ATTRIBUTE),
                "a missing captured body must not claim the key");
    }

    /** Build a JSON POST whose servlet length is unknown, as with chunked transfer. */
    private static MockHttpServletRequest unknownLengthRequest(byte[] body, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader("Idempotency-Key", key);
        request.setContent(body);
        return request;
    }

    /** Post one synthetic keyed command through the full MVC pipeline. */
    private org.springframework.test.web.servlet.ResultActions postBody(
            String key, String body, String subject, String organization) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization));
    }

    /** Counts handler executions and returns a body that changes on every call. */
    @RestController
    static final class CountingController {

        final AtomicInteger calls = new AtomicInteger();

        @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> post(@RequestBody(required = false) String body) {
            int count = calls.incrementAndGet();
            if (body != null && body.contains("\"fail\"")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"code\":\"INVALID_INPUT\",\"calls\":" + count + "}");
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("{\"calls\":" + count + "}");
        }

        @PostMapping(path = "/api/v1/idempotency/{id}/variable", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> variable(@PathVariable String id) {
            int count = calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("{\"calls\":" + count + ",\"id\":\"" + id + "\"}");
        }

        @PostMapping(path = "/api/v1/conversations/{id}/reply", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<String> reply(@PathVariable String id) {
            int count = calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("{\"calls\":" + count + ",\"id\":\"" + id + "\"}");
        }

        @PostMapping(path = "/api/v1/idempotency/empty")
        ResponseEntity<Void> empty() {
            calls.incrementAndGet();
            return ResponseEntity.noContent().build();
        }

        @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
        String get() {
            calls.incrementAndGet();
            return "{\"calls\":" + calls.get() + "}";
        }
    }

    /** Test-only store that keeps the first response per tenant, subject, command and key. */
    static final class FakeStore implements IdempotencyResponseStore {

        final Map<String, Entry> rows = new ConcurrentHashMap<>();

        @Override
        public ResponseResolution evaluate(Scope scope, IdempotencyKey key, RequestDigest digest) {
            Entry stored = rows.get(slot(scope, key));
            if (stored == null) {
                return new ResponseResolution(Decision.PROCEED, null);
            }
            if (!stored.hash().equals(digest.hex())) {
                return new ResponseResolution(Decision.CONFLICT, null);
            }
            return new ResponseResolution(Decision.REPLAY, stored.response());
        }

        @Override
        public void store(Scope scope, IdempotencyKey key, RequestDigest digest, StoredResponse response) {
            rows.putIfAbsent(slot(scope, key), new Entry(digest.hex(), response));
        }

        private static String slot(Scope scope, IdempotencyKey key) {
            return scope.organizationId() + "|" + scope.subject() + "|" + scope.command() + "|" + key.value();
        }

        /** Digest and first response frozen under one key. */
        record Entry(String hash, StoredResponse response) {
        }
    }

    /** Byte-level proof of the buffered request wrapper used by the capture filter. */
    @Test
    void bufferedRequestReplaysItsBodyToReadersAndStreams() throws Exception {
        byte[] body = "{\"marker\":\"a\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest source = new MockHttpServletRequest("POST", PATH);
        BufferedBodyRequestWrapper wrapper = new BufferedBodyRequestWrapper(source, body);
        jakarta.servlet.ServletInputStream stream = wrapper.getInputStream();

        assertEquals(body[0], stream.read());
        assertFalse(stream.isFinished());
        assertTrue(stream.isReady());
        stream.setReadListener(null);
        for (int index = 1; index < body.length; index++) {
            assertEquals(body[index], stream.read());
        }
        assertEquals(-1, stream.read());
        assertTrue(stream.isFinished());
        assertEquals("{\"marker\":\"a\"}", wrapper.getReader().lines().reduce("", String::concat));
    }
}
