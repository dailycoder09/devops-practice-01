package com.microservices.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User Service Application
 *
 * Main entry point for the User service: register, login (JWT) and profile.
 *
 * @author Microservices Team
 * @version 1.0
 */
@SpringBootApplication
public class UserServiceApplication {

    /**
     * Main method to start the application
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
