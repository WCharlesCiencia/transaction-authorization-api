package com.fincard.authorization.service;

import com.fincard.authorization.api.AuthorizeTransactionRequest;
import com.fincard.authorization.api.AuthorizeTransactionResponse;
import com.fincard.authorization.entity.Transaction;
import com.fincard.authorization.event.TransactionEvent;
import com.fincard.authorization.repository.CardRepository;
import com.fincard.authorization.repository.TransactionOutboxRepository;
import com.fincard.authorization.repository.TransactionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
public class AuthorizationService {

    private final CardRepository cards;
    private final TransactionRepository transactions;
    private final TransactionOutboxRepository outbox;
    private final JsonMapper jsonMapper;

	public AuthorizationService(CardRepository cards, TransactionRepository transactions,
			TransactionOutboxRepository outbox, JsonMapper jsonMapper) {
		this.cards = cards;
		this.transactions = transactions;
		this.outbox = outbox;
		this.jsonMapper = jsonMapper;
	}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AuthorizeTransactionResponse authorize(@NotNull @Valid AuthorizeTransactionRequest request) {
        var existing = transactions.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replay(request, existing.get());
        }

        int reserved = transactions.reserve(request.requestId(), request.cardId(), request.amount());
        if (reserved == 0) {
            // After a competing insert commits, this statement gets a new READ COMMITTED snapshot.
            return transactions.findByRequestId(request.requestId())
                    .map(transaction -> replay(request, transaction))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND"));
        }

        Transaction transaction = transactions.findByRequestId(request.requestId()).orElseThrow();
        transaction.complete(cards.debitCard(request.cardId(), request.amount()));
        AuthorizeTransactionResponse result = response(request, transaction);
        if ("APPROVED".equals(result.status())) {
            TransactionEvent event = new TransactionEvent(UUID.randomUUID(), result.requestId(),
                    result.transactionId(), result.status(), result.reason(), "TRANSACTION_APPROVED", Instant.now());
            outbox.insert(event.eventId(), event.transactionId(), jsonMapper.writeValueAsString(event));
        }
        return result;
    }

    private AuthorizeTransactionResponse replay(AuthorizeTransactionRequest request, Transaction transaction) {
        if (!transaction.getCard().getId().equals(request.cardId())
                || transaction.getAmount().compareTo(request.amount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
        }
        if ("PENDING".equals(transaction.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_PENDING");
        }
        return response(request, transaction);
    }

	private AuthorizeTransactionResponse response(AuthorizeTransactionRequest request, Transaction transaction) {
		String reason = "DECLINED".equals(transaction.getStatus()) ? "TRANSACTION_DECLINED" : null;
		return new AuthorizeTransactionResponse(request.requestId(), transaction.getId(), transaction.getStatus(),
				reason);
	}
}
