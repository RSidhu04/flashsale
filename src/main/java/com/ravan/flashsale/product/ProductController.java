package com.ravan.flashsale.product;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
public class ProductController {
  private final ProductService service;

  public ProductController(ProductService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<ProductService.ProductView> create(
      @Valid @RequestBody ProductService.CreateProduct input) {
    var product = service.create(input);
    return ResponseEntity.created(URI.create("/products/" + product.id())).body(product);
  }

  @GetMapping("/{id}")
  ProductService.ProductView get(@PathVariable long id) {
    return service.get(id);
  }
}
