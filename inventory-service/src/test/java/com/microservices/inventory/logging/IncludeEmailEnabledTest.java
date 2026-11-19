package com.microservices.inventory.logging;

import com.microservices.inventory.controller.InventoryController;
import com.microservices.inventory.security.JwtTestSupport;
import com.microservices.inventory.security.WithTestJwtSecurity;
import com.microservices.inventory.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The default of app.logging.include-email is true, and the logging filters work inside a controller slice
 * (the way all the existing controller tests load the security config).
 */
@WebMvcTest(InventoryController.class)
@WithTestJwtSecurity
class IncludeEmailEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryService inventoryService;

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
    void byDefault_theEmailIsLogged() throws Exception {
        mockMvc.perform(get("/api/inventory/batch")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearerFor("77", "anna@example.com", "USER")));

        LogCapture.Line line = capture.accessLines("/api/inventory/batch").get(0);
        assertThat(line.text("userEmail")).isEqualTo("anna@example.com");
        assertThat(line.text("userId")).isEqualTo("77");
    }

    @Test
    void theDefaultAdminToken_ofTheControllerTests_isLoggedAsAdmin() throws Exception {
        mockMvc.perform(get("/api/inventory/batch"));

        LogCapture.Line line = capture.accessLines("/api/inventory/batch").get(0);
        assertThat(line.text("userRole")).isEqualTo("ADMIN");
    }
}
