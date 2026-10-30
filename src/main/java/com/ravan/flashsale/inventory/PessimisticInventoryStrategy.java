package com.ravan.flashsale.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

public class PessimisticInventoryStrategy implements InventoryStrategy {
  private final JdbcTemplate jdbc;

  public PessimisticInventoryStrategy(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean tryReserve(long productId, int quantity) {
    if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    // The row lock is held until the surrounding checkout commits or rolls back.
    var rows =
        jdbc.queryForList(
            "SELECT available FROM inventory WHERE product_id = ? FOR UPDATE",
            Integer.class,
            productId);
    if (rows.isEmpty() || rows.getFirst() < quantity) return false;
    jdbc.update(
        "UPDATE inventory SET available = available - ?, version = version + 1 WHERE product_id ="
            + " ?",
        quantity,
        productId);
    return true;
  }
}
