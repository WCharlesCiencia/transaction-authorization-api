CREATE TABLE cards (
    id BIGSERIAL PRIMARY KEY,

    card_number VARCHAR(20) NOT NULL UNIQUE,

    expiration_date DATE NOT NULL,

    status VARCHAR(20) NOT NULL,

    available_balance NUMERIC(19,2) NOT NULL
        CHECK (available_balance >= 0) ,
        
    card_type VARCHAR(30) NOT NULL
       CONSTRAINT chk_card_type
       CHECK (card_type IN ('CREDIT', 'DEBIT'))
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    card_id BIGINT NOT NULL REFERENCES cards(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);