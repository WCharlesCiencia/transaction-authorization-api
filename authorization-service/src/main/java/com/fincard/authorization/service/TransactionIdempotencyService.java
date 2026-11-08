package com.fincard.authorization.service;

import com.fincard.authorization.api.AuthorizeTransactionRequest;
import com.fincard.authorization.api.AuthorizeTransactionResponse;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionIdempotencyService {

	private static final Logger LOG = Logger.getLogger(TransactionIdempotencyService.class.getName());

	private final RedisIdempotencyService redis;
	private final AuthorizationService authorizationService;

	public TransactionIdempotencyService(RedisIdempotencyService redis, AuthorizationService authorizationService) {

		this.redis = redis;
		this.authorizationService = authorizationService;
	}

	public AuthorizeTransactionResponse authorize(AuthorizeTransactionRequest request) {

		String requestId = request.requestId().toString();
		String token = UUID.randomUUID().toString();

		boolean acquired;

		try {
			acquired = redis.acquireLock(requestId, token);
		} catch (DataAccessException ex) {

			LOG.log(Level.WARNING, "Redis no disponible. Usando PostgreSQL.", ex);

			return authorizationService.authorize(request);
		}

		// Otra solicitud esta procesando el mismo requestId.
		if (!acquired) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_PENDING");
		}

		try {
			// La autorizacion conserva su propia transaccion.
			return authorizationService.authorize(request);
		} finally {
			try {
				redis.releaseLock(requestId, token);
			} catch (DataAccessException ex) {
				LOG.log(Level.WARNING, "No fue posible liberar el bloqueo Redis.", ex);
			}
		}
	}
}
