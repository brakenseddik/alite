# Round 1: baseline, measured WITHOUT predictions

These five measurements were taken by running the queries directly, with no written prediction first. The lesson of predicting first is that the surprise becomes visible, so round 2 does it properly. Environment and caveats: see `README.md`.

Seed: 1,000,000 products. Category ids 37 to 156 are the leaf (DET) categories. Category 37 is the hot one (91,052 products), category 156 is in the tail (4,099).

## 1. Filter by category

```sql
SELECT * FROM products WHERE category_id = 37;    -- hot, 91,052 rows
SELECT * FROM products WHERE category_id = 156;   -- tail, 4,099 rows
```

Author observed: ~110 ms for both.

Hot category, captured (83.19 ms):

```text
Seq Scan on products  (cost=0.00..33301.00 rows=89067 width=135) (actual time=0.622..80.205 rows=91052 loops=1)
  Filter: (category_id = 37)
  Rows Removed by Filter: 908948
  Buffers: shared hit=13426 read=7375
Planning:
  Buffers: shared hit=125
Planning Time: 0.263 ms
Execution Time: 83.190 ms
```

Tail category, captured (81.166 ms):

```text
Gather  (cost=1000.00..27490.43 rows=4811 width=135) (actual time=1.599..80.977 rows=4099 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=13392 read=7409
  ->  Parallel Seq Scan on products  (cost=0.00..26009.33 rows=2005 width=135) (actual time=0.186..73.811 rows=1366 loops=3)
        Filter: (category_id = 156)
        Rows Removed by Filter: 331967
        Buffers: shared hit=13392 read=7409
Planning:
  Buffers: shared hit=125
Planning Time: 0.298 ms
Execution Time: 81.166 ms
```

**What happened:** there is no index on `category_id`, so Postgres reads the whole table and throws away the rows that do not match (see `Rows Removed by Filter`). That costs the same for 91,052 matching rows as for 4,099. The same time for both is the signature of a sequential scan. Times shown by a GUI also include shipping the rows to the screen; `EXPLAIN ANALYZE` shows the server's own time.

**Fix candidate:** index on `products(category_id)`, probably partial on live rows.

## 2. Search by title

```sql
SELECT * FROM products WHERE title ILIKE '%shirt%';   -- 100,000 rows
```

Author observed: ~80 ms, varying between runs. Captured (371.412 ms):

```text
Seq Scan on products  (cost=0.00..33301.00 rows=90909 width=135) (actual time=0.017..368.145 rows=100000 loops=1)
  Filter: ((title)::text ~~* '%shirt%'::text)
  Rows Removed by Filter: 900000
  Buffers: shared hit=13520 read=7281
Planning:
  Buffers: shared hit=116
Planning Time: 0.288 ms
Execution Time: 371.412 ms
```

**What happened:** a sequential scan again. `ILIKE` costs more per row than a plain comparison. A leading `%` means a normal B-tree index cannot help at all. Variation between runs is mostly cache state and how many parallel workers the planner used.

**Fix candidate:** trigram (`pg_trgm`) GIN index, a later topic.

## 3. Newest 20 products

```sql
SELECT * FROM products ORDER BY created_at DESC LIMIT 20;
```

Author observed: 60-120 ms. Captured (155.536 ms):

```text
Limit  (cost=37055.05..37057.38 rows=20 width=135) (actual time=153.830..155.481 rows=20 loops=1)
  Buffers: shared hit=13722 read=7153
  ->  Gather Merge  (cost=37055.05..134284.14 rows=833334 width=135) (actual time=153.828..155.476 rows=20 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=13722 read=7153
        ->  Sort  (cost=36055.03..37096.69 rows=416667 width=135) (actual time=142.199..142.202 rows=15 loops=3)
              Sort Key: created_at DESC
              Sort Method: top-N heapsort  Memory: 33kB
              Buffers: shared hit=13722 read=7153
              Worker 0:  Sort Method: top-N heapsort  Memory: 33kB
              Worker 1:  Sort Method: top-N heapsort  Memory: 34kB
              ->  Parallel Seq Scan on products  (cost=0.00..24967.67 rows=416667 width=135) (actual time=0.013..61.781 rows=333333 loops=3)
                    Buffers: shared hit=13648 read=7153
Planning:
  Buffers: shared hit=145
Planning Time: 0.257 ms
Execution Time: 155.536 ms
```

**What happened:** with no index on `created_at`, each worker reads its share of the whole table and keeps only the best 20 rows (`top-N heapsort`, a few kilobytes of memory). `LIMIT 20` does not shortcut that, because Postgres cannot know which rows are newest until it has seen them all.

**Fix candidate:** index on `created_at DESC`, probably partial on live rows. To be measured in the index ticket.

## 4. Deep pagination

