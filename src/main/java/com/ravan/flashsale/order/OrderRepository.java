package com.ravan.flashsale.order;

import com.ravan.flashsale.common.ApiException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<OrderView> MAPPER =
      (r, n) ->
          new OrderView(
              r.getLong("id"),
              r.getLong("product_id"),
              r.getString("user_id"),
              r.getInt("quantity"),
              r.getString("status"),
              r.getObject("created_at", OffsetDateTime.class));

  public OrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public OrderView get(long id) {
    return jdbc.query("SELECT * FROM orders WHERE id = ?", MAPPER, id).stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  public Optional<OrderView> insert(CheckoutRequest request) {
    return jdbc
        .query(
            """
            INSERT INTO orders(product_id, user_id, quantity, status) VALUES (?, ?, ?, 'CONFIRMED')
            ON CONFLICT (product_id, user_id) DO NOTHING RETURNING *
            """,
            MAPPER,
            request.productId(),
            request.userId(),
            request.quantity())
        .stream()
        .findFirst();
  }
}
