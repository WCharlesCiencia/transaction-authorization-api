package com.fincard.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated
    private LocalDateTime createdAt;

    protected Transaction() {
    }

    public Transaction(Card card, BigDecimal amount, String status) {
        this.card = card;
        this.amount = amount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Card getCard() {
        return card;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public void complete(boolean approved) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Only pending transactions can be completed");
        }
        status = approved ? "APPROVED" : "DECLINED";
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
