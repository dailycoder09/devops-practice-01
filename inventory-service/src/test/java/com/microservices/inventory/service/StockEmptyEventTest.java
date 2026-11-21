package com.microservices.inventory.service;

import com.microservices.inventory.client.ProductClient;
import com.microservices.inventory.dto.request.InventoryRequest;
import com.microservices.inventory.dto.response.InventoryResponse;
import com.microservices.inventory.events.EventPublisher;
import com.microservices.inventory.exception.InsufficientStockException;
import com.microservices.inventory.mapper.InventoryMapper;
import com.microservices.inventory.model.InventoryItem;
import com.microservices.inventory.model.StockReservation;
import com.microservices.inventory.repository.InventoryAuditLogRepository;
import com.microservices.inventory.repository.InventoryRepository;
import com.microservices.inventory.repository.StockReservationRepository;
import com.microservices.inventory.service.impl.InventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A SKU that goes from some stock to zero sends one STOCK_EMPTY event (after a reserve, an adjust or an update).
 * A SKU that stays above zero, stays at zero, or is refused sends nothing. The event never changes the answer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockEmptyEventTest {

    private static final String SKU = "APPLE-IP15P-128";

    private record Sent(String type, Map<String, Object> data) { }

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryAuditLogRepository inventoryAuditLogRepository;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private ProductClient productClient;
    @Mock
    private StockReservationRepository stockReservationRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private InventoryServiceImpl service;

    private final List<Sent> sent = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service.setEventPublisher((type, data) -> sent.add(new Sent(type, data)));
        when(transactionTemplate.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        when(inventoryMapper.toResponse(any(InventoryItem.class))).thenReturn(InventoryResponse.builder().skuCode(SKU).build());
        when(inventoryRepository.save(any(InventoryItem.class))).thenAnswer(call -> call.getArgument(0));
        when(stockReservationRepository.save(any(StockReservation.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static InventoryItem item(int quantity) {
        return InventoryItem.builder().id(1L).skuCode(SKU).quantityOnHand(quantity).reorderThreshold(10).build();
    }

    // ---------------------------------------------------------------- reserve

    @Test
    void aReserveThatTakesTheLastUnit_sendsStockEmpty_withTheSkuAndTheOrder() {
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(1)), Optional.of(item(0)));
        when(stockReservationRepository.findByOrderIdAndSkuCode(77L, SKU)).thenReturn(Optional.empty());
        when(inventoryRepository.decreaseIfEnough(SKU, 1)).thenReturn(1);

        service.reserveStock(SKU, 1, 77L);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type()).isEqualTo("STOCK_EMPTY");
        assertThat(sent.get(0).data()).containsEntry("sku", SKU).containsEntry("reason", "reserved").containsEntry("orderId", 77L);
    }

    @Test
    void aReserveThatLeavesStock_sendsNothing() {
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(5)), Optional.of(item(4)));
        when(stockReservationRepository.findByOrderIdAndSkuCode(77L, SKU)).thenReturn(Optional.empty());
        when(inventoryRepository.decreaseIfEnough(SKU, 1)).thenReturn(1);

        service.reserveStock(SKU, 1, 77L);

        assertThat(sent).isEmpty();
    }

    @Test
    void aRefusedReserve_sendsNothing() {
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(0)));
        when(stockReservationRepository.findByOrderIdAndSkuCode(77L, SKU)).thenReturn(Optional.empty());
        when(inventoryRepository.decreaseIfEnough(SKU, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.reserveStock(SKU, 1, 77L)).isInstanceOf(InsufficientStockException.class);

        assertThat(sent).isEmpty();
    }

    // ---------------------------------------------------------------- adjust

    @Test
    void anAdjustThatReachesZero_sendsStockEmpty() {
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(5)));

        service.adjustStock(SKU, -5);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type()).isEqualTo("STOCK_EMPTY");
        assertThat(sent.get(0).data()).containsEntry("sku", SKU).containsEntry("reason", "adjusted");
        assertThat(sent.get(0).data()).doesNotContainKey("orderId");
    }

    @Test
    void anAdjustThatLeavesStock_orAddsToAnEmptySku_orStaysAtZero_sendsNothing() {
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(5)));
        service.adjustStock(SKU, -2);

        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(0)));
        service.adjustStock(SKU, 5);
        service.adjustStock(SKU, 0);

        assertThat(sent).isEmpty();
    }

    // ---------------------------------------------------------------- update

    @Test
    void anUpdateToZero_sendsStockEmpty_butAnUpdateFromZeroToZeroDoesNot() {
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(item(7)));
        service.update(1L, InventoryRequest.builder().skuCode(SKU).quantityOnHand(0).build());
        assertThat(sent).extracting(Sent::type).containsExactly("STOCK_EMPTY");
        assertThat(sent.get(0).data()).containsEntry("reason", "updated");

        sent.clear();
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(item(0)));
        service.update(1L, InventoryRequest.builder().skuCode(SKU).quantityOnHand(0).build());
        assertThat(sent).isEmpty();
    }

    // ---------------------------------------------------------------- never harms the stock change

    @Test
    void theStockChangeIsStillAnswered_whenThePublisherFails() {
        service.setEventPublisher((EventPublisher) (type, data) -> {
            throw new IllegalStateException("must never reach the caller");
        });
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(5)));

        assertThat(service.adjustStock(SKU, -5).getSkuCode()).isEqualTo(SKU);
    }

    @Test
    void theStockChangeWorks_whenNoPublisherWasSet() {
        service.setEventPublisher(null);
        when(inventoryRepository.findBySkuCode(SKU)).thenReturn(Optional.of(item(5)));

        // Nothing is sent and nothing fails: a missing publisher is the same as no events.
        assertThatCodeDoesNotThrow(() -> service.adjustStock(SKU, -5));
    }

    private static void assertThatCodeDoesNotThrow(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            throw new AssertionError("expected no exception but got " + e, e);
        }
    }
}
