package com.ravan.flashsale.order;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CheckoutFingerprintTest {
  @Test
  void fingerprintIsStableAndBindsEveryField() {
    var request = new CheckoutRequest(1, "a:2", 3);
    String hash = CheckoutService.fingerprint(request);
    assertThat(hash).hasSize(64).isEqualTo(CheckoutService.fingerprint(request));
    assertThat(hash).isNotEqualTo(CheckoutService.fingerprint(new CheckoutRequest(2, "a:2", 3)));
    assertThat(hash).isNotEqualTo(CheckoutService.fingerprint(new CheckoutRequest(1, "a:2", 4)));
    assertThat(hash).isNotEqualTo(CheckoutService.fingerprint(new CheckoutRequest(1, "a", 3)));
  }
}
