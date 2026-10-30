package com.ravan.flashsale.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CheckoutController {
  private final CheckoutService checkout;
  private final OrderRepository orders;

  public CheckoutController(CheckoutService checkout, OrderRepository orders) {
    this.checkout = checkout;
    this.orders = orders;
  }

  @PostMapping("/checkout")
  ResponseEntity<OrderView> checkout(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String key,
      @Valid @RequestBody CheckoutRequest request) {
    var result = checkout.checkout(key, request);
    return result.replay()
        ? ResponseEntity.ok(result.order())
        : ResponseEntity.created(URI.create("/orders/" + result.order().id())).body(result.order());
  }

  @GetMapping("/orders/{id}")
  OrderView get(@PathVariable long id) {
    return orders.get(id);
  }
}
