package com.fincard.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transaction_audit", schema = "audit")
public class TransactionAudit {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "event_type", nullable = false, columnDefinition = "text")
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TransactionAudit() {
    }
}