```sql
SELECT * FROM products ORDER BY id OFFSET 900000 LIMIT 20;   -- page 45,001
SELECT * FROM products ORDER BY id OFFSET 0 LIMIT 20;        -- page 1
SELECT * FROM products WHERE id > 900000 ORDER BY id LIMIT 20;   -- keyset
```

Author observed: 432 ms for the 900,000 offset.

Offset 900,000, captured (165.362 ms):

```text
Limit  (cost=42106.03..42106.96 rows=20 width=135) (actual time=165.321..165.325 rows=20 loops=1)
  Buffers: shared hit=14 read=21180
  ->  Index Scan using products_pkey on products  (cost=0.42..46784.43 rows=1000000 width=135) (actual time=0.035..137.413 rows=900020 loops=1)
        Buffers: shared hit=14 read=21180
Planning:
  Buffers: shared hit=136
Planning Time: 0.286 ms
Execution Time: 165.362 ms
```

Offset 0, captured (0.041 ms):

```text
Limit  (cost=0.42..1.36 rows=20 width=135) (actual time=0.011..0.014 rows=20 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using products_pkey on products  (cost=0.42..46784.43 rows=1000000 width=135) (actual time=0.011..0.012 rows=20 loops=1)
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=136
Planning Time: 0.221 ms
Execution Time: 0.041 ms
```

Keyset (`id > 900000`), captured (0.053 ms):

```text
Limit  (cost=0.42..1.41 rows=20 width=135) (actual time=0.017..0.021 rows=20 loops=1)
  Buffers: shared hit=7
  ->  Index Scan using products_pkey on products  (cost=0.42..4938.23 rows=100103 width=135) (actual time=0.016..0.019 rows=20 loops=1)
        Index Cond: (id > 900000)
        Buffers: shared hit=7
Planning:
  Buffers: shared hit=139
Planning Time: 0.255 ms
Execution Time: 0.053 ms
```

**What happened:** this query already uses an index (the primary key) and is still slow. Postgres has to walk 900,020 index entries and fetch each row just to discard 900,000 of them. Page 1 and the keyset version touch about 20 rows.

**Fix candidate:** keyset pagination. It cannot jump to an arbitrary page, and it needs a unique, stable sort key (use `(created_at, id)` when sorting by date).

## 5. Deleting a category

```sql
DELETE FROM categories WHERE id = 156;   -- category that has products
```

Author reported: `4099 rows affected`. **Open question:** 4,099 is exactly the number of products in category 156, so this looks like a delete of the *products*. If so, the seed has to be re-run (`seed_products.sql`) to get back to 1,000,000 products. Deleting the category itself should fail, as below.

**5a. Delete of a category that has products (captured):**

```text
Time: 2.834 ms
ERROR:  update or delete on table "categories" violates foreign key constraint "fk_products_category" on table "products"
DETAIL:  Key (id)=(156) is still referenced from table "products".
```

It is rejected by `fk_products_category` and it is fast, most likely because the check stops at the first product it finds.

**5b. Delete of an empty category: cost of the foreign key check (captured, rolled back):**

```text
Delete on categories  (cost=0.00..4.95 rows=0 width=0) (actual time=0.026..0.027 rows=0 loops=1)
  ->  Seq Scan on categories  (cost=0.00..4.95 rows=1 width=6) (actual time=0.011..0.011 rows=1 loops=1)
        Filter: ((title)::text = 'tmp empty'::text)
        Rows Removed by Filter: 156
Planning Time: 0.027 ms
Trigger for constraint fk_categories_parent: time=0.057 calls=1
Trigger for constraint fk_products_category: time=76.393 calls=1
Execution Time: 76.502 ms
```

To prove a category has no products, Postgres must scan all of `products`. That cost shows up in the `Trigger for constraint fk_products_category` line.

**5c. The same delete after adding an index on `products(category_id)` (captured, rolled back):**

```text
Delete on categories  (cost=0.00..4.95 rows=0 width=0) (actual time=0.022..0.022 rows=0 loops=1)
  ->  Seq Scan on categories  (cost=0.00..4.95 rows=1 width=6) (actual time=0.011..0.011 rows=1 loops=1)
        Filter: ((title)::text = 'tmp empty'::text)
        Rows Removed by Filter: 156
Planning Time: 0.023 ms
Trigger for constraint fk_categories_parent: time=0.047 calls=1
Trigger for constraint fk_products_category: time=0.137 calls=1
Execution Time: 0.228 ms
```

**5d. What `DELETE FROM products WHERE category_id = 156` leaves behind (captured, rolled back):** 995901 products, i.e. 1,000,000 minus 4,099.

**Fix candidate:** index on `products(category_id)`. Postgres does not create indexes on foreign key columns automatically.
