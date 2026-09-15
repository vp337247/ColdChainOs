package com.coldchainos.shared.domain;

/**
 * Base unchecked exception for all business domain invariant violations in ColdChainOS.
 */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
