-- DEV ONLY experiment: why set-based inserts win. Run it BEFORE the full seed; it cleans up after itself.
-- Run:  docker compose exec -T db sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < db/seed/compare_insert_methods.sql

TRUNCATE products, categories RESTART IDENTITY CASCADE;
INSERT INTO categories (title, level) VALUES ('TOP', 1);
INSERT INTO categories (title, level, parent_id) VALUES ('SUB', 2, 1);
INSERT INTO categories (title, level, parent_id) VALUES ('DET', 3, 2);   -- id 3

\echo ''
\echo 'A) 2000 separate statements, each its own transaction (autocommit): one commit per row'
SELECT clock_timestamp() AS t0 \gset
SELECT format('INSERT INTO products (title, price, sku, category_id) VALUES (''A %s'', 9.99, ''CMP-A-%s'', 3)', i, i)
FROM generate_series(1, 2000) AS i \gexec
SELECT round(extract(epoch FROM clock_timestamp() - :'t0'::timestamptz) * 1000) AS ms_for_2000_rows_autocommit;

\echo ''
\echo 'B) 10000 single-row INSERTs in a loop inside ONE transaction'
BEGIN;
DO
$$
    DECLARE
        t0 timestamptz := clock_timestamp();
    BEGIN
        FOR i IN 1..10000
            LOOP
                INSERT INTO products (title, price, sku, category_id)
                VALUES ('B ' || i, 9.99, 'CMP-B-' || i, 3);
            END LOOP;
        RAISE NOTICE 'ms for 10000 rows, loop in one transaction: %',
            round(extract(epoch FROM clock_timestamp() - t0) * 1000);
    END
$$;
ROLLBACK;

\echo ''
\echo 'C) 10000 rows with ONE set-based INSERT ... SELECT generate_series'
BEGIN;
DO
$$
    DECLARE
        t0 timestamptz := clock_timestamp();
    BEGIN
        INSERT INTO products (title, price, sku, category_id)
        SELECT 'C ' || i, 9.99, 'CMP-C-' || i, 3
        FROM generate_series(1, 10000) AS i;
        RAISE NOTICE 'ms for 10000 rows, one set-based statement: %',
            round(extract(epoch FROM clock_timestamp() - t0) * 1000);
    END
$$;
ROLLBACK;

-- Clean up (A cannot be rolled back; it committed row by row).
TRUNCATE products, categories RESTART IDENTITY CASCADE;

\echo ''
\echo 'Note: B and C run inside the server, so they hide the network round trip an application'
\echo 'pays per row (JDBC/Hibernate without batching). The real-world gap is larger than shown.'
