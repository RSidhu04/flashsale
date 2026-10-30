# Captured naive race

On 2026-10-30 the test-only naive checkout reproduced a lost update against local PostgreSQL 14.22. Two transactions each read stock 1, wait at a latch, then independently write 0 and create an order. Both commit: two orders for one item. The nonnegative stock constraint does not catch this.

Run the deliberately failing invariant:

```sh
./mvnw -Dflashsale.test.embedded=true -Dtest=CheckoutIntegrationTest#naiveReadModifyWriteDeterministicallyOversells -Dflashsale.demo.fail=true test
```

Omit the embedded flag to use PostgreSQL 16 Testcontainers. Expected result: BUILD FAILURE, because 2 orders exceeds starting stock 1. Captured output:

```text
NAIVE EVIDENCE: starting_stock=1, orders=2, remaining_stock=0, oversold_units=1
2026-10-30T11:32:38.233-05:00  INFO 98027 --- [flashsale] [gres:pid(98038)] i.z.t.d.p.embedded.EmbeddedPostgres      : 2026-10-30 11:32:38.233 CDT [98040] LOG:  received fast shutdown request
2026-10-30T11:32:38.338-05:00  INFO 98027 --- [flashsale] [_ctl:pid(98067)] i.z.t.d.p.embedded.EmbeddedPostgres      : waiting for server to shut down.... done
2026-10-30T11:32:38.338-05:00  INFO 98027 --- [flashsale] [_ctl:pid(98067)] i.z.t.d.p.embedded.EmbeddedPostgres      : server stopped
2026-10-30T11:32:38.338-05:00  INFO 98027 --- [flashsale] [           main] i.z.t.d.p.embedded.EmbeddedPostgres      : 2da71a08-434a-40e6-a3bc-41705e484b78 shut down postmaster in 00:00:00.109
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3.178 s <<< FAILURE! -- in com.ravan.flashsale.CheckoutIntegrationTest
[ERROR] com.ravan.flashsale.CheckoutIntegrationTest.naiveReadModifyWriteDeterministicallyOversells -- Time elapsed: 0.297 s <<< FAILURE!
java.lang.AssertionError: 
[orders must not exceed starting stock] 
Expecting actual:
  2
to be less than or equal to:
  1 
	at com.ravan.flashsale.CheckoutIntegrationTest.naiveReadModifyWriteDeterministicallyOversells(CheckoutIntegrationTest.java:114)


```

Normal CI omits `flashsale.demo.fail` and asserts the known broken outcome, so the demonstration is reproducible without leaving CI red. Production checkout never uses this code. The atomic-update concurrency test is the corresponding passing case. This is a controlled interleaving, not a claim about the probability of a race in unsynchronized traffic.
