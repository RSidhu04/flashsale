# Read the code in this order

1. `pom.xml`: dependencies and Java version. The Maven wrapper downloads Maven; Java still must be installed.
2. `V1__init.sql` and `V2__idempotency.sql`: database constraints are part of the design, not just application validation. Flyway changes the schema; Hibernate validates the mapped catalog entity.
3. `ProductController` → `ProductService`: HTTP validation, transaction boundary, product persistence and starting stock.
4. `InventoryRepository.tryReserve`: affected-row count answers whether this request won stock. There is no application-side read/check/write gap.
5. `CheckoutService`: the transaction wraps the idempotency claim, reservation, order and key completion. Throwing on duplicate purchase undoes the earlier stock update.
6. `CheckoutIntegrationTest`: synchronized start, bounded executor, every Future awaited, and final database invariants. A passing HTTP response alone is insufficient.

## Explain the naive race

With one item available, A and B both read 1. Each computes 0, writes 0 and inserts an order. Final stock is zero but two orders exist. `CHECK (available >= 0)` cannot detect this lost update. This is an explanation, not a captured experiment; the deliberately unsafe implementation has not yet been built or measured.

## Explain concurrent retries

A inserts key K. B attempts the same key and waits on the unique index. If A commits, B's insert does nothing, and B's next statement at READ COMMITTED sees the committed key and its order. If A rolls back, B can insert K and attempt checkout itself. No half-finished key is committed by this service.

## Current tradeoffs

The hottest inventory row serializes competing updates. That gives a clear correctness model, not unlimited throughput. Pool size limits active database connections. Atomic updates avoid optimistic retry storms, but strategy comparisons require measurement.

A product is a sale for now. The unique buyer constraint means the same buyer cannot order that product again, even with a different key. Request fingerprints bind product, quantity and user; whitespace in a user ID is significant. There is no user authentication yet.

Redis compensation alone would not cover a process crash between two systems. The Redis phase needs a recovery/reconciliation design as well as a Lua decrement. PostgreSQL must continue making the final stock decision.

## Official references

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [PostgreSQL READ COMMITTED behavior](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL INSERT / ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)
