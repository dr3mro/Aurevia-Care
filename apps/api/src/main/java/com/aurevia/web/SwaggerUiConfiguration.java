package com.aurevia.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the local API documentation handler when the feature is enabled (specification section 7).
 *
 * <p>A resource handler, rather than a controller, keeps the Swagger page out
 * of the versioned API route parity gate: the page is browser documentation,
 * not a new REST endpoint. The handler is registered only when
 * {@link com.aurevia.web.DocsEnabled} agrees with the security
 * chains, so a disabled deployment has no mapping at all and returns 404.</p>
 */
@Configuration
public class SwaggerUiConfiguration implements WebMvcConfigurer {

    /** Path patterns handled by the generated documentation; the exact paths are
     * registered explicitly because a PathPattern {@code /**} does not match a
     * bare trailing-slash request. */
    static final String[] DOCS_PATH_PATTERNS = {"/swagger-ui", "/swagger-ui/", "/swagger-ui/**"};

    private final DocsEnabled docsEnabled;

    /**
     * Bind the shared feature decision.
     *
     * @param docsEnabled shared enablement condition
     */
    public SwaggerUiConfiguration(DocsEnabled docsEnabled) {
        this.docsEnabled = docsEnabled;
    }

    /**
     * Map the docs subtree to the pinned webjar and generated local resources,
     * or leave it absent when the feature is off.
     *
     * @param registry Spring MVC resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!docsEnabled.isEnabled()) {
            return;
        }
        registry.addResourceHandler(DOCS_PATH_PATTERNS)
                .addResourceLocations("classpath:/" + SwaggerUiResourceResolver.WEBJAR_ROOT)
                .resourceChain(false)
                .addResolver(new SwaggerUiResourceResolver());
    }
}
