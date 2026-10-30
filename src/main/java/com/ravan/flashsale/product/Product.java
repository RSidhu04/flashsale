package com.ravan.flashsale.product;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false)
  private long priceCents;

  protected Product() {}

  public Product(String name, long priceCents) {
    this.name = name;
    this.priceCents = priceCents;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public long getPriceCents() {
    return priceCents;
  }
}
