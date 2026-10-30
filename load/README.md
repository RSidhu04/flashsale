# Run the strategy comparison

Prerequisites: Java 21, Python 3, k6. Maven comes from the wrapper. Port 8080 must be free.

```sh
python3 load/run-local.py --k6 k6 --rounds 3 --vus 100 --attempts 10000 --warmup 2000
```

The harness starts a fresh, test-only PostgreSQL process and Spring Boot API for each strategy/round, with a fixed 512 MiB JVM heap and connection pool of 16. PostgreSQL durability settings `fsync`, `synchronous_commit` and `full_page_writes` are explicitly on. The actual server version and settings are captured. The test library's bundled binary can differ from PostgreSQL 16 in Compose/CI.

Each process warms up with 2,000 in-stock purchases, then runs 10,000 attempts against 100 items and 10,000 attempts against 10,000 items. Three rounds rotate strategy order to reduce ordering bias. This is a local closed-loop workload, not a production capacity estimate. k6, Java and PostgreSQL compete for the same host resources. No client retries hide server errors.

The script verifies order count, units sold, remaining stock and committed idempotency keys directly in SQL after each workload, and fails if conservation, expected order count or k6 checks fail. It records optimistic conflict and exhaustion counter deltas. The read-only verification endpoints exist only on the test classpath with the `local-benchmark` profile; they are excluded from the production JAR.

Raw k6 summaries and SQL results go under ignored `load/results/`. Preserve selected measured artifacts in `docs/benchmarks/` with an environment description and implementation commit. All-checkout p95 and successful-checkout p95 are separate. The `checkout_attempts` rate uses k6's summary duration; it is attempts/sec, not successful orders/sec. Do not compare a mostly-sold-out run with an all-success run as if they were the same workload.

For an existing app (Docker or otherwise), select `INVENTORY_STRATEGY=atomic|pessimistic|optimistic` before startup, then run `load/flashsale.js` directly. The runner is an optional local convenience, not a replacement for deployed-environment benchmarks.
