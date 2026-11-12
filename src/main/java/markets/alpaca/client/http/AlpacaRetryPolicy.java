package markets.alpaca.client.http;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable configuration for {@link AlpacaRetryInterceptor}. */
public final class AlpacaRetryPolicy {

  private static final Set<String> DEFAULT_RETRYABLE_METHODS =
      Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES =
      Set.of(408, 425, 429, 500, 502, 503, 504);

  private final int maxAttempts;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final double jitterRatio;
  private final Set<String> retryableMethods;
  private final Set<Integer> retryableStatusCodes;
  private final AlpacaRetryListener listener;
  private final Clock clock;
  private final Sleeper sleeper;

  private AlpacaRetryPolicy(Builder builder) {
    this.maxAttempts = builder.maxAttempts;
    this.initialDelay = builder.initialDelay;
    this.maxDelay = builder.maxDelay;
    this.jitterRatio = builder.jitterRatio;
    this.retryableMethods = Set.copyOf(builder.retryableMethods);
    this.retryableStatusCodes = Set.copyOf(builder.retryableStatusCodes);
    this.listener = builder.listener;
    this.clock = builder.clock;
    this.sleeper = builder.sleeper;
  }

  /** Returns a conservative retry policy for idempotent requests. */
  public static AlpacaRetryPolicy defaultPolicy() {
    return builder().build();
  }

  /** Returns a builder initialized with conservative defaults. */
  public static Builder builder() {
    return new Builder();
  }

  public int maxAttempts() {
    return maxAttempts;
  }

  public Duration initialDelay() {
    return initialDelay;
  }

  public Duration maxDelay() {
    return maxDelay;
  }

  public double jitterRatio() {
    return jitterRatio;
  }

  public Set<String> retryableMethods() {
    return retryableMethods;
  }

  public Set<Integer> retryableStatusCodes() {
    return retryableStatusCodes;
  }

  public AlpacaRetryListener listener() {
    return listener;
  }

  Clock clock() {
    return clock;
  }

  void sleep(Duration delay) throws IOException {
    sleeper.sleep(delay);
  }

  boolean isRetryableMethod(String method) {
    return retryableMethods.contains(method.toUpperCase(Locale.ROOT));
  }

  boolean isRetryableStatusCode(int statusCode) {
    return retryableStatusCodes.contains(statusCode);
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration delay) throws IOException;
  }

  /** Builder for {@link AlpacaRetryPolicy}. */
  public static final class Builder {
    private int maxAttempts = 3;
    private Duration initialDelay = Duration.ofMillis(250);
    private Duration maxDelay = Duration.ofSeconds(5);
    private double jitterRatio = 0.2;
    private Set<String> retryableMethods = DEFAULT_RETRYABLE_METHODS;
    private Set<Integer> retryableStatusCodes = DEFAULT_RETRYABLE_STATUS_CODES;
    private AlpacaRetryListener listener = AlpacaRetryListener.NONE;
    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = AlpacaRetryPolicy::sleepThread;

    private Builder() {}

    /** Sets the total number of attempts, including the first request. Defaults to 3. */
    public Builder maxAttempts(int maxAttempts) {
      if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
      this.maxAttempts = maxAttempts;
      return this;
    }

    /** Sets the first exponential-backoff delay. Defaults to 250 ms. */
    public Builder initialDelay(Duration initialDelay) {
      this.initialDelay = requireNonNegative(initialDelay, "initialDelay");
      return this;
    }

    /** Sets the maximum computed retry delay. Defaults to 5 seconds. */
    public Builder maxDelay(Duration maxDelay) {
      this.maxDelay = requireNonNegative(maxDelay, "maxDelay");
      return this;
    }

    /** Sets the jitter ratio added to computed backoff delays. Defaults to 0.2. */
    public Builder jitterRatio(double jitterRatio) {
      if (Double.isNaN(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
        throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
      }
      this.jitterRatio = jitterRatio;
      return this;
    }

    /** Sets HTTP methods eligible for retry. Defaults to idempotent methods only. */
    public Builder retryableMethods(Collection<String> retryableMethods) {
      Objects.requireNonNull(retryableMethods, "retryableMethods must not be null");
      if (retryableMethods.isEmpty()) {
        throw new IllegalArgumentException("retryableMethods must not be empty");
      }
      this.retryableMethods =
          retryableMethods.stream()
              .map(
                  method ->
                      Objects.requireNonNull(method, "retryableMethods must not contain null"))
              .map(method -> method.toUpperCase(Locale.ROOT))
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      return this;
    }

    /** Sets HTTP status codes eligible for retry. */
    public Builder retryableStatusCodes(Collection<Integer> retryableStatusCodes) {
      Objects.requireNonNull(retryableStatusCodes, "retryableStatusCodes must not be null");
      if (retryableStatusCodes.isEmpty()) {
        throw new IllegalArgumentException("retryableStatusCodes must not be empty");
      }
      this.retryableStatusCodes =
          retryableStatusCodes.stream()
              .map(
                  code ->
                      Objects.requireNonNull(code, "retryableStatusCodes must not contain null"))
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      return this;
    }

    /** Sets optional hooks for observing retry decisions. */
    public Builder listener(AlpacaRetryListener listener) {
      this.listener = Objects.requireNonNull(listener, "listener must not be null");
      return this;
    }

    Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    Builder sleeper(Sleeper sleeper) {
      this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
      return this;
    }

    public AlpacaRetryPolicy build() {
      if (maxDelay.compareTo(initialDelay) < 0) {
        throw new IllegalArgumentException(
            "maxDelay must be greater than or equal to initialDelay");
      }
      return new AlpacaRetryPolicy(this);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
      Objects.requireNonNull(duration, name + " must not be null");
      if (duration.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
      return duration;
    }
  }

  private static void sleepThread(Duration delay) throws IOException {
    if (delay.isZero() || delay.isNegative()) return;
    try {
      Thread.sleep(delay.toMillis(), delay.toNanosPart() % 1_000_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      var interrupted = new java.io.InterruptedIOException("Interrupted during retry backoff");
      interrupted.initCause(e);
      throw interrupted;
    }
  }
}
