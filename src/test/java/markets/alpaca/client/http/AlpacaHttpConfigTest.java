package markets.alpaca.client.http;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class AlpacaHttpConfigTest {

  // -------------------------------------------------------------------------
  // defaultClient() — singleton
  // -------------------------------------------------------------------------

  @Test
  void defaultClient_returnsSameSingletonOnEveryCall() {
    assertSame(AlpacaHttpConfig.defaultClient(), AlpacaHttpConfig.defaultClient());
  }

  @Test
  void defaultClient_isNotNull() {
    assertNotNull(AlpacaHttpConfig.defaultClient());
  }

  // -------------------------------------------------------------------------
  // defaultClient() — timeout values match the published constants
  // -------------------------------------------------------------------------

  @Test
  void defaultClient_hasExpectedConnectTimeout() {
    assertEquals(
        (int) AlpacaHttpConfig.DEFAULT_CONNECT_TIMEOUT.toMillis(),
        AlpacaHttpConfig.defaultClient().connectTimeoutMillis());
  }

  @Test
  void defaultClient_hasExpectedReadTimeout() {
    assertEquals(
        (int) AlpacaHttpConfig.DEFAULT_READ_TIMEOUT.toMillis(),
        AlpacaHttpConfig.defaultClient().readTimeoutMillis());
  }

  @Test
  void defaultClient_hasExpectedWriteTimeout() {
    assertEquals(
        (int) AlpacaHttpConfig.DEFAULT_WRITE_TIMEOUT.toMillis(),
        AlpacaHttpConfig.defaultClient().writeTimeoutMillis());
  }

  // -------------------------------------------------------------------------
  // defaultBuilder() — returns a fresh builder each call
  // -------------------------------------------------------------------------

  @Test
  void defaultBuilder_returnsNewInstanceOnEveryCall() {
    assertNotSame(AlpacaHttpConfig.defaultBuilder(), AlpacaHttpConfig.defaultBuilder());
  }

  @Test
  void defaultBuilder_producesClientWithExpectedConnectTimeout() {
    OkHttpClient client = AlpacaHttpConfig.defaultBuilder().build();
    assertEquals(
        (int) AlpacaHttpConfig.DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
  }

  @Test
  void defaultClient_sendsSdkNameAndVersionAsUserAgent() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse());
      var request = new Request.Builder().url(server.url("/")).build();

      AlpacaHttpConfig.defaultClient().newCall(request).execute().close();

      String userAgent = server.takeRequest().getHeader("User-Agent");
      assertNotNull(userAgent);
      assertTrue(userAgent.startsWith("APCA-JAVA/"));
      assertTrue(userAgent.endsWith(" Java/" + Runtime.version()));
      assertFalse(userAgent.contains("APCA-JAVA/unknown "));
      assertFalse(userAgent.contains("${version}"));
    }
  }

  @Test
  void withAgentInformation_decoratesCustomClientAndOverridesExistingUserAgent() throws Exception {
    var customClient = new OkHttpClient();
    var client = AlpacaHttpConfig.withAgentInformation(customClient);

    assertNotSame(customClient, client);
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse());
      var request =
          new Request.Builder().url(server.url("/")).header("User-Agent", "custom-agent").build();

      client.newCall(request).execute().close();

      String userAgent = server.takeRequest().getHeader("User-Agent");
      assertNotNull(userAgent);
      assertTrue(userAgent.startsWith("APCA-JAVA/"));
    }
  }

  @Test
  void withAgentInformation_returnsAlreadyConfiguredClient() {
    var client = AlpacaHttpConfig.defaultBuilder().build();

    assertSame(client, AlpacaHttpConfig.withAgentInformation(client));
  }

  // -------------------------------------------------------------------------
  // loggingClient() — returns a new instance with an HttpLoggingInterceptor
  // -------------------------------------------------------------------------

  @Test
  void loggingClient_returnsNewInstanceOnEveryCall() {
    assertNotSame(
        AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.BASIC),
        AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.BASIC));
  }

  @Test
  void loggingClient_hasHttpLoggingInterceptor() {
    var client = AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.BASIC);
    boolean found =
        client.interceptors().stream().anyMatch(i -> i instanceof HttpLoggingInterceptor);
    assertTrue(found, "loggingClient must register an HttpLoggingInterceptor");
  }

  @Test
  void loggingClient_isDistinctFromDefaultSingleton() {
    assertNotSame(
        AlpacaHttpConfig.defaultClient(),
        AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.BASIC));
  }

  // -------------------------------------------------------------------------
  // loggingClient() — header redaction
  // -------------------------------------------------------------------------

  @Test
  void loggingClient_redactsApiAuthHeaders() throws Exception {
    var logLines = new ArrayList<String>();
    var client =
        AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.HEADERS, logLines::add);

    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse());
      var request =
          new Request.Builder()
              .url(server.url("/"))
              .header("APCA-API-KEY-ID", "real-key-id-value")
              .header("APCA-API-SECRET-KEY", "real-secret-key-value")
              .header("Authorization", "Basic real-broker-basic-token")
              .build();
      client.newCall(request).execute().close();
    }

    var allLogs = String.join("\n", logLines);

    assertFalse(
        allLogs.contains("real-key-id-value"), "APCA-API-KEY-ID value must not appear in logs");
    assertFalse(
        allLogs.contains("real-secret-key-value"),
        "APCA-API-SECRET-KEY value must not appear in logs");
    assertFalse(
        allLogs.contains("real-broker-basic-token"), "Authorization value must not appear in logs");

    assertTrue(
        allLogs.contains("APCA-API-KEY-ID: ██"),
        "APCA-API-KEY-ID must appear with the redaction marker");
    assertTrue(
        allLogs.contains("APCA-API-SECRET-KEY: ██"),
        "APCA-API-SECRET-KEY must appear with the redaction marker");
    assertTrue(
        allLogs.contains("Authorization: ██"),
        "Authorization must appear with the redaction marker");
  }
}
