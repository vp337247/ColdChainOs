package com.coldchainos.shared.outbox.infrastructure.persistence;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
