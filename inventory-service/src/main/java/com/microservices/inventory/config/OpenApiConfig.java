package com.microservices.inventory.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI Configuration
 *
 * Configures Swagger/OpenAPI documentation for the Inventory Service
 * Provides comprehensive API documentation accessible via /swagger-ui.html
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Value("${spring.application.name:inventory-service}")
    private String applicationName;

    @Value("${server.port:8082}")
    private String serverPort;

    /**
     * Configure OpenAPI documentation
     *
     * @return OpenAPI configuration
     */
    @Bean
    public OpenAPI inventoryServiceOpenAPI() {
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
                .title("Inventory Service API")
                .version("1.0.0")
                .description("RESTful API for Inventory Management in E-Commerce Microservices Architecture")
                .contact(contact)
                .license(license)
                .termsOfService("https://microservices.com/terms");

        // Bearer scheme: the Authorize button in Swagger UI sends "Authorization: Bearer <token>"
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Log in with user-service, then paste the token here. Only needed for POST, PUT, PATCH and DELETE. Most of them need an ADMIN login; reserve and release need the SERVICE token that order-service makes.");

        return new OpenAPI()
                .info(info)
                .servers(List.of(localServer))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
