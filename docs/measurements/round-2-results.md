# Round 2: results and verdicts

Predictions are in `predictions-round-2.md`. Plans captured as described in `README.md` (second, warm run). **Score: 4 of 5.**

## 1. `WHERE sku = 'PRD-0500000'`: wrong

Predicted ~1 ms with an index. Captured (69.74 ms):

```text
Gather  (cost=1000.00..27009.43 rows=1 width=135) (actual time=66.464..69.709 rows=1 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=14361 read=6440
  ->  Parallel Seq Scan on products  (cost=0.00..26009.33 rows=1 width=135) (actual time=51.098..61.471 rows=0 loops=3)
        Filter: ((sku)::text = 'PRD-0500000'::text)
        Rows Removed by Filter: 333333
        Buffers: shared hit=14361 read=6440
Planning:
  Buffers: shared hit=125
Planning Time: 0.286 ms
Execution Time: 69.740 ms
```

**What happened:** the SKU index is a *partial* index (`WHERE deleted_at IS NULL`). Postgres can use a partial index only when the query's `WHERE` clause guarantees every matching row is inside it. This query does not mention `deleted_at`, so a soft-deleted product with that SKU could match, and that row is not in the index. The planner cannot use the index and scans the whole table.

**Consequence:** a typical `findBySku(sku)` does not add `deleted_at IS NULL`, so it would silently be a full scan. Either make every query include the live-rows condition (Hibernate: `@SQLRestriction("deleted_at IS NULL")`) or add a normal index on `sku`.

## 2. `WHERE sku = 'PRD-0500000' AND deleted_at IS NULL`: right

Predicted ~1 ms. Captured (0.043 ms):

```text
Index Scan using uq_products_sku_active on products  (cost=0.42..8.44 rows=1 width=135) (actual time=0.020..0.021 rows=1 loops=1)
  Index Cond: ((sku)::text = 'PRD-0500000'::text)
  Buffers: shared hit=4
Planning:
  Buffers: shared hit=129
Planning Time: 0.254 ms
Execution Time: 0.043 ms
```

**What happened:** the query repeats the index's own condition, so the planner may use the partial index. The reasoning in the prediction was slightly off: Postgres does not reach the index and then check `deleted_at`. The index contains only live rows, and the repeated condition is what allows using it.

## 3. `WHERE price > 990`: right

Predicted ~100 ms, full scan. Captured (97.509 ms):

```text
Gather  (cost=1000.00..27319.63 rows=3103 width=135) (actual time=0.421..97.363 rows=3315 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=14553 read=6248
  ->  Parallel Seq Scan on products  (cost=0.00..26009.33 rows=1293 width=135) (actual time=2.483..89.654 rows=1105 loops=3)
        Filter: (price > '990'::numeric)
        Rows Removed by Filter: 332228
        Buffers: shared hit=14553 read=6248
Planning:
  Buffers: shared hit=124
Planning Time: 0.302 ms
Execution Time: 97.509 ms
```

**What happened:** nothing indexes `price`, so every row is read and about 3,300 are kept.

## 4. `WHERE id = 500000`: right

Predicted ~1 ms via the primary key. Captured (0.053 ms):

```text
Index Scan using products_pkey on products  (cost=0.42..8.44 rows=1 width=135) (actual time=0.019..0.019 rows=1 loops=1)
  Index Cond: (id = 500000)
  Buffers: shared hit=7
Planning:
  Buffers: shared hit=126
Planning Time: 0.246 ms
Execution Time: 0.053 ms
```

**What happened:** direct primary key lookup. Run each query twice: the first run is slower because of a cold cache.

## 5. `count(*) WHERE deleted_at IS NULL`: right

Predicted more than 100 ms, full scan. Captured (112.031 ms):

```text
Finalize Aggregate  (cost=25327.93..25327.94 rows=1 width=8) (actual time=111.482..111.980 rows=1 loops=1)
  Buffers: shared hit=3646
  ->  Gather  (cost=25327.71..25327.92 rows=2 width=8) (actual time=108.974..111.966 rows=3 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=3646
        ->  Partial Aggregate  (cost=24327.71..24327.72 rows=1 width=8) (actual time=105.468..105.470 rows=1 loops=3)
              Buffers: shared hit=3646
              ->  Parallel Index Only Scan using uq_products_sku_active on products  (cost=0.42..23339.62 rows=395236 width=0) (actual time=0.021..55.493 rows=316736 loops=3)
                    Heap Fetches: 0
                    Buffers: shared hit=3646
Planning:
  Buffers: shared hit=99
Planning Time: 0.265 ms
Execution Time: 112.031 ms
```

**What happened:** Postgres has to visit every live row to count it (it cannot keep an exact total, because each transaction sees a different set of rows). The usual ways out, if a number like this is shown often, are an estimate or a counter maintained on write.
