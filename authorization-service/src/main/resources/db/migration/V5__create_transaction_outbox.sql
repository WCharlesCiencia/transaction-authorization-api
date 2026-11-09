CREATE TABLE transaction_outbox (
    event_id UUID PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transaction_outbox_pending
    ON transaction_outbox (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;
