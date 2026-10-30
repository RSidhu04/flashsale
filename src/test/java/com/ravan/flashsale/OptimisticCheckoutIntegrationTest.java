package com.ravan.flashsale;

import static org.assertj.core.api.Assertions.*;

import com.ravan.flashsale.common.ApiException;
import com.ravan.flashsale.inventory.*;
import com.ravan.flashsale.order.*;
import com.ravan.flashsale.product.ProductRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "flashsale.inventory-strategy=optimistic")
class OptimisticCheckoutIntegrationTest extends CheckoutIntegrationTest {
  @Autowired ProductRepository productRepository;
  @Autowired OrderRepository orderRepository;

  @Test
  void exhaustedRetriesRollBackEveryKeyAndReservation() {
    long id = product(5);
    AtomicInteger calls = new AtomicInteger();
    var service =
        new CheckoutService(
            jdbc,
            productRepository,
            (p, q) -> {
              calls.incrementAndGet();
              // Force a failure after a write to prove transaction rollback, not just a read
              // conflict.
              jdbc.update("UPDATE inventory SET available=available-? WHERE product_id=?", q, p);
              throw new InventoryConflictException();
            },
            orderRepository,
            transactions,
            new SimpleMeterRegistry(),
            3);
    assertThatThrownBy(() -> service.checkout("exhausted", new CheckoutRequest(id, "buyer", 1)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    assertThat(calls.get()).isEqualTo(3);
    assertThat(stock(id)).isEqualTo(5);
    assertThat(count("orders")).isZero();
    assertThat(count("idempotency_keys")).isZero();
  }

  @Test
  void retryEventuallyCommitsOnceAndCanReplay() {
    long id = product(5);
    AtomicInteger calls = new AtomicInteger();
    var strategy = new OptimisticInventoryStrategy(jdbc);
    var service =
        new CheckoutService(
            jdbc,
            productRepository,
            (p, q) -> {
              if (calls.incrementAndGet() < 3) throw new InventoryConflictException();
              return strategy.tryReserve(p, q);
            },
            orderRepository,
            transactions,
            new SimpleMeterRegistry(),
            3);
    var request = new CheckoutRequest(id, "buyer", 1);
    var first = service.checkout("retry", request);
    var replay = service.checkout("retry", request);
    assertThat(first.replay()).isFalse();
    assertThat(replay.replay()).isTrue();
    assertThat(replay.order().id()).isEqualTo(first.order().id());
    assertThat(calls.get()).isEqualTo(3);
    assertThat(stock(id)).isEqualTo(4);
    assertThat(count("orders")).isEqualTo(1);
    assertThat(count("idempotency_keys")).isEqualTo(1);
  }
}
