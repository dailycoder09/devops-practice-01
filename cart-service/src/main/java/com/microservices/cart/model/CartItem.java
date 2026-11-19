package com.microservices.cart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Cart Item Entity - one product in the cart of one user
 *
 * The cart stores only the SKU and the quantity. Name and price are looked up in product-service
 * every time the cart is shown. A user can have each SKU only once (unique key on user_id and sku_code).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "id")
public class CartItem {

    /**
     * Unique identifier of the row. Items of a cart are shown in the order of this id (the order they were added)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user who owns the cart (the "sub" claim of the token)
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Stock Keeping Unit code of the product
     */
    @Column(name = "sku_code", nullable = false, length = 50)
    private String skuCode;

    /**
     * How many of the product, 1 to 99
     */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * Timestamp when the item was added
     * Automatically populated by Hibernate
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the item was last changed
     * Automatically updated by Hibernate on each modification
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
