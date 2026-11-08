-- NUEVA MIGRACIÓN: refuerza en BD el descuento atómico con reglas de tarjeta.
-- PostgreSQL PL/pgSQL es equivalente funcional en este prototipo, no Oracle PL/SQL.
CREATE OR REPLACE FUNCTION debit_card(
    p_card_id BIGINT,
    p_amount NUMERIC
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_amount IS NULL OR p_amount <= 0 THEN
        RETURN FALSE;
    END IF;

    UPDATE cards
       SET available_balance = available_balance - p_amount
     WHERE id = p_card_id
       AND status = 'ACTIVE'
       AND expiration_date >= CURRENT_DATE
       AND available_balance >= p_amount;

    RETURN FOUND;
END;
$$;
