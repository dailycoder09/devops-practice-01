package com.microservices.inventory.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * StockReservation Entity - the stock held for one line (one SKU) of one order
 *
 * order-service asks for a reservation per order line. The row remembers it, so that a repeated
 * reserve or release for the same order and SKU can be recognised and does not change stock twice.
 * The pair (order_id, sku_code) is unique.
 *
 * order_id belongs to order-service (its own database), so it is not a database-level foreign key.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Entity
@Table(name = "stock_reservations",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_stock_reservations_order_sku", columnNames = {"order_id", "sku_code"})
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "id")
public class StockReservation {

    /**
     * Unique identifier for the reservation
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order (in order-service) this reservation belongs to
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * SKU code of the reserved line
     */
    @Column(name = "sku_code", nullable = false, length = 50)
    private String skuCode;

    /**
     * Units taken from stock for this line
     */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * RESERVED or RELEASED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    /**
     * Timestamp when the reservation was made
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the last change (the release)
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
