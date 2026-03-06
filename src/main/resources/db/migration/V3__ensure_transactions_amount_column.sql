ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS amount NUMERIC(19,4);

UPDATE transactions
SET amount = 0
WHERE amount IS NULL;

ALTER TABLE transactions
    ALTER COLUMN amount SET NOT NULL;
