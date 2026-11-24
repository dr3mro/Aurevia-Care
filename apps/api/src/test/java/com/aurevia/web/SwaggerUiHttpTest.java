package com.aurevia.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.aurevia.tenant.TenantContext;

/**
 * HTTP entry-point evidence for the in-repository Swagger UI (specification section 7).
 *
 * <p>Every scenario drives the real security chains, the defensive headers
 * filter and the production resource handler through a servlet-bound MockMvc,
 * so a page that is served but cannot authenticate or render its local assets
 * cannot pass. All identities and contracts in this class are synthetic.</p>
 */
class SwaggerUiHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "synthetic-api";
    private static final String DOCS_PROPERTY = "aurevia.docs.enabled";
    private static final String SWAGGER_PATH = "/swagger-ui/";
    private static final String EXACT_SWAGGER_PATH = "/swagger-ui";
    private static final String INDEX_PATH = "/swagger-ui/index.html";
    private static final String INITIALIZER_PATH = "/swagger-ui/swagger-initializer.js";
    private static final String BUNDLE_PATH = "/swagger-ui/swagger-ui-bundle.js";
    private static final String CONTRACT_PATH = "/api/v1/openapi.yaml";
    private static final String BASELINE_CSP = "default-src 'none'; frame-ancestors 'none'";
    private static final String SCOPED_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'none'; object-src 'none'";

    /** Stubs prevent OIDC discovery and pin the absolute-session clock. */
    @TestConfiguration
    static class Stubs {
        /** @return a decoder that mints synthetic tokens with the configured issuer and audience */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none").subject("user-123").issuer(ISSUER)
                    .claim("aud", List.of(AUDIENCE)).build();
        }

        /** @return a fixed clock so absolute-session expiry is deterministic */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    /** Imports the MVC infrastructure that discovers the production resource handler. */
    @TestConfiguration
    @EnableWebMvc
    static class Mvc {
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * The synthetic profile enables the page without any operator setting.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void pageLoadsUnderSyntheticAuth() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("swagger-ui-bundle")))
                    .andExpect(content().string(containsString(CONTRACT_PATH)))
                    .andExpect(header().string("Content-Security-Policy", SCOPED_CSP));
        });
    }

    /**
     * A deployment in development gets the page without a setting or a token.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void pageLoadsUnderDevelopmentProfile() throws Throwable {
        runner("development", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString(CONTRACT_PATH)));
        });
    }

    /**
     * production plus the explicit flag enables the page for a bearer client.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void pageLoadsWhenDocsEnabledWithBearerToken() throws Throwable {
        runner("production", true).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH).header("Authorization", "Bearer synthetic-token"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString(CONTRACT_PATH)));
        });
    }

    /**
     * production plus the flag also permits an anonymous browser client, which
     * meets the staff-web chain first.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void docsFlagPermitsAnonymousBrowserClient() throws Throwable {
        runner("production", true).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("swagger-ui-bundle")));
        });
    }

    /**
     * With no profile and no flag the resource mapping is absent: an
     * authenticated caller gets 404, not a sign-in redirect or a login page.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void is404WhenDocsDisabled() throws Throwable {
        runner("production", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH).header("Authorization", "Bearer synthetic-token"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(not(containsString("swagger-ui-bundle"))));
        });
    }

    /**
     * The scoped policy allows only same-origin assets and the local contract.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void cspOnSwaggerAllowsSelfOnly() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.allOf(
                                    containsString("script-src 'self'"),
                                    containsString("style-src 'self'"),
                                    containsString("connect-src 'self'"),
                                    containsString("frame-ancestors 'none'"),
                                    not(containsString("unsafe-inline")),
                                    not(containsString("unsafe-eval")),
                                    not(containsString("http://")),
                                    not(containsString("https://")),
                                    not(containsString("unpkg")),
                                    not(containsString("jsdelivr")))))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        });
    }

    /**
     * The stricter global policy is unchanged on the contract route.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void globalCspUnchangedOnContractRoute() throws Throwable {
        runner("production", true).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(CONTRACT_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", BASELINE_CSP))
                    .andExpect(header().string("Content-Type", "application/yaml"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        });
    }

    /**
     * The generated page and initializer contain no remote origin or CDN host.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void servedHtmlAndInitializerHaveNoCdnReferences() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            MockMvc mvc = mvc(context);
            String html = mvc.perform(get(SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String initializer = mvc.perform(get(INITIALIZER_PATH))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (String body : List.of(html, initializer)) {
                assertTrue(body.length() > 0);
                assertTrue(!body.contains("http://") && !body.contains("https://"), body);
                assertTrue(!body.contains("unpkg") && !body.contains("jsdelivr")
                        && !body.contains("cdnjs") && !body.contains("cdn."), body);
            }
            assertTrue(html.contains("/swagger-ui/swagger-ui-bundle.js"), html);
            assertTrue(initializer.contains("SwaggerUIBundle"));
            assertTrue(initializer.contains(CONTRACT_PATH));
        });
    }

    /**
     * The webjar assets are served locally, not fetched from a CDN.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void swaggerAssetsAreServedLocally() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(BUNDLE_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.valueOf("text/javascript")));
        });
    }

    /**
     * The explicit index path and the exact path without a trailing slash also
     * resolve to the local page.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void indexAndExactPathsAreServed() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            MockMvc mvc = mvc(context);
            mvc.perform(get(INDEX_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("swagger-ui-bundle")));
            mvc.perform(get(EXACT_SWAGGER_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("swagger-ui-bundle")));
        });
    }

    /**
     * An unknown asset does not fall back to the petstore initializer or a CDN.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void unknownAssetReturns404() throws Throwable {
        runner("synthetic-auth", null).run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get("/swagger-ui/does-not-exist.js"))
                    .andExpect(status().isNotFound());
        });
    }

    /**
     * Anonymous swagger access is denied when the feature is off, without a
     * successful response that could be mistaken for an enabled page.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void oidcChainDeniesSwaggerWhenDocsDisabled() throws Throwable {
        runner("production", null).run(context -> {
            assertNull(context.getStartupFailure());
            MvcResult result = mvc(context).perform(get(SWAGGER_PATH)).andReturn();
            int status = result.getResponse().getStatus();
            assertTrue(status != 200 && (status == 401 || (status >= 300 && status < 400)),
                    "unexpected status " + status);
            assertTrue(!result.getResponse().getContentAsString().contains("swagger-ui-bundle"));
        });
    }

    /**
     * The resolver accepts both servlet-relative and already-stripped paths.
     */
    @Test
    void resolverAcceptsFullAndRelativePaths() {
        SwaggerUiResourceResolver resolver = new SwaggerUiResourceResolver();
        assertNotNull(resolver.resolveResource(null, "/swagger-ui/index.html", List.of(), null));
        assertNotNull(resolver.resolveResource(null, "/swagger-ui", List.of(), null));
        assertNotNull(resolver.resolveResource(null, "index.html", List.of(), null));
        assertNull(resolver.resolveResource(null, "/other/missing.js", List.of(), null));
        assertNull(resolver.resolveResource(null, "/swagger-ui/../application.yml", List.of(), null));
    }

    /**
     * Generated resources expose stable UTF-8 bytes and a predictable name.
     *
     * @throws Exception when the stream cannot be read
     */
    @Test
    void textResourceExposesStableContentAndName() throws Exception {
        SwaggerUiResourceResolver.TextResource resource =
                new SwaggerUiResourceResolver.TextResource("index.html", "body");
        assertEquals("index.html", resource.getFilename());
        assertTrue(resource.exists());
        assertTrue(resource.isReadable());
        assertEquals(4, resource.contentLength());
        assertEquals(0, resource.lastModified());
        assertEquals("in-memory resource index.html", resource.getDescription());
        assertEquals("body", new String(resource.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The resolver leaves generated same-origin URLs untouched.
     */
    @Test
    void resolverDoesNotRewriteUrls() {
        SwaggerUiResourceResolver resolver = new SwaggerUiResourceResolver();
        assertNull(resolver.resolveUrlPath("/swagger-ui/swagger-ui.css", List.of(), null));
    }

    /**
     * The pinned webjar version in Java and the dependency in the build file
     * cannot drift: this resolves the exact classpath asset the handler serves.
     */
    @Test
    void pinnedWebjarVersionIsResolvable() {
        assertTrue(new ClassPathResource("META-INF/resources/webjars/swagger-ui/5.36.0/"
                + "swagger-ui-bundle.js").exists());
    }

    /**
     * Build one isolated runner; one-call rate limiting is deliberately not
     * registered because docs are not tenant-scoped.
     *
     * @param profiles    Spring profiles to activate, or null for none
     * @param docsEnabled explicit flag value, or null to exercise the default
     * @return a configured runner
     */
    private static WebApplicationContextRunner runner(String profiles, Boolean docsEnabled) {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, StaffWebLoginConfiguration.class,
                        DocsEnabled.class, SwaggerUiConfiguration.class, Mvc.class, Stubs.class,
                        OpenApiContractController.class)
                .withPropertyValues(ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=" + AUDIENCE,
                        "aurevia.oidc.client-id=synthetic-client",
                        "aurevia.oidc.client-secret=synthetic-secret");
        if (profiles != null) {
            String active = profiles;
            runner = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(active));
        }
        if (docsEnabled != null) {
            runner = runner.withPropertyValues(DOCS_PROPERTY + "=" + docsEnabled);
        }
        return runner;
    }

    /**
     * Wire the real filters and MVC handler mappings into a servlet boundary.
     *
     * @param context running application context
     * @return an HTTP client for the docs page
     */
    private static MockMvc mvc(ApplicationContext context) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(new SecurityHeadersFilter(), proxy)
                .build();
    }
}
