package com.fincard.authorization.api;

import com.fincard.authorization.service.AuthorizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final AuthorizationService authorizationService;

    public TransactionController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/authorize")
    public AuthorizeTransactionResponse authorize(@Valid @RequestBody AuthorizeTransactionRequest request) {
        return authorizationService.authorize(request);
    }
}
