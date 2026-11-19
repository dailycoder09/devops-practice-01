package com.microservices.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Cart Service Application
 *
 * Main entry point for the Cart service: every logged-in person has one cart with products and quantities.
 *
 * @author Microservices Team
 * @version 1.0
 */
@SpringBootApplication
public class CartServiceApplication {

    /**
     * Main method to start the application
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
