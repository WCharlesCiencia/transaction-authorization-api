package com.fincard.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincard.authorization.api.AuthorizationErrorHandler;
import com.fincard.authorization.api.AuthorizeTransactionResponse;
import com.fincard.authorization.api.TransactionController;
import com.fincard.authorization.service.AuthorizationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AuthorizationHttpTests {

    private static final UUID REQUEST_ID = UUID.fromString("b2debb7a-3db8-4b64-a799-0b7a939a95df");
    private final AuthorizationService service = mock(AuthorizationService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new TransactionController(service))
                    .setControllerAdvice(new AuthorizationErrorHandler())
                    .build();

    @Test
    void returnsBadRequestBeforeCallingService() throws Exception {
        for (String amount : new String[]{"0", "-1", "null", "1.001"}) {
            mvc.perform(request(amount)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void returnsDeclinedAsProcessedDecision() throws Exception {
        when(service.authorize(any())).thenReturn(
                new AuthorizeTransactionResponse(REQUEST_ID, 42L, "DECLINED", "TRANSACTION_DECLINED"));

        mvc.perform(request("100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.transactionId").value(42))
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.reason").value("TRANSACTION_DECLINED"));
    }

    @Test
    void mapsMissingCardAndConflictingRequest() throws Exception {
        when(service.authorize(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND"))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT"));

        mvc.perform(request("100")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
        mvc.perform(request("100")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String amount) {
        return post("/api/transactions/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","cardId":1,"amount":%s}
                        """.formatted(REQUEST_ID, amount));
    }
}
