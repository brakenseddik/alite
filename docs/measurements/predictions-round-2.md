# Round 2: predictions (written BEFORE measuring)

Five queries on the 1,000,000-row seed, with the indexes that exist at this point: primary key on `id`, unique index on `public_id`, and the partial unique index `uq_products_sku_active` on `sku` (only rows where `deleted_at IS NULL`).

| # | Query | Prediction |
|---|---|---|
| 1 | `SELECT * FROM products WHERE sku = 'PRD-0500000'` | ~1 ms, because sku has a unique index |
| 2 | `SELECT * FROM products WHERE sku = 'PRD-0500000' AND deleted_at IS NULL` | ~1 ms, because it reaches the index first and checks `deleted_at` after |
| 3 | `SELECT * FROM products WHERE price > 990` | ~100 ms, full scan |
| 4 | `SELECT * FROM products WHERE id = 500000` | ~1 ms, the primary key index is created automatically |
| 5 | `SELECT count(*) FROM products WHERE deleted_at IS NULL` | more than 100 ms, full scan and a running counter |
