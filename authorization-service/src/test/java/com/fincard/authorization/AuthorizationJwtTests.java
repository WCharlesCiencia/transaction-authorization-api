package com.fincard.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincard.authorization.api.AuthorizeTransactionResponse;
import com.fincard.authorization.api.TransactionController;
import com.fincard.authorization.config.SecurityConfig;
import com.fincard.authorization.service.TransactionIdempotencyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, AuthorizationJwtTests.TestJwtConfig.class})
class AuthorizationJwtTests {

    private static final String ISSUER = "https://issuer.example.test";
    private static final UUID REQUEST_ID = UUID.fromString("b2debb7a-3db8-4b64-a799-0b7a939a95df");

    @Autowired MockMvc mvc;
    @Autowired RSAKey trustedKey;
    @MockitoBean TransactionIdempotencyService service;

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsValidSignedToken() throws Exception {
        when(service.authorize(any())).thenReturn(
                new AuthorizeTransactionResponse(REQUEST_ID, 42L, "APPROVED", null));
        mvc.perform(request().header("Authorization", "Bearer " + token(trustedKey, ISSUER, 300)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsUntrustedSignature() throws Exception {
        RSAKey untrustedKey = new RSAKeyGenerator(2048).generate();
        mvc.perform(request().header("Authorization", "Bearer " + token(untrustedKey, ISSUER, 300)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsExpiredTokenAndUnexpectedIssuer() throws Exception {
        mvc.perform(request().header("Authorization", "Bearer " + token(trustedKey, ISSUER, -300)))
                .andExpect(status().isUnauthorized());
        mvc.perform(request().header("Authorization",
                        "Bearer " + token(trustedKey, "https://other.example.test", 300)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/transactions/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","cardId":1,"amount":100}
                        """.formatted(REQUEST_ID));
    }

    private String token(RSAKey key, String issuer, long secondsUntilExpiration) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("test-user").issuer(issuer)
                .expirationTime(Date.from(Instant.now().plusSeconds(secondsUntilExpiration))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfig {

        @Bean
        RSAKey trustedKey() throws Exception {
            return new RSAKeyGenerator(2048).generate();
        }

        @Bean
        JwtDecoder jwtDecoder(RSAKey trustedKey) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(trustedKey.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
