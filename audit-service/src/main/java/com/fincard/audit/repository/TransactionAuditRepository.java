package com.fincard.audit.repository;

import com.fincard.audit.entity.TransactionAudit;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TransactionAuditRepository extends Repository<TransactionAudit, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO audit.transaction_audit
                (event_id, request_id, transaction_id, status, reason, event_type, occurred_at)
            VALUES (:eventId, :requestId, :transactionId, :status, :reason, :eventType, :occurredAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("requestId") UUID requestId,
                       @Param("transactionId") Long transactionId, @Param("status") String status,
                       @Param("reason") String reason, @Param("eventType") String eventType,
                       @Param("occurredAt") Instant occurredAt);
}
