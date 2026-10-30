# Explain these decisions from the code

## Why does the atomic update work at READ COMMITTED?

The stock check and decrement are one database operation. Competing updates to the same inventory row wait on its row lock. After the earlier transaction commits, PostgreSQL re-evaluates the `WHERE available >= quantity` condition against the updated row. One affected row means reservation; zero means no reservation. The order and key commit in the same transaction. The nonnegative constraint is additional protection, but cannot alone prevent the naive lost-update bug.

Show `AtomicInventoryStrategy` and [the captured naive race](NAIVE_RACE.md). [PostgreSQL documents the predicate recheck](https://www.postgresql.org/docs/16/transaction-iso.html).

## Why does a concurrent idempotency key wait?

The unique index prevents two committed rows with the same key. An insertion encountering an uncommitted competing key must resolve that transaction before deciding whether to insert or do nothing. If the first transaction commits, the second insertion returns zero; the next statement at READ COMMITTED reads the committed order ID. If the first rolls back, the waiting request can claim the key. A completed request with a different fingerprint gets 409. Failed checkouts do not retain keys.

Show `CheckoutService.checkoutOnce`. Do not generalize this to arbitrary isolation levels or assume every `ON CONFLICT` statement always waits: an already committed key is different from a still-uncommitted competing insert.

## Why mix JPA and SQL?

JPA keeps the simple product catalog concise. Explicit SQL makes affected-row counts, unique-conflict handling, `FOR UPDATE`, and compare-and-set predicates visible in checkout. Spring uses one transaction manager and data source for both. JDBC is intentional here, not a workaround for broken entity mappings.

## What is different about the three strategies?

- Atomic: one conditional `UPDATE` reserves stock; PostgreSQL serializes conflicting row updates.
- Pessimistic: `SELECT ... FOR UPDATE` acquires a row lock before reading stock, then an update reserves it. The surrounding transaction holds the lock until completion.
- Optimistic: read `(available, version)`, then update only if the version still matches. A zero-row update after a sufficient-stock read signals a conflict. All reservation strategies increment version. This project implements explicit SQL optimistic locking, not JPA `@Version`.

On an optimistic conflict, the entire checkout transaction rolls back, including the idempotency claim. The outer method retries in a fresh READ COMMITTED transaction. Jittered backoff runs after rollback, outside the connection/lock lifetime. After 32 attempts the API returns 503, not a false sold-out 409. The client can retry with the same key. The transaction template intentionally owns the endpoint transaction (`REQUIRES_NEW`); it is not an atomic participant in a future caller's larger transaction.

## What can the benchmark establish?

Only the behavior under its recorded host, database, load, warmup and configuration. A 100-stock/10,000-attempt sale is mostly fast rejections; its aggregate p95 says little about purchase latency. Compare successful-checkout p95 separately, and use the all-in-stock workload to compare successful purchase throughput. Report 503 retry exhaustion and conservation alongside latency. The local PostgreSQL binary is x86_64 on an ARM Mac, so these are development-host comparisons, not deployment capacity promises.

## How do the tests detect failures?

Every worker Future is retrieved with a timeout. Tests assert committed orders, committed keys and final stock after workers finish. Idempotency and duplicate-buyer cases run against every strategy. Retry-specific tests force failure after a database write and check complete rollback. The naive demonstration uses a latch to force a known interleaving; it shows possibility, not how often that bug appears in uncontrolled traffic.
