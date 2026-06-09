package markets.alpaca.client.http;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class AlpacaRetryInterceptorTest {

  @Test
  void retriesIdempotentRetryableResponses() throws Exception {
    var policy =
        AlpacaRetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ZERO)
            .jitterRatio(0)
            .build();
    var client = client(policy);

    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503));
      server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

      var response = client.newCall(get(server, "/account")).execute();

      assertEquals(200, response.code());
      assertEquals("ok", response.body().string());
      assertEquals(2, server.getRequestCount());
    }
  }

  @Test
  void refusesUnsafeMethodsByDefault() throws Exception {
    var policy =
        AlpacaRetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ZERO)
            .jitterRatio(0)
            .build();
    var client = client(policy);

    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503));
      server.enqueue(new MockResponse().setResponseCode(200).setBody("should-not-be-used"));

      var request =
          new Request.Builder()
              .url(server.url("/orders"))
              .post(okhttp3.RequestBody.create(new byte[0]))
              .build();
      var response = client.newCall(request).execute();

      assertEquals(503, response.code());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void honorsRetryAfterSecondsAndReportsRetryMetrics() throws Exception {
    var delays = new ArrayList<Duration>();
    var retries = new ArrayList<AlpacaRetryEvent>();
    var policy =
        AlpacaRetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(5))
            .jitterRatio(0)
            .listener(
                new AlpacaRetryListener() {
                  @Override
                  public void onRetry(AlpacaRetryEvent event) {
                    retries.add(event);
                  }
                })
            .sleeper(delays::add)
            .build();
    var client = client(policy);

    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "7"));
      server.enqueue(new MockResponse().setResponseCode(200));

      var response = client.newCall(get(server, "/v2/stocks/AAPL/bars")).execute();

      assertEquals(200, response.code());
      assertEquals(2, server.getRequestCount());
      assertEquals(List.of(Duration.ofSeconds(7)), delays);
      assertEquals(1, retries.size());
      assertEquals(1, retries.get(0).attempt());
      assertEquals(429, retries.get(0).statusCode());
      assertEquals(Duration.ofSeconds(7), retries.get(0).delay());
    }
  }

  @Test
  void reportsGiveUpWhenRetryBudgetIsExhausted() throws Exception {
    var giveUps = new ArrayList<AlpacaRetryEvent>();
    var policy =
        AlpacaRetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ZERO)
            .jitterRatio(0)
            .listener(
                new AlpacaRetryListener() {
                  @Override
                  public void onGiveUp(AlpacaRetryEvent event) {
                    giveUps.add(event);
                  }
                })
            .build();
    var client = client(policy);

    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503));
      server.enqueue(new MockResponse().setResponseCode(503));

      var response = client.newCall(get(server, "/v2/account")).execute();

      assertEquals(503, response.code());
      assertEquals(2, server.getRequestCount());
      assertEquals(1, giveUps.size());
      assertEquals(2, giveUps.get(0).attempt());
      assertEquals(503, giveUps.get(0).statusCode());
    }
  }

  @Test
  void httpConfigRetryingClientInstallsInterceptor() {
    var client = AlpacaHttpConfig.retryingClient(AlpacaRetryPolicy.defaultPolicy());

    assertTrue(
        client.interceptors().stream()
            .anyMatch(interceptor -> interceptor instanceof AlpacaRetryInterceptor));
  }

  private static OkHttpClient client(AlpacaRetryPolicy policy) {
    return AlpacaHttpConfig.defaultBuilder()
        .addInterceptor(AlpacaRetryInterceptor.create(policy))
        .build();
  }

  private static Request get(MockWebServer server, String path) {
    return new Request.Builder().url(server.url(path)).get().build();
  }
}
