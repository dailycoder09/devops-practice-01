package com.microservices.product.logging;

import com.microservices.product.controller.ProductController;
import com.microservices.product.security.JwtTestSupport;
import com.microservices.product.security.WithTestJwtSecurity;
import com.microservices.product.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test 9: with app.logging.include-email=false the email never reaches the logs, but the user id and role still do.
 * (Runs in a controller slice: the real security config and both logging filters, a mocked service.)
 */
@WebMvcTest(ProductController.class)
@WithTestJwtSecurity
@TestPropertySource(properties = "app.logging.include-email=false")
class IncludeEmailDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.start();
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void theEmail_isLeftOut_butIdAndRoleStay() throws Exception {
        mockMvc.perform(get("/api/products/categories")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER")));

        LogCapture.Line line = capture.accessLines("/api/products/categories").get(0);
        assertThat(line.text("userId")).isEqualTo("77");
        assertThat(line.text("userRole")).isEqualTo("USER");
        assertThat(line.has("userEmail")).isFalse();
        assertThat(capture.allText()).doesNotContain("anna@example.com");
    }
}
