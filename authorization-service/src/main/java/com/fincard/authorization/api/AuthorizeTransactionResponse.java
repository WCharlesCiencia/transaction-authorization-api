package com.fincard.authorization.api;

import java.util.UUID;

public record AuthorizeTransactionResponse(UUID requestId, Long transactionId, String status, String reason) {
}
