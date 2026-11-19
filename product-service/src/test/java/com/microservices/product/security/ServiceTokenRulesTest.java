package com.microservices.product.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * A SERVICE token (made by order-service for its stock calls) is a valid token, but it is only meant for
 * inventory-service. Here it must be refused with 403 on every write endpoint, because they all need ADMIN.
 */
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "spring.datasource.url=jdbc:h2:mem:service-token-rules-test;DB_CLOSE_DELAY=-1",
        "server-info.cloud-metadata.enabled=false",
        // Nothing listens here, so this test can never call a real inventory-service.
        "product.inventory-service.url=http://localhost:1"
})
@AutoConfigureMockMvc
class ServiceTokenRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void aServiceToken_getsA403ProblemResponse_onEveryWriteEndpoint() throws Exception {
        // The list of write endpoints comes from the controllers themselves, so an endpoint added later is checked too.
        List<String> checked = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS) {
                    continue;
                }
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    String path = pattern.replaceAll("\\{[^}]*}", "1");
                    checked.add(method + " " + path);
                    expectForbidden(method.name(), path);
                }
            }
        }

        // Also the write routes written down by hand, and a path that does not exist (every write needs ADMIN)
        expectForbidden("POST", "/api/products");
        expectForbidden("PUT", "/api/products/999999");
        expectForbidden("PUT", "/api/products/999999/images");
        expectForbidden("PATCH", "/api/products/999999");
        expectForbidden("DELETE", "/api/products/999999");
        expectForbidden("POST", "/api/no-such-path");

        assertThat(checked).as("write endpoints found in the controllers")
                .contains("POST /api/products", "PUT /api/products/1", "PUT /api/products/1/images",
                        "DELETE /api/products/1");
    }

    private void expectForbidden(String method, String path) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("SERVICE")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("SERVICE token on " + method + " " + path).isEqualTo(403);
        assertThat(response.getContentType()).as("content type of the 403 on " + method + " " + path)
                .isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"title\":\"Forbidden\"").contains("\"status\":403");
    }
}
