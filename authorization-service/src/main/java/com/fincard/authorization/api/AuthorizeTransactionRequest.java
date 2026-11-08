package com.fincard.authorization.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record AuthorizeTransactionRequest(
        @NotNull UUID requestId,
        @NotNull @Positive Long cardId,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
