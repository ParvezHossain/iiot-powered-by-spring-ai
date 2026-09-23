package com.iiot.config;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;
import org.springframework.web.util.UrlPathHelper;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {
    private static final List<String> SERVER_PATHS = List.of(
            "/api", "/mcp", "/actuator", "/v3", "/swagger-ui", "/error", "/webjars");
    private final String[] locations;

    public WebConfig(@Value("${spring.web.resources.static-locations:classpath:/static/}") String[] locations) {
        this.locations = locations;
    }

    /** Only browser navigation can fall back to the SPA; backend paths and assets never do. */
    public static boolean isSpaNavigation(HttpServletRequest request) {
        if (!isRead(request)) return false;
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path.contains(".") || SERVER_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/")))
            return false;
        try {
            return MediaType.parseMediaTypes(request.getHeader("Accept")).stream()
                    .anyMatch(type -> type.getQualityValue() > 0 && "text".equals(type.getType())
                            && "html".equals(type.getSubtype()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isFrontendRequest(HttpServletRequest request) {
        if (!isRead(request)) return false;
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")
                || path.matches("/[^/]+\\.(js|css)") || path.startsWith("/assets/")
                || path.startsWith("/media/") || isSpaNavigation(request);
    }

    private static boolean isRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations(locations)
                .resourceChain(false).addResolver(new PathResourceResolver() {
                    @Override
                    protected @Nullable Resource resolveResourceInternal(@Nullable HttpServletRequest request,
                            String path, List<? extends Resource> locations, ResourceResolverChain chain) {
                        Resource resource = super.resolveResourceInternal(request, path, locations, chain);
                        if (resource == null && request != null && isSpaNavigation(request)) {
                            return super.resolveResourceInternal(request, "index.html", locations, chain);
                        }
                        return resource;
                    }
                });
    }
}
