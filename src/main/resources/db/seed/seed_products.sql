-- DEV ONLY. Wipes all catalog data, then rebuilds it. Never run against anything you care about.
--
-- Run (from the project root, compose DB up):
--   docker compose exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < db/seed/seed_products.sql
--
-- This is NOT a Flyway migration: seed data is not schema, and a migration runs everywhere.

\timing on

-- Same "random" choices on every run, so two runs produce the same shape of data.
SELECT setseed(0.42);

BEGIN;

TRUNCATE products, categories RESTART IDENTITY CASCADE;

-- 1) Categories: 6 TOP x 5 SUB x 4 DET = 120 leaf (DET) categories.
INSERT INTO categories (title, level)
SELECT t, 1
FROM unnest(ARRAY ['Electronics', 'Fashion', 'Home & Garden', 'Sports & Outdoors', 'Books', 'Toys & Games']) AS t;

INSERT INTO categories (title, level, parent_id)
SELECT p.title || ' / Group ' || g, 2, p.id
FROM categories p
         CROSS JOIN generate_series(1, 5) AS g
WHERE p.level = 1;

INSERT INTO categories (title, level, parent_id)
SELECT p.title || ' / Type ' || g, 3, p.id
FROM categories p
         CROSS JOIN generate_series(1, 4) AS g
WHERE p.level = 2;

-- 2) Products: 1,000,000 rows in ONE statement (set-based), all in DET categories
--    (the DET-only rule lives in the service layer, so the seed must respect it itself).
WITH leaf AS MATERIALIZED (
    SELECT array_agg(id ORDER BY id) AS ids
    FROM categories
    WHERE level = 3
),
     -- MATERIALIZED: random() must be evaluated once per row, not once per reference.
     raw AS MATERIALIZED (
         SELECT n,
                now() - random() * interval '730 days' AS created_at,
                random()                               AS r_category,
                random()                               AS r_price,
                random()                               AS r_deleted,
                random()                               AS r_deleted_when
         FROM generate_series(1, 1000000) AS n
     ),
     prepared AS (
         SELECT raw.*,
                CASE WHEN r_deleted < 0.05 THEN created_at + (now() - created_at) * r_deleted_when END AS deleted_at
         FROM raw
     )
INSERT
INTO products (title, description, price, sku, category_id, created_at, updated_at, deleted_at)
SELECT (ARRAY ['Classic', 'Modern', 'Compact', 'Premium', 'Eco', 'Smart', 'Vintage', 'Pro'])[1 + (n % 8)]
           || ' '
           || (ARRAY ['Shirt', 'Jacket', 'Lamp', 'Phone', 'Chair', 'Backpack', 'Notebook', 'Headphones', 'Kettle', 'Sneakers'])[1 + (n / 8) % 10]
           || ' ' || n,
       'Generated test product number ' || n,
       -- cheap items are far more common than expensive ones
       round((1 + power(r_price, 3) * 999)::numeric, 2),
       'PRD-' || lpad(n::text, 7, '0'),
       -- skewed: low-index categories are "hot" (about 9% of all rows), the tail is small
       leaf.ids[1 + floor(power(r_category, 2) * cardinality(leaf.ids))::int],
       created_at,
       COALESCE(deleted_at, created_at),
       deleted_at
FROM prepared
         CROSS JOIN leaf;

COMMIT;

-- 3) Refresh planner statistics. Without this the planner guesses from an empty table,
--    and your EXPLAIN plans would be misleading (autovacuum would only fix it later).
ANALYZE categories;
ANALYZE products;
