package com.microservices.order.service;

import com.microservices.order.client.CartClient;
import com.microservices.order.client.CartLine;
import com.microservices.order.client.InventoryClient;
import com.microservices.order.client.ProductClient;
import com.microservices.order.client.ProductInfo;
import com.microservices.order.dto.response.OrderItemResponse;
import com.microservices.order.dto.response.OrderResponse;
import com.microservices.order.exception.OrderRejectedException;
import com.microservices.order.exception.StockRefusedException;
import com.microservices.order.model.OrderStatus;
import com.microservices.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * The orchestration of the checkout, with every other part replaced by a mock. The fake-server tests cover the
 * normal and the expected failure paths; this class covers what must still be safe when something unexpected
 * is thrown in the middle (a bug, a database error): stock that was reserved is released, and the order does not
 * stay PENDING.
 */
// Lenient on purpose: a stub for reserve("SKU-B") must not make the call reserve("SKU-A") an error.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    private static final long ORDER_ID = 42;

    @Mock
    private CartClient cartClient;
    @Mock
    private ProductClient productClient;
    @Mock
    private InventoryClient inventoryClient;
    @Mock
    private OrderStore orderStore;

    private OrderServiceImpl service;
    private final Caller caller = new Caller(7L, "ann@example.com", false, "Bearer caller-token");

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(cartClient, productClient, inventoryClient, orderStore);

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
    void happyPath_savesThenReservesThenConfirmsThenClearsTheCart() {
        when(orderStore.markConfirmed(ORDER_ID)).thenReturn(confirmed());
        when(cartClient.clear("Bearer caller-token")).thenReturn(true);

        OrderResponse result = service.placeOrder(caller);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.getCartCleared()).isTrue();
        InOrder order = inOrder(orderStore, inventoryClient, cartClient);
        order.verify(orderStore).createPending(eq(7L), eq("ann@example.com"), anyList());
        order.verify(inventoryClient).reserve("SKU-A", 2, ORDER_ID);
        order.verify(inventoryClient).reserve("SKU-B", 1, ORDER_ID);
        order.verify(orderStore).markConfirmed(ORDER_ID);
        order.verify(cartClient).clear("Bearer caller-token");
        verify(inventoryClient, never()).release(anyString(), anyLong());
    }

    @Test
    void anUnexpectedErrorWhileReserving_releasesEveryLine_rejectsTheOrder_andAnswers503() {
        doThrow(new IllegalStateException("a bug")).when(inventoryClient).reserve("SKU-B", 1, ORDER_ID);

        assertThatThrownBy(() -> service.placeOrder(caller))
                .isInstanceOfSatisfying(OrderRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getOrderId()).isEqualTo(ORDER_ID);
                    assertThat(e.getMessage())
                            .isEqualTo("Something went wrong while placing your order. Please try again.");
                });

        verify(inventoryClient).release("SKU-A", ORDER_ID);
        verify(inventoryClient).release("SKU-B", ORDER_ID);
        verify(orderStore).markRejected(ORDER_ID, "Something went wrong while placing your order. Please try again.");
        verify(orderStore, never()).markConfirmed(anyLong());
        verify(cartClient, never()).clear(anyString());
    }

    @Test
    void ifConfirmingTheOrderFails_everyLineIsReleased_andTheOrderIsRejected() {
        when(orderStore.markConfirmed(ORDER_ID)).thenThrow(new IllegalStateException("database is gone"));

        assertThatThrownBy(() -> service.placeOrder(caller))
                .isInstanceOfSatisfying(OrderRejectedException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(inventoryClient).release("SKU-A", ORDER_ID);
        verify(inventoryClient).release("SKU-B", ORDER_ID);
        verify(orderStore).markRejected(eq(ORDER_ID), anyString());
        verify(cartClient, never()).clear(anyString());
    }

    @Test
    void releasesAreSentBeforeTheOrderIsMarkedRejected() {
        doThrow(new StockRefusedException("Not enough stock for SKU-B: wanted 1, available 0"))
                .when(inventoryClient).reserve("SKU-B", 1, ORDER_ID);

        assertThatThrownBy(() -> service.placeOrder(caller))
                .isInstanceOfSatisfying(OrderRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Not enough stock for SKU-B: wanted 1, available 0");
                });

        InOrder order = inOrder(inventoryClient, orderStore);
        order.verify(inventoryClient).release("SKU-A", ORDER_ID);
        order.verify(inventoryClient).release("SKU-B", ORDER_ID);
        order.verify(orderStore).markRejected(ORDER_ID, "Not enough stock for SKU-B: wanted 1, available 0");
    }

    @Test
    void ifMarkingTheOrderRejectedFails_theCallerStillGetsTheStockAnswer() {
        doThrow(new StockRefusedException("Not enough stock for SKU-A: wanted 2, available 1"))
                .when(inventoryClient).reserve("SKU-A", 2, ORDER_ID);
        doThrow(new IllegalStateException("database is gone")).when(orderStore).markRejected(anyLong(), anyString());

        assertThatThrownBy(() -> service.placeOrder(caller))
                .isInstanceOfSatisfying(OrderRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getOrderId()).isEqualTo(ORDER_ID);
                });

        verify(inventoryClient).release("SKU-A", ORDER_ID);
        verify(inventoryClient).release("SKU-B", ORDER_ID);
    }

    @Test
    void ifTheReleaseOfOneLineThrows_theOtherLinesAreStillReleased() {
        doThrow(new StockRefusedException("Not enough stock for SKU-B: wanted 1, available 0"))
                .when(inventoryClient).reserve("SKU-B", 1, ORDER_ID);
        doThrow(new IllegalStateException("a bug")).when(inventoryClient).release("SKU-A", ORDER_ID);

        assertThatThrownBy(() -> service.placeOrder(caller)).isInstanceOf(OrderRejectedException.class);

        verify(inventoryClient).release("SKU-B", ORDER_ID);
        verify(orderStore).markRejected(eq(ORDER_ID), anyString());
    }

    @Test
    void ifClearingTheCartThrowsUnexpectedly_theOrderStaysConfirmed_withCartClearedFalse() {
        when(orderStore.markConfirmed(ORDER_ID)).thenReturn(confirmed());
        when(cartClient.clear("Bearer caller-token")).thenThrow(new IllegalStateException("a bug"));

        OrderResponse result = service.placeOrder(caller);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.getCartCleared()).isFalse();
        verify(inventoryClient, never()).release(anyString(), anyLong());
        verify(orderStore, never()).markRejected(anyLong(), anyString());
    }
}
