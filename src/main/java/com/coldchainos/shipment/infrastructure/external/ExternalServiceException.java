package com.coldchainos.shipment.infrastructure.external;

/**
 * Thrown when an external 3rd-party integration fails, triggers retries or trips a circuit breaker.
 */
public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
