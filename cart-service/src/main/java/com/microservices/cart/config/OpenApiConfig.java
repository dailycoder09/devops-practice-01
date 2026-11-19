package com.microservices.cart.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI Configuration
 *
 * Configures Swagger/OpenAPI documentation for the Cart Service
 * Provides comprehensive API documentation accessible via /swagger-ui.html
 * Adds a Bearer scheme so the "Authorize" button accepts the token from user-service's login
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
public class OpenApiConfig {

    /**
     * Name of the Bearer security scheme (used by @SecurityRequirement on the endpoints that need a login)
     */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Value("${spring.application.name:cart-service}")
    private String applicationName;

    @Value("${server.port:8084}")
    private String serverPort;

    /**
     * Configure OpenAPI documentation
     *
     * @return OpenAPI configuration
     */
    @Bean
    public OpenAPI cartServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:" + serverPort);
        localServer.setDescription("Local Development Server");

        Contact contact = new Contact();
        contact.setName("Microservices Team");
        contact.setEmail("support@microservices.com");
        contact.setUrl("https://microservices.com");

        License license = new License()
                .name("MIT License")
                .url("https://opensource.org/licenses/MIT");

        Info info = new Info()
                .title("Cart Service API")
                .version("1.0.0")
                .description("Shopping cart of a logged-in person for the E-Commerce Microservices Architecture")
                .contact(contact)
                .license(license)
                .termsOfService("https://microservices.com/terms");

        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the token from POST /api/users/login of user-service (without the word Bearer)");

        return new OpenAPI()
                .info(info)
                .servers(List.of(localServer))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer));
    }
}
