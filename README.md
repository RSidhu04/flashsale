# FlashSale

[![CI](https://github.com/RSidhu04/flashsale/actions/workflows/ci.yml/badge.svg)](https://github.com/RSidhu04/flashsale/actions/workflows/ci.yml)

A Java 21 / Spring Boot 4.1.2 portfolio project exploring correct checkout when many buyers compete for limited stock. PostgreSQL is the source of truth.

## Current scope

- Maven wrapper; Spring Web MVC, Data JPA, Validation, Flyway, PostgreSQL, Actuator.
- Product creation/read, checkout, order read.
- Selectable atomic, pessimistic and optimistic inventory reservation inside the order transaction.
- Idempotency keys, request fingerprint validation, one order per user per product.
- PostgreSQL concurrency tests; GitHub Actions runs the full suite with Testcontainers.
- Docker Compose for PostgreSQL 16 and Redis 7, optional app image.
- k6 strategy comparison with SQL conservation checks; see [measured results](docs/benchmarks/RESULTS.md).

Redis is provisioned for a later phase and is not used by checkout yet. This is a local educational API: user IDs are caller-supplied, and authentication/authorization are not implemented. Keep it on localhost until those controls exist. Product identity currently also represents sale identity; repeated sales need a separate sale model.

## Run

Install Java 21 and Docker with Compose. Maven is supplied by the wrapper.

```sh
cp .env.example .env
# .env has an explicitly local-only demo password; never commit real credentials.
docker compose up -d --wait
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Alternatively run the entire stack in containers:

```sh
cp .env.example .env
docker compose --profile app up --build -d
```

Compose binds exposed services to localhost. The app's container listens internally on all interfaces. Health: `GET /actuator/health`. Only health and info are exposed by default. No seed data is added by migrations.

```sh
curl -i localhost:8080/products -H 'Content-Type: application/json'   -d '{"name":"Keyboard","priceCents":4999,"stock":100}'
# Substitute the returned product ID below.
curl -i localhost:8080/checkout -H 'Content-Type: application/json'   -H 'Idempotency-Key: demo-purchase-1'   -d '{"productId":1,"userId":"buyer-1","quantity":1}'
curl localhost:8080/products/1
curl localhost:8080/orders/1
```

| Endpoint | Result |
| --- | --- |
| `POST /products` | 201, product and initial stock |
| `GET /products/{id}` | 200 or 404 |
| `POST /checkout` | 201 new order; 200 matching retry; 409 insufficient stock, reused key with changed request, or duplicate buyer |
| `GET /orders/{id}` | 200 or 404 |

Malformed requests, missing keys and invalid fields return 400 using Spring Problem Details. Money is stored as integer cents. Quantity may exceed one, but a buyer may place only one order per product.

## How checkout stays consistent

1. Insert the idempotency key with `ON CONFLICT DO NOTHING`. A competing transaction using the same key waits for the first one to finish.
2. If the key exists, compare the SHA-256 fingerprint and user ID. Return the stored order on an exact match; otherwise return 409.
3. Check product existence and execute `UPDATE inventory SET available = available - ? ... WHERE available >= ?`. One changed row means the reservation succeeded.
4. Insert the order, enforce the unique `(product_id, user_id)` constraint, and attach the order to the key.
5. Commit all changes together. Any failure rolls back the stock, order and new key.

The service explicitly uses READ COMMITTED. Set `INVENTORY_STRATEGY=atomic` (default), `pessimistic`, or `optimistic` before startup. Optimistic compare-and-set conflicts roll back and retry the entire transaction up to 32 times with jitter; exhaustion returns 503 and can be retried with the same key. PostgreSQL rechecks the update predicate after waiting on another updater. A nonnegative stock constraint provides another defense against invalid stock, but **that constraint alone does not prevent lost updates in a naive read-modify-write implementation**.

Only successful orders retain idempotency keys. Failed requests may be retried and reevaluated. Keys are global and retained indefinitely in this version. Authenticated ownership, expiry and privacy controls remain future work.

The `product` package uses JPA for catalog persistence. `inventory` and `order` use explicit SQL for the concurrency-sensitive operations. Spring's transaction manager shares the same database transaction with the JDBC operations. `common` holds API errors. See [the walkthrough](docs/WALKTHROUGH.md).

## Verified results

- **1,000 purchase attempts across 64 worker threads → exactly 100 orders for 100 items**, with zero stock remaining. See [`thousandBuyersCompeteForOneHundredItems`](src/test/java/com/ravan/flashsale/CheckoutIntegrationTest.java#L118).
- **20 simultaneous retries with one idempotency key → exactly one order**, with stock decremented only once. See [`twentyConcurrentRetriesReturnOneOrder`](src/test/java/com/ravan/flashsale/CheckoutIntegrationTest.java#L139).

Both checks run against the atomic, pessimistic and optimistic strategies. The full suite has 30 passing test executions; see [verification details](docs/STATUS.md).

## Tests

The original implementation had exactly **9 tests** (8 integration tests and 1 unit test). The expanded suite has **30 passing test executions**: the 9-test contract runs for all three strategies, plus 2 optimistic-retry tests and 1 fingerprint unit test. See [verification status](docs/STATUS.md).

```sh
./mvnw verify
```

Docker must be running. The default suite uses a fresh PostgreSQL 16 Testcontainer and deliberately fails rather than silently skipping tests when Docker is absent. Tests consume every worker Future, assert completion, and check database state after all workers finish.

On a development machine without Docker, this explicit alternative runs the same suite using a real PostgreSQL binary from the test-only embedded-postgres library:

```sh
./mvnw -Dflashsale.test.embedded=true verify
```

The alternative does not verify Docker Compose or the PostgreSQL 16 container image. See [verification status](docs/STATUS.md) for what has actually run.

## Measured strategy comparison

See [results and raw evidence](docs/benchmarks/RESULTS.md), [benchmark instructions](load/README.md), and [the captured naive-race failure](docs/NAIVE_RACE.md). The benchmark records successful-checkout latency separately from sold-out responses and explicitly reports optimistic retry-exhaustion failures. It verifies inventory conservation in SQL after each run.

```sh
python3 load/run-local.py --rounds 3 --vus 100 --attempts 10000 --warmup 2000
```

This local harness needs Java 21, Python 3, k6 and a free port 8080. It uses a test-only PostgreSQL process with durability enabled. Results describe this development host, not production capacity.

## Roadmap

- Redis Lua gate, failure recovery/reconciliation and rate limiting.
- Longer duration/deployed-environment benchmarks, expanded observability and deployment hardening.
- Only then consider Kafka/outbox, reservation expiry or a storefront.

Use measured numbers only with their workload and local-environment qualifications. Redis offload is not implemented. See [interview preparation](docs/INTERVIEW_PREP.md) and [NOTES.md](NOTES.md).
