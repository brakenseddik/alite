-- Read-only checks for the seed. Every "must be" line is an acceptance criterion of BAC-11.
-- Run:  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < db/seed/seed_checks.sql

\echo '--- rows (must be: 1000000 products, 156 categories, 120 leaf) ---'
SELECT (SELECT count(*) FROM products)                         AS products,
       (SELECT count(*) FROM categories)                       AS categories,
       (SELECT count(*) FROM categories WHERE level = 3)       AS leaf_categories;

\echo '--- products outside a DET category (must be: 0, the service layer owns this rule so the seed must respect it) ---'
SELECT count(*) AS products_outside_det
FROM products p
         JOIN categories c ON c.id = p.category_id
WHERE c.level <> 3;

\echo '--- SKUs (must be: distinct_skus = 1000000) ---'
SELECT count(DISTINCT sku) AS distinct_skus, count(*) AS products FROM products;

\echo '--- soft deleted (about 5 percent) ---'
SELECT count(*) FILTER (WHERE deleted_at IS NOT NULL)                                     AS soft_deleted,
       round(100.0 * count(*) FILTER (WHERE deleted_at IS NOT NULL) / count(*), 2)        AS percent
FROM products;

\echo '--- value ranges ---'
SELECT min(price) AS min_price, max(price) AS max_price,
       min(created_at)::date AS oldest, max(created_at)::date AS newest
FROM products;

\echo '--- skew: biggest and smallest categories (the planner sees these differences) ---'
(SELECT category_id, count(*) AS products FROM products GROUP BY 1 ORDER BY 2 DESC LIMIT 3)
UNION ALL
(SELECT category_id, count(*) FROM products GROUP BY 1 ORDER BY 2 ASC LIMIT 3);

\echo '--- how many titles contain shirt (for your ILIKE prediction) ---'
SELECT count(*) AS titles_with_shirt FROM products WHERE title ILIKE '%shirt%';

\echo '--- size on disk ---'
SELECT pg_size_pretty(pg_relation_size('products'))       AS table_heap,
       pg_size_pretty(pg_indexes_size('products'))        AS indexes,
       pg_size_pretty(pg_total_relation_size('products')) AS total;
