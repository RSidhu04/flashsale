# FlashSale

[![CI](https://github.com/RSidhu04/flashsale/actions/workflows/ci.yml/badge.svg)](https://github.com/RSidhu04/flashsale/actions/workflows/ci.yml)

A Java 21 / Spring Boot 4.1.2 portfolio project exploring correct checkout when many buyers compete for limited stock. PostgreSQL is the source of truth.

## Current scope

- Maven wrapper; Spring Web MVC, Data JPA, Validation, Flyway, PostgreSQL, Actuator.
- Product creation/read, checkout, order read.
- Atomic conditional stock update inside the order transaction.
- Idempotency keys, request fingerprint validation, one order per user per product.
- PostgreSQL concurrency tests; GitHub Actions runs the full suite with Testcontainers.
- Docker Compose for PostgreSQL 16 and Redis 7, optional app image.
- k6 workload and SQL conservation check. **No throughput or latency results have been measured.**

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

The service explicitly uses READ COMMITTED. PostgreSQL rechecks the update predicate after waiting on another updater. A nonnegative stock constraint provides another defense against invalid stock, but **that constraint alone does not prevent lost updates in a naive read-modify-write implementation**.

Only successful orders retain idempotency keys. Failed requests may be retried and reevaluated. Keys are global and retained indefinitely in this version. Authenticated ownership, expiry and privacy controls remain future work.

The `product` package uses JPA for catalog persistence. `inventory` and `order` use explicit SQL for the concurrency-sensitive operations. Spring's transaction manager shares the same database transaction with the JDBC operations. `common` holds API errors. See [the walkthrough](docs/WALKTHROUGH.md).

## Tests

The original implementation had exactly **9 tests** (8 integration tests and 1 unit test), verified locally and in CI. The race demonstration adds one regression test; current counts are tracked in [verification status](docs/STATUS.md).

```sh
./mvnw verify
```

Docker must be running. The default suite uses a fresh PostgreSQL 16 Testcontainer and deliberately fails rather than silently skipping tests when Docker is absent. Tests consume every worker Future, assert completion, and check database state after all workers finish.

On a development machine without Docker, this explicit alternative runs the same suite using a real PostgreSQL binary from the test-only embedded-postgres library:

```sh
./mvnw -Dflashsale.test.embedded=true verify
```

The alternative does not verify Docker Compose or the PostgreSQL 16 container image. See [verification status](docs/STATUS.md) for what has actually run.

## Benchmark scaffolding

With k6 installed and the API running:

```sh
mkdir -p load/results
k6 run --summary-export=load/results/atomic.json load/flashsale.js
# Larger experiment:
VUS=500 ATTEMPTS=10000 STOCK=100 k6 run load/flashsale.js
```

Each run creates a fresh product and uses unique buyers/keys. Record hardware, Java/PostgreSQL versions, connection pool, warmup, duration, commit SHA and k6 settings. Inspect the checkout-tagged latency, successful orders and expected conflicts separately. Sold-out 409s are expected business outcomes, although k6's generic HTTP error metric counts them as failures. The checks threshold fails on unexpected responses.

Run `load/verify.sql` with the printed product ID using psql. Confirm `available + units_sold = starting_stock`; when attempts exceed stock with quantity one, expect 100 orders and zero remaining stock. HTTP status counts alone are not a correctness proof.

| Strategy | Correctness suite | Throughput | p95 |
| --- | --- | --- | --- |
| Atomic update | See verification status | Not measured | Not measured |
| Pessimistic / optimistic / Redis gate | Not implemented | Not measured | Not measured |

## Next phases from the build guide

- Controlled naive-race experiment with captured failing evidence (test/demo only).
- Pessimistic and optimistic strategies behind an interface; compare under identical workloads.
- Redis Lua gate, failure recovery/reconciliation and rate limiting.
- Measured benchmarks, custom Micrometer counters, deployment hardening.
- Only then consider Kafka/outbox, reservation expiry or a storefront.

No resume claim about throughput, latency or Redis offload is supported yet. See [NOTES.md](NOTES.md) for the learning checklist.
