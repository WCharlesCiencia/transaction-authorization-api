package com.fincard.audit.event;

import java.time.Instant;
import java.util.UUID;

public record TransactionEvent(
        UUID eventId,
        UUID requestId,
        Long transactionId,
        String status,
        String reason,
        String eventType,
        Instant occurredAt
) {
}
