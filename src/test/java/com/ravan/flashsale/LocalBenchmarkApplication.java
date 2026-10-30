package com.ravan.flashsale;

import io.micrometer.core.instrument.MeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Development-only launcher and SQL verification endpoints; excluded from the production JAR. */
public class LocalBenchmarkApplication {
  public static void main(String[] args) throws Exception {
    var postgres =
        EmbeddedPostgres.builder()
            .setServerConfig("fsync", "on")
            .setServerConfig("synchronous_commit", "on")
            .setServerConfig("full_page_writes", "on")
            .start();
    System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
    System.setProperty("spring.datasource.username", "postgres");
    System.setProperty("spring.datasource.password", "postgres");
    System.setProperty("server.address", "127.0.0.1");
    System.setProperty("spring.profiles.active", "local-benchmark");
    var context =
        SpringApplication.run(
            new Class<?>[] {FlashsaleApplication.class, VerificationController.class}, args);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  context.close();
                  try {
                    postgres.close();
                  } catch (java.io.IOException e) {
                    e.printStackTrace();
                  }
                }));
  }

  @RestController
  @org.springframework.context.annotation.Profile("local-benchmark")
  static class VerificationController {
    final JdbcTemplate jdbc;
    final MeterRegistry meters;
    final String strategy;

    VerificationController(
        JdbcTemplate jdbc,
        MeterRegistry meters,
        @Value("${flashsale.inventory-strategy}") String strategy) {
      this.jdbc = jdbc;
      this.meters = meters;
      this.strategy = strategy;
    }

    @GetMapping("/benchmark/environment")
    Map<String, Object> environment() {
      return Map.of(
          "strategy",
          strategy,
          "java",
          System.getProperty("java.version"),
          "postgres",
          jdbc.queryForObject("SELECT version()", String.class),
          "settings",
          jdbc.queryForList(
              "SELECT name,setting FROM pg_settings WHERE name IN"
                  + " ('fsync','synchronous_commit','full_page_writes','shared_buffers','max_connections')"));
    }

    @GetMapping("/benchmark/verify/{id}")
    Map<String, Object> verify(@PathVariable long id) {
      var result =
          new LinkedHashMap<String, Object>(
              jdbc.queryForMap(
                  """
                  SELECT i.available, count(o.id) AS orders, coalesce(sum(o.quantity),0) AS units_sold
                  FROM inventory i LEFT JOIN orders o ON o.product_id=i.product_id
                  WHERE i.product_id=? GROUP BY i.available
                  """,
                  id));
      result.put("productId", id);
      result.put(
          "idempotencyKeys",
          jdbc.queryForObject(
              "SELECT count(*) FROM idempotency_keys k JOIN orders o ON o.id=k.order_id WHERE"
                  + " o.product_id=?",
              Long.class,
              id));
      result.put(
          "conflictsTotal", meters.counter("flashsale.checkout.optimistic.conflicts").count());
      result.put(
          "exhaustedTotal", meters.counter("flashsale.checkout.optimistic.exhausted").count());
      return result;
    }
  }
}
