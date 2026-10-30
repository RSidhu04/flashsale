package com.ravan.flashsale.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

public class OptimisticInventoryStrategy implements InventoryStrategy {
  private final JdbcTemplate jdbc;

  public OptimisticInventoryStrategy(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean tryReserve(long productId, int quantity) {
    if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    var rows =
        jdbc.queryForList(
            "SELECT available, version FROM inventory WHERE product_id = ?", productId);
    if (rows.isEmpty() || ((Number) rows.getFirst().get("available")).intValue() < quantity)
      return false;
    long version = ((Number) rows.getFirst().get("version")).longValue();
    // Explicit SQL compare-and-set implements optimistic locking without a managed JPA entity.
    int changed =
        jdbc.update(
            """
            UPDATE inventory SET available = available - ?, version = version + 1
            WHERE product_id = ? AND version = ? AND available >= ?
            """,
            quantity,
            productId,
            version,
            quantity);
    if (changed == 0) throw new InventoryConflictException();
    return true;
  }
}
