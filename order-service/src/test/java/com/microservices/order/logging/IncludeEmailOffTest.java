package com.microservices.order.logging;

import com.microservices.order.support.LogCapture.Line;
import com.microservices.order.support.LoggingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 9 of the logging contract: with app.logging.include-email=false no email is in the MDC and none is in any
 * log line. (The order itself still keeps the email, which is data and not a log.)
 */
@TestPropertySource(properties = "app.logging.include-email=false")
class IncludeEmailOffTest extends LoggingTestBase {

    @Test
    void theAccessLine_hasNoUserEmail_butTheRestOfTheUser() throws Exception {
        mockMvc.perform(getWith("/api/orders", userToken)).andExpect(status().isOk());

        Line line = logs.accessLine("GET", "/api/orders");
        assertThat(line.mdc("userId")).isEqualTo(String.valueOf(userId));
        assertThat(line.mdc("userRole")).isEqualTo("USER");
        assertThat(line.mdc()).doesNotContainKey("userEmail");
    }

    @Test
    void aWholeCheckout_neverLogsTheEmail_inAnyLine() throws Exception {
        shop.addToCart(userId, "SKU-A", 1, "100.00");

        MvcResult result = checkout(userToken).andExpect(status().isCreated()).andReturn();

        assertThat(logs.lines()).isNotEmpty();
        assertThat(logs.allText()).doesNotContain("user" + userId + "@example.com").doesNotContain("@example.com");
        assertThat(logs.lines().stream().filter(line -> line.mdc().containsKey("userEmail"))).isEmpty();
        // The order JSON still has the email: it belongs to the order, it is not a log.
        assertThat(result.getResponse().getContentAsString()).contains("user" + userId + "@example.com");
    }

    @Test
    void theSecurityEvents_alsoLeaveTheEmailOut() throws Exception {
        mockMvc.perform(getWith("/actuator/env", userToken)).andExpect(status().isForbidden());

        assertThat(logs.single("access_denied").mdc()).doesNotContainKey("userEmail");
        assertThat(logs.allText()).doesNotContain("@example.com");
    }
}
