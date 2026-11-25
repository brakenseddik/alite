# Measurements: catalog at 1M rows (BAC-11)

How the unindexed V1 schema behaves with 1,000,000 seeded products, and what to fix.

## Where these numbers come from

- Seed: `db/seed/seed_products.sql` (1,000,000 products, 156 categories, `setseed(0.42)`), followed by `ANALYZE`.
- Plans in this folder were captured with `EXPLAIN (ANALYZE, BUFFERS)` on **PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)**, `shared_buffers = 128MB`, `jit = on`, `max_parallel_workers_per_gather = 2`, products table + indexes 250 MB. Each query was run twice and the second (warm cache) run is kept.
- **Absolute times will differ on another machine.** Compare the *plan shapes* (Seq Scan vs Index Scan, rows removed, sort method) and the *ratios*. Round 1 also lists the timings the author observed on their own machine.
- To refresh on your machine: run the same `EXPLAIN (ANALYZE, BUFFERS)` in psql and paste the plan over the block.

## Files

| File | What it is |
|---|---|
| `round-1-baseline.md` | First measurements, taken **without written predictions**, plus the category-delete experiment |
| `predictions-round-2.md` | Predictions for five more queries, written **before** measuring (commit this one first) |
| `round-2-results.md` | The five queries measured, with a verdict on each prediction (4 of 5 correct) |

## Summary

| Query | Plan | Time (captured) | Author observed (round 1) |
|---|---|---|---|
| `category_id = 37` (91,052 rows) | Seq Scan | 83.19 ms | ~110 ms |
| `category_id = 156` (4,099 rows) | Seq Scan | 81.166 ms | ~110 ms |
| `title ILIKE '%shirt%'` (100,000 rows) | Seq Scan | 371.412 ms | ~80 ms, varies |
| `ORDER BY created_at DESC LIMIT 20` | Parallel scan, top-N heapsort | 155.536 ms | 60-120 ms |
| `ORDER BY id OFFSET 900000 LIMIT 20` | Index scan, walks 900,020 rows | 165.362 ms | 432 ms |
| `ORDER BY id OFFSET 0 LIMIT 20` | Index scan | 0.041 ms | not measured |
| `WHERE id > 900000 ORDER BY id LIMIT 20` (keyset) | Index scan | 0.053 ms | not measured |
| `sku = 'PRD-0500000'` | Seq Scan | 69.74 ms | not measured |
| `sku = ... AND deleted_at IS NULL` | Index scan (partial index) | 0.043 ms | not measured |
| `price > 990` | Seq Scan | 97.509 ms | not measured |
| `id = 500000` | Index scan (primary key) | 0.053 ms | not measured |
| `count(*) WHERE deleted_at IS NULL` | Parallel Seq Scan | 112.031 ms | not measured |

## What this tells us (inputs for the index ticket)

1. **A sequential scan costs the same whatever it returns.** Hot and tail categories took about the same time. An index on `products(category_id)` is the fix; decide whether it should be partial (live rows only).
2. **`ORDER BY created_at DESC LIMIT 20` reads every row**, because nothing is sorted. An index on `created_at` (probably partial, live rows) lets Postgres read just the first 20 entries. To be measured in the index ticket.
3. **`OFFSET` costs grow with the page number even with an index.** Page 1 is 0.041 ms, page 45,000 is 165.362 ms. The keyset form is 0.053 ms. Use keyset pagination; it needs a unique, stable sort key (`(created_at, id)` for date ordering).
4. **The partial SKU index is only used when the query repeats `deleted_at IS NULL`.** Without it the same lookup is a full scan (69.74 ms vs 0.043 ms).
5. **Foreign keys need an index on the referencing column.** Deleting a category with no products scans the whole products table for the check (see round 1, item 5).
6. **`ILIKE '%text%'` cannot use a normal index.** A trigram (`pg_trgm`) GIN index is a later topic.

## Decision-log candidates

- Every query on `products` must include `deleted_at IS NULL` (or an index without the predicate must exist), otherwise the partial SKU index is silently skipped.
- Pagination is keyset-based, not `OFFSET`.
- Every foreign key column gets an index, built with `CREATE INDEX CONCURRENTLY`.
