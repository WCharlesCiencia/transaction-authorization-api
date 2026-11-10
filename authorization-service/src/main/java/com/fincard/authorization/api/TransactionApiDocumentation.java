package com.fincard.authorization.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

public interface TransactionApiDocumentation {

	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Authorize a card transaction", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthorizeTransactionRequest.class), examples = @ExampleObject(value = """
			{"requestId":"3b7875c1-cd43-48ea-a1bb-c634a926e6fa","cardId":1,"amount":25.50}
			"""))))
	@ApiResponse(responseCode = "200", description = "Authorization processed. Check status for the decision.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthorizeTransactionResponse.class), examples = {
			@ExampleObject(name = "Approved", value = """
					{"requestId":"3b7875c1-cd43-48ea-a1bb-c634a926e6fa","transactionId":42,"status":"APPROVED","reason":null}
					"""),
			@ExampleObject(name = "Declined", value = """
					{"requestId":"3b7875c1-cd43-48ea-a1bb-c634a926e6fa","transactionId":42,"status":"DECLINED","reason":"TRANSACTION_DECLINED"}
					""") }))
	@ApiResponse(responseCode = "400", description = "Malformed JSON or invalid request fields.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
	@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid JWT.", content = @Content)
	@ApiResponse(responseCode = "404", description = "Card not found.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthorizationErrorHandler.ApiError.class), examples = @ExampleObject(value = "{\"code\":\"CARD_NOT_FOUND\"}")))
	@ApiResponse(responseCode = "409", description = "Request ID conflicts with a previous request or is still pending.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthorizationErrorHandler.ApiError.class), examples = {
			@ExampleObject(name = "Request ID conflict", value = "{\"code\":\"REQUEST_ID_CONFLICT\"}"),
			@ExampleObject(name = "Request pending", value = "{\"code\":\"REQUEST_PENDING\"}") }))
    AuthorizeTransactionResponse authorize(AuthorizeTransactionRequest request);
}
