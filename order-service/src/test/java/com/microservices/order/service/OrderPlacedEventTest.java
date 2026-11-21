package com.microservices.order.service;

import com.microservices.order.client.CartClient;
import com.microservices.order.client.CartLine;
import com.microservices.order.client.InventoryClient;
import com.microservices.order.client.ProductClient;
import com.microservices.order.client.ProductInfo;
import com.microservices.order.dto.response.OrderItemResponse;
import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.events.EventPublisher;
import com.microservices.order.exception.OrderRejectedException;
import com.microservices.order.exception.StockRefusedException;
import com.microservices.order.model.OrderStatus;
import com.microservices.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * An order placed (CONFIRMED): one event ORDER_PLACED. A rejected order: no event. The event never changes the
 * answer of the checkout, and it carries no token.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPlacedEventTest {

    private static final long ORDER_ID = 42;

    private record Sent(String type, Map<String, Object> data) { }

    @Mock
    private CartClient cartClient;
    @Mock
    private ProductClient productClient;
    @Mock
    private InventoryClient inventoryClient;
    @Mock
    private OrderStore orderStore;

    private final List<Sent> sent = new ArrayList<>();
    private OrderServiceImpl service;
    private final Caller caller = new Caller(7L, "ann@example.com", false, "Bearer caller-token");

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(cartClient, productClient, inventoryClient, orderStore);
        service.setEventPublisher((type, data) -> sent.add(new Sent(type, data)));

        when(cartClient.readCart("Bearer caller-token"))
                .thenReturn(List.of(new CartLine("SKU-A", 2), new CartLine("SKU-B", 1)));
        when(productClient.findBySku("SKU-A")).thenReturn(Optional.of(new ProductInfo("Phone A", new BigDecimal("10.00"))));
        when(productClient.findBySku("SKU-B")).thenReturn(Optional.of(new ProductInfo("Case B", new BigDecimal("2.50"))));
        when(orderStore.createPending(eq(7L), eq("ann@example.com"), anyList())).thenReturn(pending());
    }

    private static OrderResponse pending() {
        return OrderResponse.builder()
                .id(ORDER_ID)
                .status(OrderStatus.PENDING)
                .total(new BigDecimal("22.50"))
                .items(List.of(
                        OrderItemResponse.builder().sku("SKU-A").name("Phone A").unitPrice(new BigDecimal("10.00"))
                                .quantity(2).lineTotal(new BigDecimal("20.00")).build(),
                        OrderItemResponse.builder().sku("SKU-B").name("Case B").unitPrice(new BigDecimal("2.50"))
                                .quantity(1).lineTotal(new BigDecimal("2.50")).build()))
                .build();
    }

    private static OrderResponse confirmed() {
        return pending().toBuilder().status(OrderStatus.CONFIRMED).build();
    }

    @Test
    void aConfirmedOrder_sendsOneOrderPlacedEvent_withTheFactsButNoToken() {
        when(orderStore.markConfirmed(ORDER_ID)).thenReturn(confirmed());
        when(cartClient.clear("Bearer caller-token")).thenReturn(true);

        service.placeOrder(caller);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type()).isEqualTo("ORDER_PLACED");
        assertThat(sent.get(0).data()).containsEntry("orderId", ORDER_ID)
                .containsEntry("userId", 7L)
                .containsEntry("email", "ann@example.com")
                .containsEntry("itemCount", 2)
                .containsEntry("status", "CONFIRMED");
        assertThat((BigDecimal) sent.get(0).data().get("total")).isEqualByComparingTo("22.50");
        assertThat(sent.get(0).data().toString()).doesNotContain("caller-token").doesNotContain("Bearer");
    }

    @Test
    void theEventIsStillSent_whenOnlyTheCartCleanUpFails() {
        when(orderStore.markConfirmed(ORDER_ID)).thenReturn(confirmed());
        when(cartClient.clear("Bearer caller-token")).thenReturn(false);

        service.placeOrder(caller);

        assertThat(sent).extracting(Sent::type).containsExactly("ORDER_PLACED");
    }

    @Test
    void aRejectedOrder_sendsNoEvent() {
        doThrow(new StockRefusedException("Not enough stock for SKU-B: wanted 1, available 0"))
                .when(inventoryClient).reserve("SKU-B", 1, ORDER_ID);

        assertThatThrownBy(() -> service.placeOrder(caller)).isInstanceOf(OrderRejectedException.class);

        assertThat(sent).isEmpty();
    }

    @Test
    void anOrderThatCouldNotBeConfirmed_sendsNoEvent() {
        when(orderStore.markConfirmed(ORDER_ID)).thenThrow(new IllegalStateException("database is gone"));

        assertThatThrownBy(() -> service.placeOrder(caller)).isInstanceOf(OrderRejectedException.class);

        assertThat(sent).isEmpty();
    }

    @Test
    void theOrderIsStillAnswered_whenThePublisherFails() {
        when(orderStore.markConfirmed(ORDER_ID)).thenReturn(confirmed());
        service.setEventPublisher((EventPublisher) (type, data) -> {
            throw new IllegalStateException("must never reach the caller");
        });

        assertThat(service.placeOrder(caller).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
