package com.microservices.inventory.model;

/**
 * The kind of mutation an {@link InventoryAuditLog} entry records.
 *
 * @author Microservices Team
 * @version 1.0
 */
public enum ChangeType {
    CREATED,
    ADJUSTED,
    UPDATED,
    DELETED,
    /** Stock was taken for an order (reserve) */
    RESERVED,
    /** Reserved stock was put back (release) */
    RELEASED
}
