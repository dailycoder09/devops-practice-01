package com.microservices.order.config;

import com.microservices.order.logging.DownstreamLoggingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * REST Client Configuration
 *
 * One RestClient per service that order-service calls. All three have short time-outs
 * (connect 2 s, read 5 s by default), so a slow or stopped service cannot hang a checkout.
 *
 * Every client is built from the Spring-managed RestClient.Builder (not the static RestClient.builder()), because only
 * that builder is instrumented by Micrometer: it adds the trace headers (traceparent) to every outgoing call, so the
 * other services log the same trace id. Each client also gets a {@link DownstreamLoggingInterceptor} that logs one
 * line per call (never a header, a body or a query). The caller's token and the SERVICE token are set by the client
 * classes and are not touched here.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
public class RestClientConfig {

    @Value("${order.cart-service.url}")
    private String cartServiceUrl;

    @Value("${order.product-service.url}")
    private String productServiceUrl;

    @Value("${order.inventory-service.url}")
    private String inventoryServiceUrl;

    @Value("${order.clients.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${order.clients.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Bean
    public RestClient cartRestClient(RestClient.Builder builder) {
        return restClient(builder, cartServiceUrl, "cart-service");
    }

    @Bean
    public RestClient productRestClient(RestClient.Builder builder) {
        return restClient(builder, productServiceUrl, "product-service");
    }

    @Bean
    public RestClient inventoryRestClient(RestClient.Builder builder) {
        return restClient(builder, inventoryServiceUrl, "inventory-service");
    }

    private RestClient restClient(RestClient.Builder builder, String baseUrl, String serviceName) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        // Send the body (even an empty one) in one piece. Streaming mode makes the JDK throw instead of
        // returning a 401 answer, and a 401 must be an answer we can read.
        requestFactory.setOutputStreaming(false);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new DownstreamLoggingInterceptor(serviceName))
                .build();
    }
}
