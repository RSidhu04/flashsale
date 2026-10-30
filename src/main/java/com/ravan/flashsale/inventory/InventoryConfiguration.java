package com.ravan.flashsale.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class InventoryConfiguration {
  @Bean
  InventoryStrategy inventoryStrategy(
      @Value("${flashsale.inventory-strategy:atomic}") String strategy, JdbcTemplate jdbc) {
    return switch (strategy) {
      case "atomic" -> new AtomicInventoryStrategy(jdbc);
      case "pessimistic" -> new PessimisticInventoryStrategy(jdbc);
      case "optimistic" -> new OptimisticInventoryStrategy(jdbc);
      default -> throw new IllegalArgumentException("Unknown inventory strategy: " + strategy);
    };
  }
}
