package com.fincard.audit.service;

import com.fincard.audit.event.TransactionEvent;
import com.fincard.audit.repository.TransactionAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionAuditService {

    private final TransactionAuditRepository repository;

    public TransactionAuditService(TransactionAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean record(TransactionEvent event) {
        return repository.insertIfAbsent(event.eventId(), event.requestId(), event.transactionId(),
                event.status(), event.reason(), event.eventType(), event.occurredAt()) == 1;
    }
}
