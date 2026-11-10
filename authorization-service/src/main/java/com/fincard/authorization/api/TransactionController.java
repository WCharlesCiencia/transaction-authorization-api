package com.fincard.authorization.api;

import com.fincard.authorization.service.TransactionIdempotencyService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController implements TransactionApiDocumentation {

	private final TransactionIdempotencyService idempotencyService;

	public TransactionController(TransactionIdempotencyService idempotencyService) {

		this.idempotencyService = idempotencyService;
	}

	@PostMapping("/authorize")
	@Override
	public AuthorizeTransactionResponse authorize(@Valid @RequestBody AuthorizeTransactionRequest request) {

		return idempotencyService.authorize(request);
	}
	
	
}
