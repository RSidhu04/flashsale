package com.ravan.flashsale.inventory;

/** Called inside the checkout transaction; the reservation is not committed on its own. */
public interface InventoryStrategy {
  boolean tryReserve(long productId, int quantity);
}
