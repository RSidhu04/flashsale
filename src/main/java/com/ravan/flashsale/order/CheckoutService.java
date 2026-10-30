package com.ravan.flashsale.order;

import com.ravan.flashsale.common.ApiException;
import com.ravan.flashsale.inventory.InventoryStrategy;
import com.ravan.flashsale.product.ProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class CheckoutService {
  public record Result(OrderView order, boolean replay) {}

  private final JdbcTemplate jdbc;
  private final ProductRepository products;
  private final InventoryStrategy inventory;
  private final OrderRepository orders;

  public CheckoutService(
      JdbcTemplate jdbc,
      ProductRepository products,
      InventoryStrategy inventory,
      OrderRepository orders) {
    this.jdbc = jdbc;
    this.products = products;
    this.inventory = inventory;
    this.orders = orders;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result checkout(@NotBlank @Size(max = 100) String key, @Valid CheckoutRequest request) {
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
