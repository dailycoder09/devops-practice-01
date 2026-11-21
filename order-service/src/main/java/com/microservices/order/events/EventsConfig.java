package com.microservices.order.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the publisher from two settings, both given from outside (the user data of the VM):
 * EVENTS_URL (the address of the Lambda function URL; empty = no events are sent) and EVENTS_SECRET
 * (must be set when EVENTS_URL is set). A wrong value stops the service at start-up with a clear message,
 * and the message never shows the values.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
public class EventsConfig {

    @Bean
    public EventPublisher eventPublisher(@Value("${events.url:}") String url,
                                         @Value("${events.secret:}") String secret,
                                         @Value("${spring.application.name:order-service}") String service) {
        return create(service, url, secret);
    }

    static EventPublisher create(String service, String url, String secret) {
        String address = url == null ? "" : url.trim();
        if (address.isEmpty()) {
            return EventPublisher.NONE;
        }
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            throw new IllegalStateException("EVENTS_URL must start with https:// (the address of the Lambda function URL) "
                    + "or be empty. The value is not shown.");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("EVENTS_SECRET must be set when EVENTS_URL is set.");
        }
        return new HttpEventPublisher(service, address, secret);
    }
}
