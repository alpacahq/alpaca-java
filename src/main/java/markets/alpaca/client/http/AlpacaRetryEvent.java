package markets.alpaca.client.http;

import java.time.Duration;
import okhttp3.HttpUrl;

/** Describes one retry decision made by {@link AlpacaRetryInterceptor}. */
public record AlpacaRetryEvent(
    String method, HttpUrl url, int statusCode, int attempt, int maxAttempts, Duration delay) {}
