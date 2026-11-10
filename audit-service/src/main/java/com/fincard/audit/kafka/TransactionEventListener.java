package com.fincard.audit.kafka;

import com.fincard.audit.event.TransactionEvent;
import com.fincard.audit.service.TransactionAuditService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventListener {

    private final TransactionAuditService audit;

    public TransactionEventListener(TransactionAuditService audit) {
        this.audit = audit;
    }

    @KafkaListener(topics = "${app.kafka.topics.transaction-events}")
    public void onTransactionEvent(TransactionEvent event) {
        if (event.eventId() == null || event.requestId() == null || event.transactionId() == null
                || event.status() == null || event.status().isBlank() || event.status().length() > 20
                || event.eventType() == null || event.eventType().isBlank() || event.occurredAt() == null) {
            throw new IllegalArgumentException("Transaction event has missing or invalid required fields");
        }
        audit.record(event);
    }
}
