package com.ravan.flashsale.order;

import jakarta.validation.constraints.*;

public record CheckoutRequest(
    @Positive long productId, @NotBlank @Size(max = 100) String userId, @Positive int quantity) {}
