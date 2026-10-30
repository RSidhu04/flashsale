package com.ravan.flashsale.product;

import com.ravan.flashsale.common.ApiException;
import com.ravan.flashsale.inventory.InventoryRepository;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
  public record CreateProduct(
      @NotBlank @Size(max = 200) String name,
      @Positive long priceCents,
      @PositiveOrZero int stock) {}

  public record ProductView(long id, String name, long priceCents, int available) {}

  private final ProductRepository products;
  private final InventoryRepository inventory;

  public ProductService(ProductRepository products, InventoryRepository inventory) {
    this.products = products;
    this.inventory = inventory;
  }

  @Transactional
  public ProductView create(CreateProduct input) {
    Product p = products.saveAndFlush(new Product(input.name(), input.priceCents()));
    inventory.create(p.getId(), input.stock());
    return new ProductView(p.getId(), p.getName(), p.getPriceCents(), input.stock());
  }

  @Transactional(readOnly = true)
  public ProductView get(long id) {
    Product p = products.findById(id).orElseThrow(ApiException::notFound);
    return new ProductView(id, p.getName(), p.getPriceCents(), inventory.available(id));
  }
}
