package com.microservices.product.security;

import com.microservices.product.config.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For {@code @WebMvcTest} controller tests. Loads the real {@link SecurityConfig} with the test secret and
 * lets every request carry a valid ADMIN token by default, so these tests keep checking what the
 * controllers do. The security rules themselves are tested in {@link SecurityRulesTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({SecurityConfig.class, WithTestJwtSecurity.AdminByDefault.class})
@TestPropertySource(properties = "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET)
public @interface WithTestJwtSecurity {

    @TestConfiguration
    class AdminByDefault {

        @Bean
        MockMvcBuilderCustomizer adminTokenOnEveryRequest() {
            return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/")
                    .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN")));
        }
    }
}
