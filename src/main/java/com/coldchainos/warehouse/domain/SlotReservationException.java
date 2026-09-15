package com.coldchainos.warehouse.domain;

import com.coldchainos.shared.domain.DomainException;

public class SlotReservationException extends DomainException {

    public SlotReservationException(String message) {
        super(message);
    }
}
