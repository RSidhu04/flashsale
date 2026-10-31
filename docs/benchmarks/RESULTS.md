# Local k6 comparison — 2026-10-30

A [repeat run on 2026-10-31](REPEAT-2026-10-31.md) preserves a second full set of measurements without changing code or settings.

All 18 measured workloads completed and conserved stock. Atomic and pessimistic locking had no failed purchases. Optimistic locking returned retry-exhaustion 503s in every all-in-stock run, so its zero-contention availability threshold failed. The harness exited **2** to preserve that failure; the correctness suite remains green.

## Environment and method

Source: [`ec9867f`](https://github.com/RSidhu04/flashsale/commit/ec9867f301fedbf106d36d71b6422499351d09f2). Raw metadata retains the equivalent local commit identity, plus this published commit. Working tree was clean when the measurement started.

- Apple M5, 10 logical CPUs, 16 GiB RAM; macOS 26.6.2. Java 21.0.13 ARM64; Spring Boot 4.1.2; k6 2.3.0 ARM64.
- PostgreSQL 14.22 **x86_64 binary on an ARM host**, bundled by embedded-postgres. This is not the PostgreSQL 16 Linux container tested in CI.
- PostgreSQL fsync, synchronous_commit and full_page_writes on; shared_buffers 128 MiB; max_connections 300. Hikari pool 16. JVM initial/max heap 512 MiB.
- k6, API and database share one development host over localhost. No network latency simulation, client retry, Redis gate or authentication.
- 100 virtual users; 10,000 checkout attempts per workload; fresh product and unique buyer/key per attempt.
- Each strategy/round starts a new app/database and warms with 2,000 in-stock attempts. Warmup results, including contention failures, are retained separately.
- Three rounds rotate order: atomic/pessimistic/optimistic, pessimistic/optimistic/atomic, optimistic/atomic/pessimistic. Each process then runs scarce stock before in-stock traffic.
- Scarce: 100 items for 10,000 attempts. In-stock: 10,000 items for 10,000 attempts. All quantities are one.
- Rates use k6 checkout_attempts / testRunDurationMs, excluding product setup/SQL verification calls from the attempt count. Latency is k6 response duration, in milliseconds.
- These are **short burst comparisons**, not one-hour soak tests or sustained production capacity measurements. Three samples are insufficient for tight confidence intervals.

## Summary

Values are the **median of three per-run measurements**, not percentiles recomputed over pooled request samples. Ranges show min–max across runs. Rate means attempts/sec; in-stock atomic/pessimistic rates also equal successful orders/sec.

| Workload | Strategy | Median req/s (range) | Median all-checkout p95 ms | Median successful-checkout p95 ms | Orders/run | 503s across 3 runs |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| scarce | atomic | 8,799 (6,217–9,205) | 28.4 | 35.7 | 100/100/100 | 0 |
| scarce | pessimistic | 8,475 (7,482–9,227) | 21.2 | 40.1 | 100/100/100 | 0 |
| scarce | optimistic | 9,602 (9,489–10,008) | 27.2 | 81.4 | 100/100/100 | 0 |
| in-stock | atomic | 3,392 (3,379–3,693) | 45.2 | 45.2 | 10000/10000/10000 | 0 |
| in-stock | pessimistic | 3,110 (2,714–3,506) | 49.6 | 49.6 | 10000/10000/10000 | 0 |
| in-stock | optimistic | 1,703 (1,615–1,707) | 253.3 | 241.4 | 9940/9941/9923 | 196 |

Atomic had the highest median successful purchase rate in this sample, but its rate range overlaps pessimistic locking. This is not evidence of a universal winner. Optimistic retries added substantial work and tail latency: 59–77 in-stock requests per run exhausted 32 attempts. Those buyers received 503 and their stock/key writes rolled back. No client retries were used to hide these failures.

The scarce-stock aggregate is mostly 409 responses. For example, optimistic has a high attempt rate there while successful purchases have materially worse p95; do not call that faster checkout based on aggregate rejection traffic.

## Every measured run

| Run | Req/s | All p95 ms | Success p95 ms | Duration s | Orders | Remaining | Retry conflicts | 503 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [r1-atomic-scarce](2026-10-30/r1-atomic-scarce.json) | 8798.7 | 28.44 | 39.19 | 1.137 | 100 | 0 | 0 | 0 |
| [r1-atomic-in-stock](2026-10-30/r1-atomic-in-stock.json) | 3378.9 | 47.32 | 47.32 | 2.960 | 10000 | 0 | 0 | 0 |
| [r1-pessimistic-scarce](2026-10-30/r1-pessimistic-scarce.json) | 9227.4 | 19.68 | 35.01 | 1.084 | 100 | 0 | 0 | 0 |
| [r1-pessimistic-in-stock](2026-10-30/r1-pessimistic-in-stock.json) | 3110.4 | 49.64 | 49.64 | 3.215 | 10000 | 0 | 0 | 0 |
| [r1-optimistic-scarce](2026-10-30/r1-optimistic-scarce.json) | 9601.5 | 28.92 | 92.23 | 1.042 | 100 | 0 | 675 | 0 |
| [r1-optimistic-in-stock](2026-10-30/r1-optimistic-in-stock.json) | 1702.7 | 253.26 | 239.17 | 5.873 | 9940 | 60 | 50669 | 60 |
| [r2-pessimistic-scarce](2026-10-30/r2-pessimistic-scarce.json) | 8475.0 | 21.19 | 40.07 | 1.180 | 100 | 0 | 0 | 0 |
| [r2-pessimistic-in-stock](2026-10-30/r2-pessimistic-in-stock.json) | 3506.4 | 39.82 | 39.82 | 2.852 | 10000 | 0 | 0 | 0 |
| [r2-optimistic-scarce](2026-10-30/r2-optimistic-scarce.json) | 9488.9 | 27.17 | 74.79 | 1.054 | 100 | 0 | 706 | 0 |
| [r2-optimistic-in-stock](2026-10-30/r2-optimistic-in-stock.json) | 1707.5 | 252.23 | 241.63 | 5.857 | 9941 | 59 | 51855 | 59 |
| [r2-atomic-scarce](2026-10-30/r2-atomic-scarce.json) | 9204.6 | 27.31 | 33.13 | 1.086 | 100 | 0 | 0 | 0 |
| [r2-atomic-in-stock](2026-10-30/r2-atomic-in-stock.json) | 3693.0 | 41.14 | 41.14 | 2.708 | 10000 | 0 | 0 | 0 |
| [r3-optimistic-scarce](2026-10-30/r3-optimistic-scarce.json) | 10008.4 | 27.21 | 81.38 | 0.999 | 100 | 0 | 666 | 0 |
| [r3-optimistic-in-stock](2026-10-30/r3-optimistic-in-stock.json) | 1615.4 | 261.45 | 241.44 | 6.190 | 9923 | 77 | 53462 | 77 |
| [r3-atomic-scarce](2026-10-30/r3-atomic-scarce.json) | 6216.9 | 42.62 | 35.68 | 1.609 | 100 | 0 | 0 | 0 |
| [r3-atomic-in-stock](2026-10-30/r3-atomic-in-stock.json) | 3391.9 | 45.23 | 45.23 | 2.948 | 10000 | 0 | 0 | 0 |
| [r3-pessimistic-scarce](2026-10-30/r3-pessimistic-scarce.json) | 7482.1 | 23.98 | 45.31 | 1.337 | 100 | 0 | 0 | 0 |
| [r3-pessimistic-in-stock](2026-10-30/r3-pessimistic-in-stock.json) | 2714.4 | 62.60 | 62.60 | 3.684 | 10000 | 0 | 0 | 0 |

## Correctness and failed availability evidence

Every measured run checked `available + units_sold = initial_stock`, `orders = units_sold = committed keys = HTTP 201 count`, all 10,000 attempts completing, and HTTP 503 count matching the server exhaustion counter delta. There were no unexpected responses or oversold units. For optimistic in-stock runs, leftover stock (60, 59, 77) equals the number of rejected buyers. Correctness does not imply availability.

The original full comparison stopped during optimistic warmup: 1,982 orders and 18 retry-exhaustion responses from 2,000 attempts, with 18 items remaining. That evidence is retained as `initial-aborted-*`. We then changed only the recorder to retain known 503 failures and continue all comparisons, without tuning the 32-attempt retry budget or discarding failed results.

Raw evidence: [environment and SQL summaries](2026-10-30/environment.json). Each run has its unmodified k6 JSON and a matching `-sql.json` snapshot. Warmups are retained but excluded from the summary table. The environment file adds hardware and published-commit provenance to the original runner metadata.

## Reproduce and use responsibly

See [runner instructions](../../load/README.md). Longer runs on a deployed Linux/PostgreSQL 16 setup, isolated load generation, and repeated trials would be needed before a capacity or reliability claim. A defensible résumé statement is: “Compared three inventory strategies in local k6 tests; atomic checkout achieved a median 3,392 successful orders/sec and 45.2 ms p95 across three 10,000-purchase runs at 100 virtual users, with SQL-verified stock conservation.” Keep the word **local** and these workload qualifications.
