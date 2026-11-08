CREATE OR REPLACE FUNCTION debit_card(
    p_card_id BIGINT,
    p_amount NUMERIC
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE cards
    SET available_balance = available_balance - p_amount
    WHERE id = p_card_id
      AND available_balance >= p_amount
      AND p_amount > 0;

    RETURN FOUND;
END;
$$;