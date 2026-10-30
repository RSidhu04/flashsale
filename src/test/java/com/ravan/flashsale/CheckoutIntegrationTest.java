package com.ravan.flashsale;

import static org.assertj.core.api.Assertions.*;

import com.ravan.flashsale.common.ApiException;
import com.ravan.flashsale.order.*;
import com.ravan.flashsale.product.ProductService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutIntegrationTest {
  // Fail if Docker is missing: never silently skip the database correctness suite.
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    if (Boolean.getBoolean("flashsale.test.embedded")) {
      try {
        embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.start();
        properties.add("spring.datasource.url", () -> embedded.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "postgres");
        return;
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    POSTGRES.start();
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;

  @AfterAll
  static void stopDatabase() throws Exception {
    if (embedded != null) embedded.close();
    if (POSTGRES.isRunning()) POSTGRES.stop();
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired CheckoutService checkout;
  @Autowired ProductService products;
  @Autowired JdbcTemplate jdbc;
  @org.springframework.boot.test.web.server.LocalServerPort int port;

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE idempotency_keys, orders, inventory, products RESTART IDENTITY CASCADE");
  }

  long product(int stock) {
    return products.create(new ProductService.CreateProduct("Demo", 1999, stock)).id();
  }

  int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  int stock(long product) {
    return products.get(product).available();
  }

  @Test
  void naiveReadModifyWriteDeterministicallyOversells() throws Exception {
    long id = product(1);
    CountDownLatch bothRead = new CountDownLatch(2);
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    race(
        2,
        i ->
            tx.execute(
                status -> {
                  int observed = stock(id);
                  bothRead.countDown();
                  try {
                    if (!bothRead.await(10, TimeUnit.SECONDS))
                      throw new IllegalStateException("Both readers did not arrive");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                  // Deliberately broken, test-only: both transactions write the same stale result.
                  jdbc.update(
                      "UPDATE inventory SET available = ? WHERE product_id = ?", observed - 1, id);
                  jdbc.update(
                      "INSERT INTO orders(product_id,user_id,quantity,status) VALUES (?, ?, 1,"
                          + " 'CONFIRMED')",
                      id,
                      "naive-" + i);
                  return true;
                }));
    System.out.printf(
        "NAIVE EVIDENCE: starting_stock=1, orders=%d, remaining_stock=%d, oversold_units=%d%n",
        count("orders"), stock(id), count("orders") - 1);
    assertThat(stock(id)).isZero();
    assertThat(count("orders")).isEqualTo(2);
    // Opt-in failing invariant for a reproducible before/after demonstration.
    if (Boolean.getBoolean("flashsale.demo.fail"))
      assertThat(count("orders"))
          .as("orders must not exceed starting stock")
          .isLessThanOrEqualTo(1);
  }

  @Test
  void thousandBuyersCompeteForOneHundredItems() throws Exception {
    long id = product(100);
    var outcomes =
        race(
            1000,
            i -> {
              try {
                checkout.checkout("key-" + i, new CheckoutRequest(id, "user-" + i, 1));
                return true;
              } catch (ApiException e) {
                assertThat(e.getMessage()).isEqualTo("Insufficient stock");
                return false;
              }
            });
    assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(100);
    assertThat(count("orders")).isEqualTo(100);
    assertThat(count("idempotency_keys")).isEqualTo(100);
    assertThat(stock(id)).isZero();
  }

  @Test
  void twentyConcurrentRetriesReturnOneOrder() throws Exception {
    long id = product(100);
    var results = race(20, i -> checkout.checkout("same-key", new CheckoutRequest(id, "buyer", 2)));
    assertThat(results.stream().map(r -> r.order().id()).distinct().count()).isEqualTo(1);
    assertThat(results.stream().filter(r -> !r.replay()).count()).isEqualTo(1);
    assertThat(count("orders")).isEqualTo(1);
    assertThat(stock(id)).isEqualTo(98);
  }

  @Test
  void conflictingKeyDoesNotMutateStock() {
    long id = product(10);
    checkout.checkout("key", new CheckoutRequest(id, "buyer", 2));
    assertThatThrownBy(() -> checkout.checkout("key", new CheckoutRequest(id, "buyer", 3)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> checkout.checkout("key", new CheckoutRequest(id, "other", 2)))
        .isInstanceOf(ApiException.class);
    assertThat(stock(id)).isEqualTo(8);
    assertThat(count("orders")).isEqualTo(1);
  }

  @Test
  void duplicateBuyerRollsBackInventoryAndNewKey() {
    long id = product(10);
    checkout.checkout("first", new CheckoutRequest(id, "buyer", 2));
    assertThatThrownBy(() -> checkout.checkout("second", new CheckoutRequest(id, "buyer", 2)))
        .isInstanceOf(ApiException.class);
    assertThat(stock(id)).isEqualTo(8);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }

  @Test
  void concurrentDifferentKeysForOneBuyerCreateOneOrder() throws Exception {
    long id = product(100);
    var results =
        race(
            20,
            i -> {
              try {
                checkout.checkout("key-" + i, new CheckoutRequest(id, "buyer", 1));
                return true;
              } catch (ApiException e) {
                assertThat(e.getMessage()).isEqualTo("User already ordered this product");
                return false;
              }
            });
    assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    assertThat(stock(id)).isEqualTo(99);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }

  @Test
  void soldOutAndMissingProductLeaveNoKey() {
    long id = product(1);
    assertThatThrownBy(() -> checkout.checkout("key", new CheckoutRequest(id, "buyer", 2)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> checkout.checkout("key", new CheckoutRequest(999, "buyer", 1)))
        .isInstanceOf(ApiException.class);
    assertThat(count("orders")).isZero();
    assertThat(count("idempotency_keys")).isZero();
    assertThat(stock(id)).isEqualTo(1);
  }

  @Test
  void multiUnitPurchasesConserveStock() throws Exception {
    long id = product(100);
    var results =
        race(
            100,
            i -> {
              try {
                checkout.checkout("key-" + i, new CheckoutRequest(id, "buyer-" + i, 3));
                return true;
              } catch (ApiException e) {
                assertThat(e.getMessage()).isEqualTo("Insufficient stock");
                return false;
              }
            });
    assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(33);
    assertThat(stock(id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM orders", Integer.class))
        .isEqualTo(99);
  }

  @Test
  void httpContractValidatesAndReplays() throws Exception {
    long id = product(3);
    String body = "{\"productId\":" + id + ",\"userId\":\"buyer\",\"quantity\":1}";
    assertThat(post(body, "key").statusCode()).isEqualTo(201);
    assertThat(post(body, "key").statusCode()).isEqualTo(200);
    assertThat(post(body.replace("buyer", "other"), "key").statusCode()).isEqualTo(409);
    assertThat(post(body.replace("quantity\":1", "quantity\":0"), "invalid").statusCode())
        .isEqualTo(400);
    assertThat(post(body, null).statusCode()).isEqualTo(400);
    assertThat(post(body, " ").statusCode()).isEqualTo(400);
  }

  java.net.http.HttpResponse<String> post(String body, String key) throws Exception {
    var builder =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/checkout"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (key != null) builder.header("Idempotency-Key", key);
    return java.net.http.HttpClient.newHttpClient()
        .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  // Read every Future: worker exceptions must fail the test, never disappear.
  <T> List<T> race(int attempts, IntFunction<T> action) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, attempts));
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < attempts; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return action.apply(index);
                }));
      }
      start.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      List<T> results = new ArrayList<>();
      for (var future : futures)
        results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      return results;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
