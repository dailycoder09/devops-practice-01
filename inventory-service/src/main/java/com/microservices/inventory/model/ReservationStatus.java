package com.microservices.inventory.model;

/**
 * State of a {@link StockReservation}: RESERVED (the units were taken from stock) or RELEASED
 * (they were put back). A reservation never goes back from RELEASED to RESERVED.
 *
 * @author Microservices Team
 * @version 1.0
 */
public enum ReservationStatus {
    RESERVED,
    RELEASED
}
