package com.ravan.flashsale.order;

import com.ravan.flashsale.common.ApiException;
import com.ravan.flashsale.inventory.InventoryConflictException;
import com.ravan.flashsale.inventory.InventoryStrategy;
import com.ravan.flashsale.product.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class CheckoutService {
  public record Result(OrderView order, boolean replay) {}

  private final JdbcTemplate jdbc;
  private final ProductRepository products;
  private final InventoryStrategy inventory;
  private final OrderRepository orders;
  private final TransactionTemplate transaction;
  private final int maxAttempts;
  private final Counter conflicts;
  private final Counter exhausted;

  public CheckoutService(
      JdbcTemplate jdbc,
      ProductRepository products,
      InventoryStrategy inventory,
      OrderRepository orders,
      PlatformTransactionManager transactionManager,
      MeterRegistry meters,
      @Value("${flashsale.optimistic-max-attempts:32}") int maxAttempts) {
    this.jdbc = jdbc;
    this.products = products;
    this.inventory = inventory;
    this.orders = orders;
    if (maxAttempts < 1 || maxAttempts > 100)
      throw new IllegalArgumentException("Retry attempts must be between 1 and 100");
    this.maxAttempts = maxAttempts;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    conflicts = meters.counter("flashsale.checkout.optimistic.conflicts");
    exhausted = meters.counter("flashsale.checkout.optimistic.exhausted");
  }

  public Result checkout(@NotBlank @Size(max = 100) String key, @Valid CheckoutRequest request) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return transaction.execute(status -> checkoutOnce(key, request));
      } catch (InventoryConflictException conflict) {
        conflicts.increment();
        if (attempt == maxAttempts) break;
        // Sleep outside the transaction: no connection/row locks held during backoff.
        try {
          Thread.sleep(
              ThreadLocalRandom.current()
                  .nextLong(1, Math.min(25, 1L << Math.min(attempt, 5)) + 1));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ApiException(
              HttpStatus.SERVICE_UNAVAILABLE, "Checkout interrupted; retry with the same key");
        }
      }
    }
    exhausted.increment();
    throw new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "Inventory contention; retry with the same key");
  }

  private Result checkoutOnce(String key, CheckoutRequest request) {
    String hash = fingerprint(request);
    // A concurrent insert of the same key waits for the first transaction to finish.
    // DO NOTHING avoids poisoning the transaction with a unique-constraint exception.
    int claimed =
        jdbc.update(
            """
            INSERT INTO idempotency_keys(key, user_id, request_hash) VALUES (?, ?, ?)
            ON CONFLICT (key) DO NOTHING
            """,
            key,
            request.userId(),
            hash);
    if (claimed == 0) {
      var prior =
          jdbc.queryForMap(
              "SELECT user_id, request_hash, order_id FROM idempotency_keys WHERE key = ?", key);
      if (!request.userId().equals(prior.get("user_id")) || !hash.equals(prior.get("request_hash")))
        throw ApiException.conflict("Idempotency key already used for a different request");
      return new Result(orders.get(((Number) prior.get("order_id")).longValue()), true);
    }
    if (!products.existsById(request.productId())) throw ApiException.notFound();
    if (!inventory.tryReserve(request.productId(), request.quantity()))
      throw ApiException.conflict("Insufficient stock");
    // Throwing here rolls back the inventory reservation and the key together.
    OrderView order =
        orders
            .insert(request)
            .orElseThrow(() -> ApiException.conflict("User already ordered this product"));
    jdbc.update("UPDATE idempotency_keys SET order_id = ? WHERE key = ?", order.id(), key);
    return new Result(order, false);
  }

  static String fingerprint(CheckoutRequest r) {
    // Length-prefix the only variable-width field, avoiding delimiter ambiguity.
    String canonical =
        r.productId() + ":" + r.quantity() + ":" + r.userId().length() + ":" + r.userId();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
