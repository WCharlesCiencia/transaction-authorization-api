-- NUEVA MIGRACIÓN: habilita clave de idempotencia para cada solicitud.
-- Adición nullable para mantener compatibilidad con filas previas.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS ux_transactions_request_id
    ON transactions (request_id)
    WHERE request_id IS NOT NULL;

-- Opcional: restringir estados conocidos, solo si los datos existentes cumplen.
-- No agregar constraint sin revisar estados ya presentes.
