package markets.alpaca.client.http;

import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Factory for OkHttp client instances with Alpaca-recommended defaults.
 *
 * <p>{@link #defaultClient()} returns a shared singleton — OkHttp instances hold a thread pool and
 * a connection pool and should be shared across the application when the same transport policy is
 * appropriate. Pass the result directly to {@code AlpacaClientFactory}, or use {@link
 * #defaultBuilder()} to create a customised instance for a specific API, credential set, timeout
 * profile, proxy, retry interceptor, logging policy, or connection pool.
 */
public final class AlpacaHttpConfig {

  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

  private static final OkHttpClient DEFAULT_CLIENT = defaultBuilder().build();

  private AlpacaHttpConfig() {}

  /**
   * Returns the shared singleton {@link OkHttpClient} with Alpaca defaults (no logging). Use {@link
   * #defaultBuilder()} if you need a distinct instance with different timeouts, interceptors,
   * retry/backoff behavior, logging, proxy settings, or connection-pool sizing. Use {@link
   * #retryingClient(AlpacaRetryPolicy)} for opt-in application-level retries of idempotent
   * requests.
   */
  public static OkHttpClient defaultClient() {
    return DEFAULT_CLIENT;
  }

  /**
   * Returns a pre-configured builder so callers can add their own interceptors, certificates,
   * retry/backoff policies, or other customisations before calling {@code build()}.
   *
   * <p>OkHttp's transport retry support is enabled by default, but application-level retries for
   * responses such as HTTP 429 or 5xx are opt-in. Add {@link AlpacaRetryInterceptor} when you want
   * conservative retries for idempotent requests.
   */
  public static OkHttpClient.Builder defaultBuilder() {
    return new OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
        .readTimeout(DEFAULT_READ_TIMEOUT)
        .writeTimeout(DEFAULT_WRITE_TIMEOUT)
        .addInterceptor(AlpacaAgentInterceptor.INSTANCE);
  }

  /**
   * Returns an HTTP client that identifies this SDK and its version in the {@code User-Agent}
   * header using {@code APCA-JAVA/<sdk-version> Java/<runtime-version>}.
   *
   * <p>If the supplied client already has the Alpaca agent interceptor, the same instance is
   * returned. Otherwise, a derived client is returned without modifying the supplied instance. The
   * SDK's canonical agent value replaces any existing {@code User-Agent} header.
   */
  public static OkHttpClient withAgentInformation(OkHttpClient httpClient) {
    if (httpClient == null) throw new NullPointerException("httpClient must not be null");
    boolean configured =
        httpClient.interceptors().stream()
            .anyMatch(interceptor -> interceptor instanceof AlpacaAgentInterceptor);
    return configured
        ? httpClient
        : httpClient.newBuilder().addInterceptor(AlpacaAgentInterceptor.INSTANCE).build();
  }

  /** Returns an OkHttp client with default timeouts and the default Alpaca retry policy. */
  public static OkHttpClient retryingClient() {
    return retryingClient(AlpacaRetryPolicy.defaultPolicy());
  }

  /** Returns an OkHttp client with default timeouts and the supplied Alpaca retry policy. */
  public static OkHttpClient retryingClient(AlpacaRetryPolicy policy) {
    return defaultBuilder().addInterceptor(AlpacaRetryInterceptor.create(policy)).build();
  }

  /**
   * Returns an OkHttpClient with HTTP-level request/response logging at the given level, writing to
   * the platform logger ({@code java.util.logging}).
   *
   * <p>{@link HttpLoggingInterceptor.Level#BASIC} logs request line, response code, and size — safe
   * for all environments. Higher levels log headers and bodies:
   *
   * <ul>
   *   <li>{@link HttpLoggingInterceptor.Level#HEADERS} — logs all headers. The {@code
   *       APCA-API-KEY-ID}, {@code APCA-API-SECRET-KEY}, and {@code Authorization} headers are
   *       redacted automatically by this method, but any other sensitive headers you add will not
   *       be.
   *   <li>{@link HttpLoggingInterceptor.Level#BODY} — logs headers and bodies; same redaction
   *       applies. Avoid in production: response bodies may contain PII.
   * </ul>
   *
   * @param level the desired logging verbosity
   * @see #loggingClient(HttpLoggingInterceptor.Level, HttpLoggingInterceptor.Logger)
   */
  public static OkHttpClient loggingClient(HttpLoggingInterceptor.Level level) {
    return loggingClient(level, HttpLoggingInterceptor.Logger.DEFAULT);
  }

  /**
   * Returns an OkHttpClient with HTTP-level request/response logging at the given level, writing to
   * a caller-supplied {@link HttpLoggingInterceptor.Logger}.
   *
   * <p>Use this overload to route OkHttp log lines into your own logging framework (SLF4J, Logback,
   * Log4j, Android Logcat, etc.):
   *
   * <pre>{@code
   * // SLF4J example
   * Logger slf4j = LoggerFactory.getLogger(AlpacaHttpConfig.class);
   * var client = AlpacaHttpConfig.loggingClient(Level.BASIC, slf4j::debug);
   * }</pre>
   *
   * <p>The same header-redaction rules as {@link #loggingClient(HttpLoggingInterceptor.Level)}
   * apply.
   *
   * @param level the desired logging verbosity
   * @param logger destination for log lines produced by the interceptor
   */
  public static OkHttpClient loggingClient(
      HttpLoggingInterceptor.Level level, HttpLoggingInterceptor.Logger logger) {
    var logging = new HttpLoggingInterceptor(logger);
    logging.setLevel(level);
    logging.redactHeader("APCA-API-KEY-ID");
    logging.redactHeader("APCA-API-SECRET-KEY");
    logging.redactHeader("Authorization");
    return defaultBuilder().addInterceptor(logging).build();
  }
}
