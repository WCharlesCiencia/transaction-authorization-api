CREATE TABLE transaction_audit (
    event_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    transaction_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason TEXT,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
