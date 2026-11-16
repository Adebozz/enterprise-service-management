package com.ademola.esm.config;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web app when it is bundled into the backend image (the root Dockerfile copies the React
 * build into {@code classpath:/static}), so one container is the whole site on a single-container
 * host. The same origin keeps the refresh cookie first-party, as nginx does in Docker Compose.
 *
 * <p>Caching follows the nginx setup: hashed files under {@code /assets/} are kept for a year, and
 * everything else, {@code index.html} included, is revalidated so a new deployment shows at once.
 * Client-side routes such as {@code /tickets/42} have no file and get {@code index.html}; the
 * server's own paths never fall back, so an unknown {@code /api/...} stays a 404 problem response.
 * Without a bundled web app (development, tests of the API alone) nothing here matches.
 */
@Configuration(proxyBeanMethods = false)
class SpaWebConfig implements WebMvcConfigurer {

    private static final String[] NO_FALLBACK = {"api/", "actuator", "v3/", "swagger-ui"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(
                        CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (isClientRoute(resourcePath)) {
                            Resource index = new ClassPathResource("/static/index.html");
                            return index.exists() ? index : null;
                        }
                        return null;
                    }
                });
    }

    /** Extension-less paths outside the server's own prefixes: {@code queue}, {@code tickets/42}. */
    static boolean isClientRoute(String path) {
        for (String prefix : NO_FALLBACK) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }
}
