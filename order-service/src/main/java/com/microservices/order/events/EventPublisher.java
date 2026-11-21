package com.microservices.order.events;

import java.util.Map;

/**
 * Tells the outside world that something happened (a new user registered, an order was placed, a stock ran out).
 *
 * Sending is best effort: an implementation never throws and never makes the caller wait. A registration or an
 * order must work even when nobody is listening.
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface EventPublisher {

    /** Does nothing. Used when EVENTS_URL is not set. */
    EventPublisher NONE = (type, data) -> { };

    /**
     * @param type the kind of event, for example USER_REGISTERED
     * @param data the facts of the event; never a password, a hash or a token
     */
    void publish(String type, Map<String, Object> data);
}
