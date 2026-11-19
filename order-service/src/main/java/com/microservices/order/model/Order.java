package com.microservices.order.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Order Entity - what one person ordered at one moment
 *
 * The lines hold a copy of the name and unit price that product-service gave at checkout.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "items")
@EqualsAndHashCode(of = "id")
public class Order {

    /**
     * Unique identifier of the order (it is also the order id sent to inventory-service)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user id: the "sub" claim of the token
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The "email" claim of the token (can be empty)
     */
    @Column(name = "user_email", length = 255)
    private String userEmail;

    /**
     * PENDING, CONFIRMED or REJECTED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /**
     * Sum of the line totals, two decimals
     */
    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /**
     * Plain words that say why the order was rejected (empty otherwise)
     */
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /**
     * Timestamp when the order was created
     * Automatically populated by Hibernate
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the order was last updated
     * Automatically updated by Hibernate on each modification
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * The lines of the order, in the order they were added
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Adds a line and connects it to this order
     *
     * @param item the line
     */
    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }
}
