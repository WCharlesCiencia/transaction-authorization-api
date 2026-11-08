package com.fincard.authorization.service;

import com.fincard.authorization.api.AuthorizeTransactionResponse;

public interface ApprovedTransactionEventPublisher {
    void publish(AuthorizeTransactionResponse approvedTransaction);
}
