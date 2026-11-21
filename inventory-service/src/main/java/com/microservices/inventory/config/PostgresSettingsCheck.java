package com.microservices.inventory.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Startup check for the "postgres" profile.
 *
 * With this profile the service needs four settings (see application-postgres.yml). Spring Boot leaves an
 * unset ${DB_SCHEMA} as plain text instead of failing, so without this check a missing DB_SCHEMA would create
 * and use a schema literally named "${DB_SCHEMA}". This check runs before any bean (including the database
 * connection and Flyway) is created, and stops the service with a message that names the missing variable.
 * The values themselves (URL, user name, password) are never written into a message.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@Profile("postgres")
public class PostgresSettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String[] REQUIRED = {"DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_SCHEMA"};

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        check(environment);
    }

    static void check(Environment environment) {
        String profiles = Arrays.toString(environment.getActiveProfiles());
        for (String name : REQUIRED) {
            String value = environment.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("The profile \"postgres\" needs the environment variable " + name
                        + ", and it is missing. Set DB_URL, DB_USERNAME, DB_PASSWORD and DB_SCHEMA (active profiles: "
                        + profiles + ").");
            }
        }
        if (!environment.getProperty("DB_URL").startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("DB_URL must start with jdbc:postgresql: when the profile \"postgres\" is active "
                    + "(the value is not shown; active profiles: " + profiles + ").");
        }
    }
}
