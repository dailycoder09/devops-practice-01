package com.microservices.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order Service Application
 *
 * Main entry point for the Order service: a logged-in person places an order from their cart,
 * the stock is reserved in inventory-service, and the person can read their own orders.
 *
 * @author Microservices Team
 * @version 1.0
 */
@SpringBootApplication
public class OrderServiceApplication {

    /**
     * Main method to start the application
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
