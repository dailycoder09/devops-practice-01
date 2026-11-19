package com.microservices.cart.logging;

/**
 * The names of the MDC keys this service uses. The JSON log shows every MDC key as a top-level field.
 * "traceId" and "spanId" are set by the tracing library, not by this service.
 *
 * @author Microservices Team
 * @version 1.0
 */
public final class MdcKeys {

    public static final String CLIENT_IP = "clientIp";
    public static final String USER_ID = "userId";
    public static final String USER_EMAIL = "userEmail";
    public static final String USER_ROLE = "userRole";

    /** The caller of a request without a (valid) login. */
    public static final String ANONYMOUS = "anonymous";

    private MdcKeys() {
    }
}
