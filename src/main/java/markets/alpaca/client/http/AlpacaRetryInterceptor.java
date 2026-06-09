package markets.alpaca.client.http;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * OkHttp interceptor that retries idempotent REST responses according to an {@link
 * AlpacaRetryPolicy}.
 */
public final class AlpacaRetryInterceptor implements Interceptor {

  private final AlpacaRetryPolicy policy;

  private AlpacaRetryInterceptor(AlpacaRetryPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
  }

  /** Creates an interceptor with {@link AlpacaRetryPolicy#defaultPolicy()}. */
  public static AlpacaRetryInterceptor create() {
    return create(AlpacaRetryPolicy.defaultPolicy());
  }

  /** Creates an interceptor with the supplied policy. */
  public static AlpacaRetryInterceptor create(AlpacaRetryPolicy policy) {
    return new AlpacaRetryInterceptor(policy);
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    var request = chain.request();
    var response = chain.proceed(request);

    if (!policy.isRetryableMethod(request.method())) {
      return response;
    }

    int attempt = 1;
    while (policy.isRetryableStatusCode(response.code())) {
      if (attempt >= policy.maxAttempts()) {
        policy.listener().onGiveUp(event(response, attempt, Duration.ZERO));
        return response;
      }

      Duration delay = retryDelay(response, attempt);
      policy.listener().onRetry(event(response, attempt, delay));
      response.close();
      policy.sleep(delay);
      attempt++;
      response = chain.proceed(request);
    }

    return response;
  }

  private AlpacaRetryEvent event(Response response, int attempt, Duration delay) {
    var request = response.request();
    return new AlpacaRetryEvent(
        request.method(), request.url(), response.code(), attempt, policy.maxAttempts(), delay);
  }

  private Duration retryDelay(Response response, int attempt) {
    var retryAfter = retryAfterDelay(response.header("Retry-After"));
    if (retryAfter != null) return retryAfter;
    return jitteredBackoff(attempt);
  }

  private Duration retryAfterDelay(String retryAfter) {
    if (retryAfter == null || retryAfter.isBlank()) return null;
    String value = retryAfter.trim();
    try {
      long seconds = Long.parseLong(value);
      return Duration.ofSeconds(Math.max(0, seconds));
    } catch (NumberFormatException ignored) {
      // Retry-After also permits an HTTP-date.
    }

    try {
      var retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      var delay = Duration.between(policy.clock().instant(), retryAt);
      return delay.isNegative() ? Duration.ZERO : delay;
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private Duration jitteredBackoff(int attempt) {
    Duration delay = cappedBackoff(attempt);
    if (delay.isZero() || policy.jitterRatio() == 0) return delay;

    double factor =
        ThreadLocalRandom.current().nextDouble(1 - policy.jitterRatio(), 1 + policy.jitterRatio());
    Duration jittered = Duration.ofNanos((long) (delay.toNanos() * factor));
    if (jittered.compareTo(policy.maxDelay()) > 0) return policy.maxDelay();
    return jittered;
  }

  private Duration cappedBackoff(int attempt) {
    Duration delay = policy.initialDelay();
    for (int i = 1; i < attempt; i++) {
      if (delay.compareTo(policy.maxDelay().dividedBy(2)) >= 0) return policy.maxDelay();
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : delay;
  }
}
