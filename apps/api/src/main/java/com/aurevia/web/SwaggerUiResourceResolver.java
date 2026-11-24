package com.aurevia.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves Swagger UI requests to same-origin content inside the application (specification section 7).
 *
 * <p>The welcome page and its initializer are generated in memory because no
 * static resource directory is part of this story. Every other asset is read
 * from the pinned webjar on the classpath. Nothing is fetched from a CDN: the
 * only remote-looking string in the stock webjar initializer, the public
 * petstore contract, is replaced with the local contract route.</p>
 */
final class SwaggerUiResourceResolver implements ResourceResolver {

    /** Classpath root of the pinned webjar; mirrors the version declared in the build file. */
    static final String WEBJAR_ROOT = "META-INF/resources/webjars/swagger-ui/5.36.0/";

    /** Prefix stripped from the full request path before resolving a local asset. */
    private static final String DOCS_PREFIX = "/swagger-ui";

    /** Name of the generated welcome document. */
    private static final String INDEX = "index.html";

    /** Name of the generated initializer that points at the local contract. */
    private static final String INITIALIZER = "swagger-initializer.js";

    /** Language-tagged markup for the generated page. */
    private static final String INDEX_CONTENT = """
            <!DOCTYPE html>
            <html lang="en">
              <head>
                <meta charset="UTF-8">
                <title>Orevia API documentation</title>
                <meta name="orevia-openapi-contract" content="/api/v1/openapi.yaml">
                <link rel="stylesheet" type="text/css" href="/swagger-ui/swagger-ui.css">
                <link rel="icon" type="image/png" href="/swagger-ui/favicon-32x32.png" sizes="32x32">
              </head>
              <body>
                <div id="swagger-ui"></div>
                <script src="/swagger-ui/swagger-ui-bundle.js"></script>
                <script src="/swagger-ui/swagger-ui-standalone-preset.js"></script>
                <script src="/swagger-ui/swagger-initializer.js"></script>
              </body>
            </html>
            """;

    /** External initializer: a script file avoids the inline execution forbidden by WEB-02. */
    private static final String INITIALIZER_CONTENT = """
            window.onload = function () {
              window.ui = SwaggerUIBundle({
                url: '/api/v1/openapi.yaml',
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                plugins: [SwaggerUIBundle.plugins.DownloadUrl],
                layout: 'StandaloneLayout'
              });
            };
            """;

    /** Cached in-memory welcome document. */
    private final Resource indexResource = new TextResource(INDEX, INDEX_CONTENT);

    /** Cached in-memory initializer. */
    private final Resource initializerResource = new TextResource(INITIALIZER, INITIALIZER_CONTENT);

    /**
     * Map a request path beneath the docs handler to a local resource.
     *
     * @param request      current request, unused: resolution is path-only
     * @param requestPath  path beneath {@code /swagger-ui/**}
     * @param locations    configured classpath locations, unused: the pinned root is fixed
     * @param chain        remaining resolvers, unused: this resolver is authoritative
     * @return the resource to serve, or {@code null} when the asset does not exist
     */
    @Override
    public Resource resolveResource(HttpServletRequest request, String requestPath,
            List<? extends Resource> locations, ResourceResolverChain chain) {
        String relative = requestPath;
        if (relative.startsWith(DOCS_PREFIX)) {
            relative = relative.substring(DOCS_PREFIX.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
        }
        // Path traversal must never escape the pinned webjar root, even if a
        // caller supplies a crafted path that bypasses the resource handler.
        if (relative.contains("..")) {
            return null;
        }
        if (relative.isEmpty() || INDEX.equals(relative)) {
            return indexResource;
        }
        if (INITIALIZER.equals(relative)) {
            return initializerResource;
        }
        Resource asset = new ClassPathResource(WEBJAR_ROOT + relative);
        return asset.exists() ? asset : null;
    }

    /**
     * Decline URL rewriting: the generated links are already same-origin.
     *
     * @param resourcePath original resource path
     * @param locations    configured classpath locations
     * @param chain        remaining resolvers
     * @return {@code null} to keep the original URL
     */
    @Override
    public String resolveUrlPath(String resourcePath, List<? extends Resource> locations,
            ResourceResolverChain chain) {
        return null;
    }

    /** An immutable in-memory resource with a stable name for content-type negotiation. */
    static final class TextResource extends AbstractResource {

        /** File name used for content-type negotiation. */
        private final String filename;

        /** Exact UTF-8 bytes served to the browser. */
        private final byte[] content;

        /**
         * Wrap UTF-8 bytes with a stable name and a deterministic modification time.
         *
         * @param filename name reported to the media-type factory
         * @param content  exact bytes to serve
         */
        TextResource(String filename, String content) {
            this.filename = filename;
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public long contentLength() {
            return content.length;
        }

        @Override
        public long lastModified() {
            return 0L;
        }

        @Override
        public String getDescription() {
            return "in-memory resource " + filename;
        }
    }
}
