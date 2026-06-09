package markets.alpaca.client.ws;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Reconnect policy for Alpaca WebSocket stream clients. */
public final class AlpacaStreamReconnectPolicy {

  /** Use this value for {@link Builder#maxAttempts(int)} to retry forever. */
  public static final int UNLIMITED_ATTEMPTS = -1;

  private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
  private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(64);
  private static final int DEFAULT_MAX_ATTEMPTS = 10;
  private static final double DEFAULT_JITTER_RATIO = 0.2;

  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final int maxAttempts;
  private final double jitterRatio;
  private final DoubleSupplier random;

  private AlpacaStreamReconnectPolicy(Builder builder) {
    this.initialBackoff = builder.initialBackoff;
    this.maxBackoff = builder.maxBackoff;
    this.maxAttempts = builder.maxAttempts;
    this.jitterRatio = builder.jitterRatio;
    this.random = builder.random;
  }

  /**
   * Returns the default policy: 10 attempts, starting at 1 second, capped at 64 seconds, with 20%
   * jitter.
   */
  public static AlpacaStreamReconnectPolicy defaultPolicy() {
    return builder().build();
  }

  /** Returns a policy that never reconnects after an unexpected close or transport failure. */
  public static AlpacaStreamReconnectPolicy disabled() {
    return builder().maxAttempts(0).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Duration initialBackoff() {
    return initialBackoff;
  }

  public Duration maxBackoff() {
    return maxBackoff;
  }

  /**
   * Maximum reconnect attempts after one successful connection.
   *
   * <p>{@code 0} disables reconnects. {@link #UNLIMITED_ATTEMPTS} retries forever.
   */
  public int maxAttempts() {
    return maxAttempts;
  }

  /** Ratio used to randomize reconnect delays. {@code 0} disables jitter. */
  public double jitterRatio() {
    return jitterRatio;
  }

  public boolean allowsAttempt(int attempt) {
    return maxAttempts == UNLIMITED_ATTEMPTS || attempt <= maxAttempts;
  }

  public long delayMillisForAttempt(int attempt) {
    long initialMs = initialBackoff.toMillis();
    long maxMs = maxBackoff.toMillis();
    int shift = Math.min(Math.max(attempt - 1, 0), 62);
    long multiplier = 1L << shift;
    long delay;
    try {
      delay = Math.multiplyExact(initialMs, multiplier);
    } catch (ArithmeticException e) {
      delay = Long.MAX_VALUE;
    }
    return Math.min(delay, maxMs);
  }

  /** Returns the reconnect delay for {@code attempt}, with configured jitter applied. */
  public long jitteredDelayMillisForAttempt(int attempt) {
    long baseDelay = delayMillisForAttempt(attempt);
    if (baseDelay == 0 || jitterRatio == 0) return baseDelay;

    double randomValue = random.getAsDouble();
    if (randomValue < 0) randomValue = 0;
    if (randomValue > 1) randomValue = 1;
    double factor = (1 - jitterRatio) + (randomValue * jitterRatio * 2);
    long jittered = Math.round(baseDelay * factor);
    return Math.min(jittered, maxBackoff.toMillis());
  }

  /** Builder for {@link AlpacaStreamReconnectPolicy}. */
  public static final class Builder {
    private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
    private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    private double jitterRatio = DEFAULT_JITTER_RATIO;
    private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

    private Builder() {}

    /** Delay before the first reconnect attempt. Must be positive. */
    public Builder initialBackoff(Duration initialBackoff) {
      this.initialBackoff = positive(initialBackoff, "initialBackoff");
      return this;
    }

    /** Maximum delay between reconnect attempts. Must be positive. */
    public Builder maxBackoff(Duration maxBackoff) {
      this.maxBackoff = positive(maxBackoff, "maxBackoff");
      return this;
    }

    /**
     * Maximum number of reconnect attempts.
     *
     * <p>Use {@code 0} to disable reconnects or {@link #UNLIMITED_ATTEMPTS} to retry forever.
     */
    public Builder maxAttempts(int maxAttempts) {
      if (maxAttempts < UNLIMITED_ATTEMPTS) {
        throw new IllegalArgumentException("maxAttempts must be >= 0 or UNLIMITED_ATTEMPTS");
      }
      this.maxAttempts = maxAttempts;
      return this;
    }

    /** Jitter ratio applied to reconnect delays. Use {@code 0} for deterministic backoff. */
    public Builder jitterRatio(double jitterRatio) {
      if (Double.isNaN(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
        throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
      }
      this.jitterRatio = jitterRatio;
      return this;
    }

    Builder random(DoubleSupplier random) {
      this.random = Objects.requireNonNull(random, "random must not be null");
      return this;
    }

    public AlpacaStreamReconnectPolicy build() {
      if (maxBackoff.compareTo(initialBackoff) < 0) {
        throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
      }
      return new AlpacaStreamReconnectPolicy(this);
    }

    private static Duration positive(Duration duration, String name) {
      Objects.requireNonNull(duration, name + " must not be null");
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return duration;
    }
  }
}
