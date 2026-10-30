package com.ravan.flashsale;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "flashsale.inventory-strategy=pessimistic")
class PessimisticCheckoutIntegrationTest extends CheckoutIntegrationTest {}
