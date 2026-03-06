ALTER TABLE wallets
    DROP COLUMN IF EXISTS version;

DO $$
DECLARE
    col RECORD;
BEGIN
    FOR col IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND (
              (table_name IN ('members', 'wallets', 'transactions', 'ledger_entries')
               AND column_name IN ('created_at', 'updated_at'))
              OR (table_name = 'transactions' AND column_name IN ('requested_at', 'completed_at'))
          )
          AND data_type = 'timestamp without time zone'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE TIMESTAMPTZ USING %I AT TIME ZONE ''UTC''',
            col.table_name, col.column_name, col.column_name
        );
    END LOOP;
END
$$;
