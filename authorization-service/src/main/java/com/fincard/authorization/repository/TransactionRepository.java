package com.fincard.authorization.repository;

import com.fincard.authorization.entity.Transaction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByRequestId(UUID requestId);

    @Modifying
    @Query(value = """
            INSERT INTO transactions (request_id, card_id, amount, status)
            SELECT :requestId, id, :amount, 'PENDING'
            FROM cards
            WHERE id = :cardId
            ON CONFLICT (request_id) WHERE request_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int reserve(@Param("requestId") UUID requestId, @Param("cardId") Long cardId,
                @Param("amount") BigDecimal amount);
}
