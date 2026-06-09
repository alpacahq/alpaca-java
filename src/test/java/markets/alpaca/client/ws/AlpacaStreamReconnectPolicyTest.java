package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AlpacaStreamReconnectPolicyTest {

  @Test
  void defaultPolicy_hasBoundedAttempts() {
    var policy = AlpacaStreamReconnectPolicy.defaultPolicy();

    assertEquals(Duration.ofSeconds(1), policy.initialBackoff());
    assertEquals(Duration.ofSeconds(64), policy.maxBackoff());
    assertEquals(10, policy.maxAttempts());
    assertEquals(0.2, policy.jitterRatio());
    assertTrue(policy.allowsAttempt(10));
    assertFalse(policy.allowsAttempt(11));
  }

  @Test
  void disabledPolicy_allowsNoReconnectAttempts() {
    var policy = AlpacaStreamReconnectPolicy.disabled();

    assertEquals(0, policy.maxAttempts());
    assertFalse(policy.allowsAttempt(1));
  }

  @Test
  void delayMillisForAttempt_usesExponentialBackoffCappedByMax() {
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(100))
            .maxBackoff(Duration.ofMillis(250))
            .jitterRatio(0)
            .build();

    assertEquals(100, policy.delayMillisForAttempt(1));
    assertEquals(200, policy.delayMillisForAttempt(2));
    assertEquals(250, policy.delayMillisForAttempt(3));
    assertEquals(250, policy.delayMillisForAttempt(10));
  }

  @Test
  void jitteredDelayMillisForAttempt_appliesConfiguredJitterWithinBounds() {
    var lowest =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(1_000))
            .maxBackoff(Duration.ofMillis(10_000))
            .jitterRatio(0.2)
            .random(() -> 0)
            .build();
    var highest =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(1_000))
            .maxBackoff(Duration.ofMillis(10_000))
            .jitterRatio(0.2)
            .random(() -> 1)
            .build();

    assertEquals(800, lowest.jitteredDelayMillisForAttempt(1));
    assertEquals(1_200, highest.jitteredDelayMillisForAttempt(1));
  }

  @Test
  void jitteredDelayMillisForAttempt_isDeterministicWhenJitterIsDisabled() {
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(1_000))
            .maxBackoff(Duration.ofMillis(10_000))
            .jitterRatio(0)
            .random(() -> 1)
            .build();

    assertEquals(1_000, policy.jitteredDelayMillisForAttempt(1));
    assertEquals(2_000, policy.jitteredDelayMillisForAttempt(2));
  }

  @Test
  void builder_rejectsInvalidDurationsAndAttempts() {
    assertThrows(
        NullPointerException.class,
        () -> AlpacaStreamReconnectPolicy.builder().initialBackoff(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().initialBackoff(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().maxBackoff(Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().maxAttempts(-2));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().jitterRatio(-0.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().jitterRatio(1.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaStreamReconnectPolicy.builder().jitterRatio(Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AlpacaStreamReconnectPolicy.builder()
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(1))
                .build());
  }

  @Test
  void unlimitedAttempts_allowsAnyAttempt() {
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .maxAttempts(AlpacaStreamReconnectPolicy.UNLIMITED_ATTEMPTS)
            .build();

    assertTrue(policy.allowsAttempt(1));
    assertTrue(policy.allowsAttempt(10_000));
  }
}
