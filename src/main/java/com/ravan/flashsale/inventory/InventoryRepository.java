package com.ravan.flashsale.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
  private final JdbcTemplate jdbc;

  public InventoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void create(long productId, int stock) {
    jdbc.update("INSERT INTO inventory(product_id, available) VALUES (?, ?)", productId, stock);
  }

  public int available(long productId) {
    return jdbc.queryForObject(
        "SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
  }

  // PostgreSQL locks the row and rechecks the predicate after competing updates commit.
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
