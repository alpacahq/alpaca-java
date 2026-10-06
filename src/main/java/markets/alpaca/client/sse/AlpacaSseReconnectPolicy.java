package markets.alpaca.client.sse;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Retry and backoff settings for Server-Sent Events connections. */
public final class AlpacaSseReconnectPolicy {

  /** Retry forever after an established stream disconnects. */
  public static final int UNLIMITED_ATTEMPTS = -1;

  private final int initialAttempts;
  private final int establishedAttempts;
  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final Duration maxElapsedTime;
  private final double jitterRatio;
  private final DoubleSupplier random;

  private AlpacaSseReconnectPolicy(Builder builder) {
    this.initialAttempts = builder.initialAttempts;
    this.establishedAttempts = builder.establishedAttempts;
    this.initialBackoff = builder.initialBackoff;
    this.maxBackoff = builder.maxBackoff;
    this.maxElapsedTime = builder.maxElapsedTime;
    this.jitterRatio = builder.jitterRatio;
    this.random = builder.random;
  }

  /** Returns the resilient Trading default: two initial retries and unlimited live reconnects. */
  public static AlpacaSseReconnectPolicy defaultPolicy() {
    return builder().build();
  }

  /** Returns a policy that never reconnects. */
  public static AlpacaSseReconnectPolicy disabled() {
    return builder().initialAttempts(0).establishedAttempts(0).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return builder()
        .initialAttempts(initialAttempts)
        .establishedAttempts(establishedAttempts)
        .initialBackoff(initialBackoff)
        .maxBackoff(maxBackoff)
        .maxElapsedTime(maxElapsedTime)
        .jitterRatio(jitterRatio)
        .random(random);
  }

  public int initialAttempts() {
    return initialAttempts;
  }

  public int establishedAttempts() {
    return establishedAttempts;
  }

  public Duration initialBackoff() {
    return initialBackoff;
  }

  /**
   * Returns the maximum reconnect delay.
   *
   * <p>The cap applies to client exponential backoff, server-provided SSE {@code retry:} values,
   * and HTTP {@code Retry-After} values.
   */
  public Duration maxBackoff() {
    return maxBackoff;
  }

  /**
   * Returns the maximum elapsed time for one reconnect cycle, or {@code null} when unbounded.
   *
   * <p>The initial cycle spans connection attempts and backoff until the first accepted response.
   * An established cycle starts after a transient disconnect and resets after an event is
   * delivered. The budget includes connection time, client backoff, {@code retry:}, and {@code
   * Retry-After} delays.
   *
   * <p>This is separate from {@link AlpacaSseOptions#maxDuration()}, which limits the lifetime of
   * the entire subscription.
   */
  public Duration maxElapsedTime() {
    return maxElapsedTime;
  }

  public double jitterRatio() {
    return jitterRatio;
  }

  public boolean allowsAttempt(boolean established, int attempt) {
    int maximum = established ? establishedAttempts : initialAttempts;
    return maximum == UNLIMITED_ATTEMPTS || attempt <= maximum;
  }

  public Duration delayForAttempt(int attempt) {
    int shift = Math.min(Math.max(attempt - 1, 0), 62);
    long base;
    try {
      base = Math.multiplyExact(initialBackoff.toMillis(), 1L << shift);
    } catch (ArithmeticException ignored) {
      base = Long.MAX_VALUE;
    }
    base = Math.min(base, maxBackoff.toMillis());
    if (base == 0 || jitterRatio == 0) return Duration.ofMillis(base);

    double value = Math.max(0, Math.min(1, random.getAsDouble()));
    double factor = (1 - jitterRatio) + (value * jitterRatio * 2);
    return Duration.ofMillis(Math.min(Math.round(base * factor), maxBackoff.toMillis()));
  }

  /** Builder for {@link AlpacaSseReconnectPolicy}. */
  public static final class Builder {
    private int initialAttempts = 2;
    private int establishedAttempts = UNLIMITED_ATTEMPTS;
    private Duration initialBackoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofSeconds(30);
    private Duration maxElapsedTime;
    private double jitterRatio = 0.2;
    private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

    private Builder() {}

    public Builder initialAttempts(int initialAttempts) {
      this.initialAttempts = attempts(initialAttempts, "initialAttempts");
      return this;
    }

    public Builder establishedAttempts(int establishedAttempts) {
      this.establishedAttempts = attempts(establishedAttempts, "establishedAttempts");
      return this;
    }

    public Builder initialBackoff(Duration initialBackoff) {
      this.initialBackoff = positive(initialBackoff, "initialBackoff");
      return this;
    }

    /** Sets the cap for client and server-directed reconnect delays. */
    public Builder maxBackoff(Duration maxBackoff) {
      this.maxBackoff = positive(maxBackoff, "maxBackoff");
      return this;
    }

    /**
     * Sets the elapsed-time budget for one initial-open or established reconnect cycle.
     *
     * <p>Pass {@code null} to leave reconnect cycles unbounded.
     */
    public Builder maxElapsedTime(Duration maxElapsedTime) {
      this.maxElapsedTime = optionalPositive(maxElapsedTime, "maxElapsedTime");
      return this;
    }

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

    public AlpacaSseReconnectPolicy build() {
      if (maxBackoff.compareTo(initialBackoff) < 0) {
        throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
      }
      return new AlpacaSseReconnectPolicy(this);
    }

    private static int attempts(int value, String name) {
      if (value < UNLIMITED_ATTEMPTS) {
        throw new IllegalArgumentException(name + " must be >= 0 or UNLIMITED_ATTEMPTS");
      }
      return value;
    }

    private static Duration positive(Duration value, String name) {
      Objects.requireNonNull(value, name + " must not be null");
      if (value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return value;
    }

    private static Duration optionalPositive(Duration value, String name) {
      return value == null ? null : positive(value, name);
    }
  }
}
