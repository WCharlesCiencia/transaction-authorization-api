package com.fincard.authorization.repository;

import com.fincard.authorization.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

public interface CardRepository extends JpaRepository<Card, Long> {
    @Transactional
    @Query(value = "SELECT debit_card(:cardId, :amount)", nativeQuery = true)
    boolean debitCard(@Param("cardId") Long cardId, @Param("amount") BigDecimal amount);
}
