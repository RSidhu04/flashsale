# Verification and remaining work

## Current verification — 2026-10-30

- The original baseline was exactly **9 tests**, confirmed again before these changes.
- The expanded suite has **30 test executions, 0 failures, 0 errors, 0 skipped** locally and in [PostgreSQL 16 Testcontainers CI](https://github.com/RSidhu04/flashsale/actions/runs/41093602457).
- Breakdown: 9 inherited integration tests for each of atomic/pessimistic/optimistic (27), two optimistic-retry tests (2), and fingerprint unit test (1). These are 30 executions, not 30 distinct scenarios.
- Local test alternative uses PostgreSQL 14.22; CI uses PostgreSQL 16. Java 21.0.13 / Spring Boot 4.1.2.
- [Naive race evidence](NAIVE_RACE.md): two orders for one item; intentional no-oversell assertion fails. Normal CI asserts the reproducible broken outcome and remains green.
- All strategies pass the 1,000-attempt/100-stock check, idempotency, duplicate-buyer, rollback, multi-unit and HTTP contract cases. Optimistic retry recovery and rollback after exhaustion are tested separately.
- [18 measured k6 runs](benchmarks/RESULTS.md), 180,000 measured checkout attempts, plus excluded warmups. Stock conservation verified for every run.
- Atomic and pessimistic: no failed purchases. Optimistic: 196 total 503s across the three in-stock runs; all writes rolled back, no overselling. The benchmark availability threshold intentionally remains failed for those results.
- Separate commits preserve the race demonstration, pessimistic strategy, optimistic strategy, benchmark runner, contention-failure recording and measured report.

## Remaining work

- Redis gate, compensation plus crash recovery, and rate limiting.
- Docker image build and Compose runtime verification.
- Longer sustained/deployed-environment benchmarks; current measurements are short local bursts.
- Broader application metrics, authentication/authorization and deployment hardening.
- Distinct sale model for repeat sales and optional advanced extensions.
