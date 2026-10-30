package com.ravan.flashsale.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

public class AtomicInventoryStrategy implements InventoryStrategy {
  private final JdbcTemplate jdbc;

  public AtomicInventoryStrategy(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean tryReserve(long productId, int quantity) {
    if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    return jdbc.update(
            """
            UPDATE inventory SET available = available - ?, version = version + 1
            WHERE product_id = ? AND available >= ?
            """,
            quantity,
            productId,
            quantity)
        == 1;
  }
}
