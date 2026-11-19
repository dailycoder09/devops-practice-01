package com.microservices.cart.client;

import com.microservices.cart.config.RestClientConfig;
import com.microservices.cart.exception.ProductServiceUnavailableException;
import com.microservices.cart.support.FakeProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the product lookup against a fake product-service over real HTTP.
 */
class ProductClientImplTest {

    private FakeProductService fake;
    private ProductClientImpl client;

    @BeforeEach
    void start() {
        fake = new FakeProductService();
        client = new ProductClientImpl(RestClientConfig.createRestClient(fake.baseUrl(), 2000, 300));
    }

    @AfterEach
    void stop() {
        fake.stop();
    }

    @Test
    void knownSku_returnsNameAndPrice_andIgnoresTheOtherFields() {
        fake.addProduct("PHONE-1", "Test Phone", "999.99");

        Optional<ProductInfo> product = client.findBySku("PHONE-1");

        assertThat(product).isPresent();
        assertThat(product.get().name()).isEqualTo("Test Phone");
        assertThat(product.get().price()).isEqualByComparingTo("999.99");
    }

    @Test
    void unknownSku_isEmpty() {
        assertThat(client.findBySku("NOPE")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 500, 502, 503})
    void anyOtherStatus_meansTheProductServiceDidNotAnswer(int status) {
        fake.failWithStatus(status);

        assertThatThrownBy(() -> client.findBySku("PHONE-1"))
                .isInstanceOf(ProductServiceUnavailableException.class)
                .hasMessage("The product service did not answer. Please try again.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "null", "{}", "{\"name\":\"No price\"}",
            "{\"price\":5.00}", "{\"name\":\"\",\"price\":5.00}", "{\"name\":\"Text price\",\"price\":\"abc\"}",
            "{\"name\":\"Negative\",\"price\":-1}"})
    void anAnswerWeCannotUse_meansTheProductServiceDidNotAnswer(String body) {
        fake.answerWithRawBody("PHONE-1", body);

        assertThatThrownBy(() -> client.findBySku("PHONE-1"))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    void aProductServiceThatIsNotThere_meansTheProductServiceDidNotAnswer() {
        fake.stop();

        assertThatThrownBy(() -> client.findBySku("PHONE-1"))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    void aSlowAnswer_runsIntoTheReadTimeout() {
        fake.addProduct("PHONE-1", "Test Phone", "999.99");
        fake.delayMillis(1500);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.findBySku("PHONE-1"))
                .isInstanceOf(ProductServiceUnavailableException.class);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("gave up after the read timeout, not after the whole delay").isLessThan(1400);
    }

    @Test
    void theSkuIsSentAsOnePathPart() {
        fake.addProduct("A/B", "Slash", "1.00");

        client.findBySku("A/B");

        assertThat(fake.requests()).hasSize(1);
        assertThat(fake.requests().get(0).rawPath()).isEqualTo("/api/products/sku/A%2FB");
    }
}
