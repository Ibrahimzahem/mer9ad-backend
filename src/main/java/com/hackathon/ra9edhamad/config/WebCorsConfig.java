package com.hackathon.ra9edhamad.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS + SPA forwarding. The React frontend is built and bundled into
 * src/main/resources/static, so the backend serves everything on one port.
 *
 * <p>The forward-to-index.html rule ensures that client-side routes (like /intel)
 * don't 404 — Spring sends them to index.html and React Router handles them.
 * API routes (/api/**, /open-banking/**) are not forwarded.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
        .allowedOrigins("http://localhost:5173", "http://localhost:8080", "https://rased.yasiraloufi.dev")
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("*");
        
         }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward all non-API, non-static-asset routes to index.html (SPA fallback)
        registry.addViewController("/intel").setViewName("forward:/index.html");
    }
}
