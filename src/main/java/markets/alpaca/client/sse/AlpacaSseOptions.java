package markets.alpaca.client.sse;

import java.time.Duration;
import java.util.Objects;

/** Immutable transport and resource limits for an SSE client. */
public final class AlpacaSseOptions {

  private static final long DEFAULT_MAX_EVENT_BYTES = 1024L * 1024L;
  private static final long DEFAULT_MAX_ERROR_BODY_BYTES = 64L * 1024L;

  private final AlpacaSseReconnectPolicy reconnectPolicy;
  private final String initialLastEventId;
  private final Duration connectTimeout;
  private final Duration idleTimeout;
  private final Duration maxDuration;
  private final long maxLineBytes;
  private final long maxEventBytes;
  private final long maxErrorBodyBytes;

  private AlpacaSseOptions(Builder builder) {
    reconnectPolicy = builder.reconnectPolicy;
    initialLastEventId = builder.initialLastEventId;
    connectTimeout = builder.connectTimeout;
    idleTimeout = builder.idleTimeout;
    maxDuration = builder.maxDuration;
    maxLineBytes = builder.maxLineBytes;
    maxEventBytes = builder.maxEventBytes;
    maxErrorBodyBytes = builder.maxErrorBodyBytes;
  }

  /** Returns resilient defaults suitable for Trading activity streams. */
  public static AlpacaSseOptions defaults() {
    return builder().build();
  }

  /** Returns defaults with automatic reconnect disabled. */
  public static AlpacaSseOptions reconnectDisabled() {
    return builder().reconnectPolicy(AlpacaSseReconnectPolicy.disabled()).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return builder()
        .reconnectPolicy(reconnectPolicy)
        .initialLastEventId(initialLastEventId)
        .connectTimeout(connectTimeout)
        .idleTimeout(idleTimeout)
        .maxDuration(maxDuration)
        .maxLineBytes(maxLineBytes)
        .maxEventBytes(maxEventBytes)
        .maxErrorBodyBytes(maxErrorBodyBytes);
  }

  public AlpacaSseReconnectPolicy reconnectPolicy() {
    return reconnectPolicy;
  }

  public String initialLastEventId() {
    return initialLastEventId;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration idleTimeout() {
    return idleTimeout;
  }

  public Duration maxDuration() {
    return maxDuration;
  }

  public long maxLineBytes() {
    return maxLineBytes;
  }

  public long maxEventBytes() {
    return maxEventBytes;
  }

  public long maxErrorBodyBytes() {
    return maxErrorBodyBytes;
  }

  /** Builder for {@link AlpacaSseOptions}. Null idle/max durations disable those limits. */
  public static final class Builder {
    private AlpacaSseReconnectPolicy reconnectPolicy = AlpacaSseReconnectPolicy.defaultPolicy();
    private String initialLastEventId;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration idleTimeout;
    private Duration maxDuration;
    private long maxLineBytes = DEFAULT_MAX_EVENT_BYTES;
    private long maxEventBytes = DEFAULT_MAX_EVENT_BYTES;
    private long maxErrorBodyBytes = DEFAULT_MAX_ERROR_BODY_BYTES;

    private Builder() {}

    public Builder reconnectPolicy(AlpacaSseReconnectPolicy reconnectPolicy) {
      this.reconnectPolicy =
          Objects.requireNonNull(reconnectPolicy, "reconnectPolicy must not be null");
      return this;
    }

    public Builder initialLastEventId(String initialLastEventId) {
      if (initialLastEventId != null
          && (initialLastEventId.indexOf('\r') >= 0 || initialLastEventId.indexOf('\n') >= 0)) {
        throw new IllegalArgumentException("initialLastEventId must not contain CR or LF");
      }
      this.initialLastEventId = initialLastEventId;
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = positive(connectTimeout, "connectTimeout");
      return this;
    }

    public Builder idleTimeout(Duration idleTimeout) {
      this.idleTimeout = optionalPositive(idleTimeout, "idleTimeout");
      return this;
    }

    public Builder maxDuration(Duration maxDuration) {
      this.maxDuration = optionalPositive(maxDuration, "maxDuration");
      return this;
    }

    public Builder maxLineBytes(long maxLineBytes) {
      this.maxLineBytes = positive(maxLineBytes, "maxLineBytes");
      return this;
    }

    public Builder maxEventBytes(long maxEventBytes) {
      this.maxEventBytes = positive(maxEventBytes, "maxEventBytes");
      return this;
    }

    public Builder maxErrorBodyBytes(long maxErrorBodyBytes) {
      this.maxErrorBodyBytes = positive(maxErrorBodyBytes, "maxErrorBodyBytes");
      return this;
    }

    public AlpacaSseOptions build() {
      return new AlpacaSseOptions(this);
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

    private static long positive(long value, String name) {
      if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
      return value;
    }
  }
}
