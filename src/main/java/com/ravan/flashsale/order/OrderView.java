package com.ravan.flashsale.order;

import java.time.OffsetDateTime;

public record OrderView(
    long id,
    long productId,
    String userId,
    int quantity,
    String status,
    OffsetDateTime createdAt) {}
