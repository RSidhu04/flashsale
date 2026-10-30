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
}
