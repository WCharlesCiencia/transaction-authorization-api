package com.fincard.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_number", nullable = false, unique = true, length = 20)
    private String cardNumber;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "card_type", nullable = false, length = 30)
    private String cardType;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    protected Card() {
    }

    public Card(String cardNumber, LocalDate expirationDate, String status, String cardType,
                BigDecimal availableBalance) {
        this.cardNumber = cardNumber;
        this.expirationDate = expirationDate;
        this.status = status;
        this.cardType = cardType;
        this.availableBalance = availableBalance;
    }

    public Long getId() {
        return id;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public String getStatus() {
        return status;
    }

    public String getCardType() {
        return cardType;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }
}
