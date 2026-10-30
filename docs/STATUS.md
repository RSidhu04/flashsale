# Verification and remaining work

Verified on 2026-10-30:

- Java 21.0.13; Spring Boot 4.1.2; Maven wrapper build succeeded.
- `./mvnw -Dflashsale.test.embedded=true verify`: **9 tests, 0 failures, 0 errors, 0 skipped**.
- The local alternative used real PostgreSQL **14.22**, supplied by the test-only embedded-postgres dependency. It did not use the Compose PostgreSQL 16 container.
- 1,000 concurrent-task purchase attempts on 64 worker threads with stock 100: 100 successful orders, 100 committed keys, zero stock remaining.
- 20 concurrent identical retries: one new order, all responses refer to it, stock decremented once.
- Conflicting payload/user for an existing key rejected without changing stock.
- A duplicate buyer using a new key rolls back its stock reservation and key.
- Concurrent different keys for one buyer create one order.
- Missing products and insufficient stock leave no order or key.
- Multi-unit purchases conserve inventory (33 orders of 3 units, 1 remaining from 100).
- HTTP validation and 201 / 200 replay / 400 / 409 semantics exercised.
- Fingerprint binding checked with a unit test.

Not yet verified:

- Default PostgreSQL 16 Testcontainers run: Docker is unavailable in this environment.
- Docker image build and Compose runtime.
- GitHub Actions execution (workflow is included; first run pending).
- k6 workload execution, throughput, p95 or strategy comparison.

Still to implement from the guide:

- Captured naive-race demonstration and pessimistic/optimistic strategy comparison.
- Redis gate, compensation plus crash recovery, and rate limiting.
- Application-specific Micrometer counters and expanded failure injection.
- Authentication/authorization before shared deployment; distinct sale model if products can be sold in multiple sales.
- Optional advanced extensions only after the core experiments.
