package com.microservices.cart.config;

import com.microservices.cart.logging.DownstreamLoggingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * REST Client Configuration
 *
 * Configures the RestClient used to ask product-service for the name and price of a product.
 * The time-outs are short (2 seconds to connect, 5 seconds to wait for the answer), so a slow
 * product-service gives a quick "please try again" answer instead of a hanging request.
 *
 * The client is built from the RestClient.Builder that Spring Boot provides (not from the static
 * RestClient.builder()), because only that builder is connected to the tracing: the trace id then travels to
 * product-service in the "traceparent" header of every call. A logging interceptor writes one line per call.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
public class RestClientConfig {

    /**
     * RestClient for product-service
     *
     * @param builder         the builder of Spring Boot (it carries the tracing)
     * @param baseUrl         base URL of product-service (env PRODUCT_SERVICE_URL)
     * @param connectTimeoutMs time to wait for the connection, in milliseconds
     * @param readTimeoutMs    time to wait for the answer, in milliseconds
     * @return RestClient with the base URL and the time-outs
     */
    @Bean
    public RestClient productRestClient(
            RestClient.Builder builder,
            @Value("${cart.product-service.url:http://localhost:8081}") String baseUrl,
            @Value("${cart.product-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${cart.product-service.read-timeout-ms:5000}") int readTimeoutMs) {
        return createRestClient(builder, baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * Builds the client from a builder. Public so a test can build the same client for a fake server.
     */
    public static RestClient createRestClient(RestClient.Builder builder, String baseUrl, int connectTimeoutMs,
                                              int readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .requestInterceptor(new DownstreamLoggingInterceptor("product-service"))
                .build();
    }

    /**
     * The same client, built from a plain builder: no tracing. Only for tests of the client on its own;
     * the application always uses the builder of Spring Boot.
     */
    public static RestClient createRestClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        return createRestClient(RestClient.builder(), baseUrl, connectTimeoutMs, readTimeoutMs);
    }
}
