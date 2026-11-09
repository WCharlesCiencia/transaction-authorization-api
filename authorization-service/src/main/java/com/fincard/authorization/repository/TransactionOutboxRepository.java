package com.fincard.authorization.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionOutboxRepository {

    private final JdbcTemplate jdbc;

    public TransactionOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, long transactionId, String payload) {
        jdbc.update("""
                INSERT INTO transaction_outbox (event_id, transaction_id, payload)
                VALUES (?, ?, CAST(? AS jsonb))
                """, eventId, transactionId, payload);
    }

    // The caller keeps the transaction open until the selected rows are updated.
    public List<PendingEvent> lockPendingBatch(int limit) {
        return jdbc.query("""
                SELECT event_id, transaction_id, payload, attempts
                FROM transaction_outbox
                WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, created_at, event_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new PendingEvent(
                rs.getObject("event_id", UUID.class),
                rs.getLong("transaction_id"),
                rs.getString("payload"),
                rs.getInt("attempts")), limit);
    }

    public int markPublished(UUID eventId) {
        return jdbc.update("""
                UPDATE transaction_outbox
                SET published_at = clock_timestamp()
                WHERE event_id = ? AND published_at IS NULL
                """, eventId);
    }

    public int recordFailure(UUID eventId, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE transaction_outbox
                SET attempts = attempts + 1, next_attempt_at = ?
                WHERE event_id = ? AND published_at IS NULL
                """, Timestamp.from(nextAttemptAt), eventId);
    }

	public record PendingEvent(UUID eventId, long transactionId, String payload, int attempts) {
	}
}
