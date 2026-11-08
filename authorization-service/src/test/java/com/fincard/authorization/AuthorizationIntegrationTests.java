package com.fincard.authorization;

import static org.junit.jupiter.api.Assertions.*;

import com.fincard.authorization.api.AuthorizeTransactionRequest;
import com.fincard.authorization.api.AuthorizeTransactionResponse;
import com.fincard.authorization.service.AuthorizationService;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "FINCARD_RUN_POSTGRES_TESTS", matches = "true")
class AuthorizationIntegrationTests {

    @Autowired AuthorizationService authorization;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Long> cards = new ArrayList<>();

    @AfterEach
    void cleanUpFixtures() {
        for (Long card : cards) {
            jdbc.update("DELETE FROM transactions WHERE card_id = ?", card);
            jdbc.update("DELETE FROM cards WHERE id = ?", card);
        }
    }

    @Test
    void approvesAndReplaysWithoutChargingTwice() {
        long card = createCard("ACTIVE", 1, "500");
        var request = request(card, "100");
        var approved = authorization.authorize(request);

        assertEquals("APPROVED", approved.status());
        assertEquals(approved, authorization.authorize(
                new AuthorizeTransactionRequest(request.requestId(), card, new BigDecimal("100.00"))));
        assertEquals(1, countTransactions(card));
        assertBalance(card, "400");
        assertStatus(HttpStatus.CONFLICT, new AuthorizeTransactionRequest(
                request.requestId(), card, new BigDecimal("101")));
        assertStatus(HttpStatus.CONFLICT, new AuthorizeTransactionRequest(
                request.requestId(), Long.MAX_VALUE, request.amount()));
    }

    @Test
    void persistsRejectionsWithoutChangingBalance() {
        long blocked = createCard("BLOCKED", 1, "500");
        long expired = createCard("ACTIVE", -1, "500");
        long insufficient = createCard("ACTIVE", 1, "50");

        for (long card : List.of(blocked, expired, insufficient)) {
            BigDecimal before = balance(card);
            var request = request(card, "100");
            var declined = authorization.authorize(request);

            assertEquals("DECLINED", declined.status());
            assertEquals("TRANSACTION_DECLINED", declined.reason());
            assertEquals(declined, authorization.authorize(request));
            assertEquals(1, countTransactions(card));
            assertEquals(before, balance(card));
        }
    }

    @Test
    void rejectsInvalidAmountsAndMissingCard() {
        long card = createCard("ACTIVE", 1, "500");
        for (String invalid : List.of("0", "-1", "0.001", "100000000000000000")) {
            assertThrows(ConstraintViolationException.class, () -> authorization.authorize(request(card, invalid)));
        }
        assertThrows(ConstraintViolationException.class, () -> authorization.authorize(
                new AuthorizeTransactionRequest(UUID.randomUUID(), card, null)));
        assertStatus(HttpStatus.NOT_FOUND, request(Long.MAX_VALUE, "100"));
        assertEquals(0, countTransactions(card));
        assertBalance(card, "500");
    }

    @Test
    void concurrentDebitsPreserveBalance() throws Exception {
        long card = createCard("ACTIVE", 0, "2000000");
        var results = concurrently(List.of(request(card, "100000"), request(card, "200000"), request(card, "300000")));

        assertTrue(results.stream().allMatch(result -> "APPROVED".equals(result.status())));
        assertBalance(card, "1400000");
        assertEquals(3, countTransactions(card));
    }

    @Test
    void concurrentDebitsCannotOverdraw() throws Exception {
        long card = createCard("ACTIVE", 1, "400000");
        var requests = List.of(request(card, "100000"), request(card, "200000"), request(card, "300000"));
        var results = concurrently(requests);

        BigDecimal expected = new BigDecimal("400000");
        for (int i = 0; i < results.size(); i++) {
            if ("APPROVED".equals(results.get(i).status())) {
                expected = expected.subtract(requests.get(i).amount());
            }
        }
        assertTrue(expected.signum() >= 0);
        assertTrue(results.stream().anyMatch(result -> "DECLINED".equals(result.status())));
        assertBalance(card, expected.toPlainString());
        assertEquals(3, countTransactions(card));
    }

    @Test
    void concurrentReplaysShareOneDebit() throws Exception {
        long card = createCard("ACTIVE", 1, "500");
        var request = request(card, "100");
        var results = concurrently(List.of(request, request, request));

        assertEquals("APPROVED", results.getFirst().status());
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        assertBalance(card, "400");
        assertEquals(1, countTransactions(card));
    }

    @Test
    void concurrentRequestsWithDifferentAmountsOnlyDebitTheWinner() throws Exception {
        long card = createCard("ACTIVE", 1, "500");
        var first = request(card, "100");
        var second = new AuthorizeTransactionRequest(first.requestId(), card, new BigDecimal("200"));

        var results = concurrently(List.of(first, second), request -> {
            try {
                return authorization.authorize(request).status();
            } catch (ResponseStatusException exception) {
                assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
                return "CONFLICT";
            }
        });

        assertEquals(1, results.stream().filter("APPROVED"::equals).count());
        assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        assertBalance(card, "APPROVED".equals(results.getFirst()) ? "400" : "300");
        assertEquals(1, countTransactions(card));
    }

    @Test
    void failedWriteAfterDebitRollsBackBalanceAndReservation() {
        long card = createCard("ACTIVE", 1, "500");
        var request = request(card, "100");
        var template = new TransactionTemplate(transactionManager);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> template.executeWithoutResult(status -> {
                    assertEquals("APPROVED", authorization.authorize(request).status());
                    jdbc.update("UPDATE transactions SET amount = 0 WHERE request_id = ?", request.requestId());
                }));

        assertBalance(card, "500");
        assertEquals(0, countTransactions(card));
        assertEquals("APPROVED", authorization.authorize(request).status());
        assertBalance(card, "400");
    }

    private List<AuthorizeTransactionResponse> concurrently(List<AuthorizeTransactionRequest> requests) throws Exception {
        return concurrently(requests, authorization::authorize);
    }

    private <T> List<T> concurrently(List<AuthorizeTransactionRequest> requests,
            java.util.function.Function<AuthorizeTransactionRequest, T> authorize) throws Exception {
        var ready = new CountDownLatch(requests.size());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(requests.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (var request : requests) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timed out");
                    }
                    return authorize.apply(request);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private long createCard(String status, int daysToExpiration, String balance) {
        long card = jdbc.queryForObject("""
                INSERT INTO cards (card_number, expiration_date, status, available_balance)
                VALUES (?, CURRENT_DATE + CAST(? AS integer), ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID().toString().substring(0, 20),
                daysToExpiration, status, new BigDecimal(balance));
        cards.add(card);
        return card;
    }

    private AuthorizeTransactionRequest request(long card, String amount) {
        return new AuthorizeTransactionRequest(UUID.randomUUID(), card, new BigDecimal(amount));
    }

    private int countTransactions(long card) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE card_id = ?", Integer.class, card);
    }

    private BigDecimal balance(long card) {
        return jdbc.queryForObject("SELECT available_balance FROM cards WHERE id = ?", BigDecimal.class, card);
    }

    private void assertBalance(long card, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(balance(card)));
    }

    private void assertStatus(HttpStatus expected, AuthorizeTransactionRequest request) {
        var exception = assertThrows(ResponseStatusException.class, () -> authorization.authorize(request));
        assertEquals(expected, exception.getStatusCode());
    }
}
