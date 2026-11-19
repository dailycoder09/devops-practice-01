package com.microservices.order.exception;

/**
 * inventory-service could not be used for one line: it did not answer in time, it was unreachable,
 * or it answered with an error status that is not a clear "no".
 *
 * @author Microservices Team
 * @version 1.0
 */
public class StockServiceException extends RuntimeException {

    public StockServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
