package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.broker.api.EventsApi;
import markets.alpaca.client.ws.AlpacaCryptoStream;
import markets.alpaca.client.ws.AlpacaNewsStream;
import markets.alpaca.client.ws.AlpacaStockStream;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.AlpacaStreamReconnectPolicy;
import markets.alpaca.client.ws.AlpacaTradingStream;
import markets.alpaca.client.ws.CryptoStreamListener;
import markets.alpaca.client.ws.NewsStreamListener;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.TradingEnvironment;
import markets.alpaca.client.ws.TradingStreamListener;
import markets.alpaca.client.ws.internal.AbstractAlpacaStream;
import markets.alpaca.client.ws.internal.AbstractMarketDataStream;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AlpacaClientFactory}.
 *
 * <p>This class verifies that each factory method correctly wires the supplied {@link
 * AlpacaCredentials} into the generated {@code ApiClient}'s authentication scheme and honours the
 * optionally supplied {@link OkHttpClient}.
 *
 * <p><b>Compile-time note:</b> this file references generated classes under {@code
 * markets.alpaca.client.{broker,trading,data}.http}. Run {@code ./gradlew generateApis} before
 * compiling.
 */
class AlpacaClientFactoryTest {

  private static final AlpacaCredentials CREDS =
      new AlpacaCredentials("test-key-id", "test-secret-key");

  private static Object field(Object target, Class<?> declaringClass, String name) {
    try {
      Field field = declaringClass.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Unable to read " + declaringClass.getSimpleName() + "." + name, e);
    }
  }

  private static OkHttpClient streamHttpClient(AbstractAlpacaStream stream) {
    return (OkHttpClient) field(stream, AbstractAlpacaStream.class, "httpClient");
  }

  private static AlpacaStreamReconnectPolicy streamReconnectPolicy(AbstractAlpacaStream stream) {
    return (AlpacaStreamReconnectPolicy)
        field(stream, AbstractAlpacaStream.class, "reconnectPolicy");
  }

  private static Executor streamCallbackExecutor(AbstractAlpacaStream stream) {
    return (Executor) field(stream, AbstractAlpacaStream.class, "callbackExecutor");
  }

  private static AlpacaCredentials marketDataStreamCredentials(AbstractMarketDataStream stream) {
    return (AlpacaCredentials) field(stream, AbstractMarketDataStream.class, "credentials");
  }

  private static void assertDefaultReconnectPolicy(AlpacaStreamReconnectPolicy policy) {
    assertEquals(10, policy.maxAttempts());
    assertEquals(java.time.Duration.ofSeconds(1), policy.initialBackoff());
    assertEquals(java.time.Duration.ofSeconds(64), policy.maxBackoff());
  }

  // -------------------------------------------------------------------------
  // Broker API — HTTP Basic Auth
  // -------------------------------------------------------------------------

  @Test
  void brokerClient_setsUsernameFromKeyId() {
    var client = AlpacaClientFactory.brokerClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.broker.http.auth.HttpBasicAuth)
            client.getAuthentications().get("BasicAuth");
    assertNotNull(auth, "BasicAuth must be present in generated Broker ApiClient");
    assertEquals("test-key-id", auth.getUsername());
  }

  @Test
  void brokerClient_setsPasswordFromSecretKey() {
    var client = AlpacaClientFactory.brokerClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.broker.http.auth.HttpBasicAuth)
            client.getAuthentications().get("BasicAuth");
    assertNotNull(auth, "BasicAuth must be present in generated Broker ApiClient");
    assertEquals("test-secret-key", auth.getPassword());
  }

  @Test
  void brokerClient_usesProvidedHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void brokerClient_noArgOverload_usesDefaultSingleton() {
    var client = AlpacaClientFactory.brokerClient(CREDS);
    assertSame(AlpacaHttpConfig.defaultClient(), client.getHttpClient());
  }

  @Test
  void brokerClient_usesSandboxBasePathByDefault() {
    var client = AlpacaClientFactory.brokerClient(CREDS);
    assertEquals("https://broker-api.sandbox.alpaca.markets", client.getBasePath());
  }

  @Test
  void brokerClient_environmentOverload_usesRequestedBasePath() {
    var client = AlpacaClientFactory.brokerClient(CREDS, BrokerApiEnvironment.PRODUCTION);
    assertEquals("https://broker-api.alpaca.markets", client.getBasePath());
  }

  @Test
  void brokerClient_environmentAndHttpClientOverload_usesRequestedBasePathAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.brokerClient(CREDS, BrokerApiEnvironment.SANDBOX, httpClient);
    assertEquals("https://broker-api.sandbox.alpaca.markets", client.getBasePath());
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void brokerClient_baseUrlOverload_usesCustomBasePath() {
    var client = AlpacaClientFactory.brokerClient(CREDS, "https://broker-proxy.example");

    assertEquals("https://broker-proxy.example", client.getBasePath());
  }

  @Test
  void brokerClient_baseUrlAndHttpClientOverload_usesCustomBasePathAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client =
        AlpacaClientFactory.brokerClient(CREDS, "https://broker-proxy.example", httpClient);

    assertEquals("https://broker-proxy.example", client.getBasePath());
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void brokerEventsSseClient_factoryReturnsClient() {
    assertNotNull(AlpacaClientFactory.brokerEventsSseClient(CREDS));
  }

  @Test
  void brokerEventsSseClient_existingApiClientOverloadWrapsProvidedClient() {
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS);
    BrokerEventsSseClient sseClient = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
    EventsApi eventsApi = (EventsApi) field(sseClient, BrokerEventsSseClient.class, "eventsApi");

    assertSame(brokerClient, eventsApi.getApiClient());
  }

  @Test
  void brokerEventsSseClient_executorOverloadWiresCallbackExecutor() {
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS);
    Executor callbackExecutor = Runnable::run;

    BrokerEventsSseClient sseClient =
        AlpacaClientFactory.brokerEventsSseClient(brokerClient, callbackExecutor);

    assertSame(callbackExecutor, field(sseClient, BrokerEventsSseClient.class, "callbackExecutor"));
  }

  // -------------------------------------------------------------------------
  // Trading API — header key pair
  // -------------------------------------------------------------------------

  @Test
  void tradingClient_setsApiKeyHeader() {
    var client = AlpacaClientFactory.tradingClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.trading.http.auth.ApiKeyAuth)
            client.getAuthentications().get("API_Key");
    assertNotNull(auth, "API_Key auth must be present in generated Trading ApiClient");
    assertEquals("test-key-id", auth.getApiKey());
  }

  @Test
  void tradingClient_setsApiSecretHeader() {
    var client = AlpacaClientFactory.tradingClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.trading.http.auth.ApiKeyAuth)
            client.getAuthentications().get("API_Secret");
    assertNotNull(auth, "API_Secret auth must be present in generated Trading ApiClient");
    assertEquals("test-secret-key", auth.getApiKey());
  }

  @Test
  void tradingClient_usesProvidedHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.tradingClient(CREDS, httpClient);
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void tradingClient_noArgOverload_usesDefaultSingleton() {
    var client = AlpacaClientFactory.tradingClient(CREDS);
    assertSame(AlpacaHttpConfig.defaultClient(), client.getHttpClient());
  }

  @Test
  void tradingClient_usesGeneratedPaperTradingBasePathByDefault() {
    var client = AlpacaClientFactory.tradingClient(CREDS);
    assertEquals("https://paper-api.alpaca.markets", client.getBasePath());
  }

  @Test
  void tradingClient_environmentOverload_usesRequestedBasePath() {
    var client = AlpacaClientFactory.tradingClient(CREDS, TradingApiEnvironment.PRODUCTION);
    assertEquals("https://api.alpaca.markets", client.getBasePath());
  }

  @Test
  void tradingClient_environmentAndHttpClientOverload_usesRequestedBasePathAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.tradingClient(CREDS, TradingApiEnvironment.PAPER, httpClient);
    assertEquals("https://paper-api.alpaca.markets", client.getBasePath());
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void tradingClient_baseUrlOverload_usesCustomBasePath() {
    var client = AlpacaClientFactory.tradingClient(CREDS, "https://trading-proxy.example");

    assertEquals("https://trading-proxy.example", client.getBasePath());
  }

  @Test
  void tradingClient_baseUrlAndHttpClientOverload_usesCustomBasePathAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client =
        AlpacaClientFactory.tradingClient(CREDS, "https://trading-proxy.example", httpClient);

    assertEquals("https://trading-proxy.example", client.getBasePath());
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void applyTradingAuth_throwsWhenApiKeyEntryMissing() {
    assertThrows(
        IllegalStateException.class,
        () -> AlpacaClientFactory.applyTradingAuth(CREDS, new java.util.HashMap<>()));
  }

  @Test
  void applyTradingAuth_throwsWhenApiSecretEntryMissing() {
    var auths =
        new java.util.HashMap<
            String, markets.alpaca.client.openapi.trading.http.auth.Authentication>();
    auths.put(
        "API_Key",
        new markets.alpaca.client.openapi.trading.http.auth.ApiKeyAuth(
            "header", "APCA-API-KEY-ID"));
    assertThrows(
        IllegalStateException.class, () -> AlpacaClientFactory.applyTradingAuth(CREDS, auths));
  }

  @Test
  void orders_wrapsTradingClient() {
    var tradingClient = AlpacaClientFactory.tradingClient(CREDS);

    var orders = AlpacaClientFactory.orders(tradingClient);

    assertSame(tradingClient, orders.generatedApi().getApiClient());
  }

  // -------------------------------------------------------------------------
  // Data API — header key pair
  // -------------------------------------------------------------------------

  @Test
  void dataClient_setsApiKeyHeader() {
    var client = AlpacaClientFactory.dataClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.data.http.auth.ApiKeyAuth)
            client.getAuthentications().get("apiKey");
    assertNotNull(auth, "apiKey auth must be present in generated Data ApiClient");
    assertEquals("test-key-id", auth.getApiKey());
  }

  @Test
  void dataClient_setsApiSecretHeader() {
    var client = AlpacaClientFactory.dataClient(CREDS);
    var auth =
        (markets.alpaca.client.openapi.data.http.auth.ApiKeyAuth)
            client.getAuthentications().get("apiSecret");
    assertNotNull(auth, "apiSecret auth must be present in generated Data ApiClient");
    assertEquals("test-secret-key", auth.getApiKey());
  }

  @Test
  void dataClient_usesProvidedHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.dataClient(CREDS, httpClient);
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void dataClient_noArgOverload_usesDefaultSingleton() {
    var client = AlpacaClientFactory.dataClient(CREDS);
    assertSame(AlpacaHttpConfig.defaultClient(), client.getHttpClient());
  }

  @Test
  void dataClient_baseUrlOverload_usesCustomBasePath() {
    var client = AlpacaClientFactory.dataClient(CREDS, "https://data-proxy.example");

    assertEquals("https://data-proxy.example", client.getBasePath());
  }

  @Test
  void dataClient_baseUrlAndHttpClientOverload_usesCustomBasePathAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var client = AlpacaClientFactory.dataClient(CREDS, "https://data-proxy.example", httpClient);

    assertEquals("https://data-proxy.example", client.getBasePath());
    assertSame(httpClient, client.getHttpClient());
  }

  @Test
  void baseUrlOverloads_rejectBlankValues() {
    StockStreamListener listener = new StockStreamListener() {};

    assertThrows(
        IllegalArgumentException.class, () -> AlpacaClientFactory.tradingClient(CREDS, "///"));
    assertThrows(IllegalArgumentException.class, () -> AlpacaClientFactory.dataClient(CREDS, " "));
    assertThrows(IllegalArgumentException.class, () -> AlpacaClientFactory.brokerClient(CREDS, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaClientFactory.stockStream(CREDS, StockSource.IEX, "\t", listener));
  }

  @Test
  void applyDataAuth_throwsWhenApiKeyEntryMissing() {
    assertThrows(
        IllegalStateException.class,
        () -> AlpacaClientFactory.applyDataAuth(CREDS, new java.util.HashMap<>()));
  }

  @Test
  void applyDataAuth_throwsWhenApiSecretEntryMissing() {
    var auths =
        new java.util.HashMap<
            String, markets.alpaca.client.openapi.data.http.auth.Authentication>();
    auths.put(
        "apiKey",
        new markets.alpaca.client.openapi.data.http.auth.ApiKeyAuth("header", "APCA-API-KEY-ID"));
    assertThrows(
        IllegalStateException.class, () -> AlpacaClientFactory.applyDataAuth(CREDS, auths));
  }

  @Test
  void stocks_wrapsDataClient() {
    var dataClient = AlpacaClientFactory.dataClient(CREDS);

    var stocks = AlpacaClientFactory.stocks(dataClient);

    assertSame(dataClient, stocks.generatedApi().getApiClient());
  }

  // -------------------------------------------------------------------------
  // WebSocket stream clients
  // -------------------------------------------------------------------------

  @Test
  void stockStream_noArgOverload_usesDefaultSingletonAndWiresArguments() {
    StockStreamListener listener = new StockStreamListener() {};

    try (var stream =
        AlpacaClientFactory.stockStream(
            CREDS, StockSource.IEX, AlpacaStreamEnvironment.PRODUCTION, listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.alpaca.markets/v2/iex", field(stream, AlpacaStockStream.class, "url"));
      assertSame(listener, field(stream, AlpacaStockStream.class, "listener"));
    }
  }

  @Test
  void stockStream_customHttpClientOverload_usesProvidedHttpClientAndEnvironment() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    StockStreamListener listener = new StockStreamListener() {};

    try (var stream =
        AlpacaClientFactory.stockStream(
            CREDS, StockSource.SIP, AlpacaStreamEnvironment.SANDBOX, listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.sandbox.alpaca.markets/v2/sip",
          field(stream, AlpacaStockStream.class, "url"));
      assertSame(listener, field(stream, AlpacaStockStream.class, "listener"));
    }
  }

  @Test
  void stockStream_baseUrlOverload_usesCustomStreamBaseUrl() {
    StockStreamListener listener = new StockStreamListener() {};

    try (var stream =
        AlpacaClientFactory.stockStream(
            CREDS, StockSource.IEX, "wss://stream-proxy.example", listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v2/iex", field(stream, AlpacaStockStream.class, "url"));
    }
  }

  @Test
  void stockStream_baseUrlAndHttpClientOverload_usesCustomStreamBaseUrlAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    StockStreamListener listener = new StockStreamListener() {};

    try (var stream =
        AlpacaClientFactory.stockStream(
            CREDS, StockSource.SIP, "wss://stream-proxy.example/", listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v2/sip", field(stream, AlpacaStockStream.class, "url"));
    }
  }

  @Test
  void stockStream_policyAndExecutorOverloads_wireProvidedValues() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var reconnectPolicy = AlpacaStreamReconnectPolicy.disabled();
    Executor callbackExecutor = Runnable::run;
    StockStreamListener listener = new StockStreamListener() {};

    try (var policyOnly =
            AlpacaClientFactory.stockStream(
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                reconnectPolicy);
        var executorOnly =
            AlpacaClientFactory.stockStream(
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                callbackExecutor);
        var httpAndExecutor =
            AlpacaClientFactory.stockStream(
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                httpClient,
                callbackExecutor);
        var httpAndPolicy =
            AlpacaClientFactory.stockStream(
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                httpClient,
                reconnectPolicy);
        var allCustom =
            AlpacaClientFactory.stockStream(
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                httpClient,
                reconnectPolicy,
                callbackExecutor)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(policyOnly));
      assertSame(reconnectPolicy, streamReconnectPolicy(policyOnly));

      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(executorOnly));
      assertDefaultReconnectPolicy(streamReconnectPolicy(executorOnly));
      assertSame(callbackExecutor, streamCallbackExecutor(executorOnly));

      assertSame(httpClient, streamHttpClient(httpAndExecutor));
      assertDefaultReconnectPolicy(streamReconnectPolicy(httpAndExecutor));
      assertSame(callbackExecutor, streamCallbackExecutor(httpAndExecutor));

      assertSame(httpClient, streamHttpClient(httpAndPolicy));
      assertSame(reconnectPolicy, streamReconnectPolicy(httpAndPolicy));

      assertSame(httpClient, streamHttpClient(allCustom));
      assertSame(reconnectPolicy, streamReconnectPolicy(allCustom));
      assertSame(callbackExecutor, streamCallbackExecutor(allCustom));
    }
  }

  @Test
  void cryptoStream_noArgOverload_usesDefaultSingletonAndWiresArguments() {
    CryptoStreamListener listener = new CryptoStreamListener() {};

    try (var stream =
        AlpacaClientFactory.cryptoStream(CREDS, AlpacaStreamEnvironment.PRODUCTION, listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.alpaca.markets/v1beta3/crypto/us",
          field(stream, AlpacaCryptoStream.class, "url"));
      assertSame(listener, field(stream, AlpacaCryptoStream.class, "listener"));
    }
  }

  @Test
  void cryptoStream_customHttpClientOverload_usesProvidedHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    CryptoStreamListener listener = new CryptoStreamListener() {};

    try (var stream =
        AlpacaClientFactory.cryptoStream(
            CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.alpaca.markets/v1beta3/crypto/us",
          field(stream, AlpacaCryptoStream.class, "url"));
      assertSame(listener, field(stream, AlpacaCryptoStream.class, "listener"));
    }
  }

  @Test
  void cryptoStream_baseUrlOverload_usesCustomStreamBaseUrl() {
    CryptoStreamListener listener = new CryptoStreamListener() {};

    try (var stream =
        AlpacaClientFactory.cryptoStream(CREDS, "wss://stream-proxy.example", listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v1beta3/crypto/us",
          field(stream, AlpacaCryptoStream.class, "url"));
    }
  }

  @Test
  void cryptoStream_baseUrlAndHttpClientOverload_usesCustomStreamBaseUrlAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    CryptoStreamListener listener = new CryptoStreamListener() {};

    try (var stream =
        AlpacaClientFactory.cryptoStream(
            CREDS, "wss://stream-proxy.example/", listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v1beta3/crypto/us",
          field(stream, AlpacaCryptoStream.class, "url"));
    }
  }

  @Test
  void cryptoStream_rejectsSandboxEnvironment() {
    CryptoStreamListener listener = new CryptoStreamListener() {};

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AlpacaClientFactory.cryptoStream(CREDS, AlpacaStreamEnvironment.SANDBOX, listener));

    assertEquals(
        "Crypto stream is only available on AlpacaStreamEnvironment.PRODUCTION",
        thrown.getMessage());
  }

  @Test
  void cryptoStream_policyAndExecutorOverloads_wireProvidedValues() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var reconnectPolicy = AlpacaStreamReconnectPolicy.disabled();
    Executor callbackExecutor = Runnable::run;
    CryptoStreamListener listener = new CryptoStreamListener() {};

    try (var policyOnly =
            AlpacaClientFactory.cryptoStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, reconnectPolicy);
        var executorOnly =
            AlpacaClientFactory.cryptoStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, callbackExecutor);
        var httpAndExecutor =
            AlpacaClientFactory.cryptoStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient, callbackExecutor);
        var httpAndPolicy =
            AlpacaClientFactory.cryptoStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient, reconnectPolicy);
        var allCustom =
            AlpacaClientFactory.cryptoStream(
                CREDS,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                httpClient,
                reconnectPolicy,
                callbackExecutor)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(policyOnly));
      assertSame(reconnectPolicy, streamReconnectPolicy(policyOnly));

      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(executorOnly));
      assertDefaultReconnectPolicy(streamReconnectPolicy(executorOnly));
      assertSame(callbackExecutor, streamCallbackExecutor(executorOnly));

      assertSame(httpClient, streamHttpClient(httpAndExecutor));
      assertDefaultReconnectPolicy(streamReconnectPolicy(httpAndExecutor));
      assertSame(callbackExecutor, streamCallbackExecutor(httpAndExecutor));

      assertSame(httpClient, streamHttpClient(httpAndPolicy));
      assertSame(reconnectPolicy, streamReconnectPolicy(httpAndPolicy));

      assertSame(httpClient, streamHttpClient(allCustom));
      assertSame(reconnectPolicy, streamReconnectPolicy(allCustom));
      assertSame(callbackExecutor, streamCallbackExecutor(allCustom));
    }
  }

  @Test
  void newsStream_noArgOverload_usesDefaultSingletonAndWiresArguments() {
    NewsStreamListener listener = new NewsStreamListener() {};

    try (var stream =
        AlpacaClientFactory.newsStream(CREDS, AlpacaStreamEnvironment.PRODUCTION, listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.alpaca.markets/v1beta1/news",
          field(stream, AlpacaNewsStream.class, "url"));
      assertSame(listener, field(stream, AlpacaNewsStream.class, "listener"));
    }
  }

  @Test
  void newsStream_customHttpClientOverload_usesProvidedHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    NewsStreamListener listener = new NewsStreamListener() {};

    try (var stream =
        AlpacaClientFactory.newsStream(
            CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertSame(CREDS, marketDataStreamCredentials(stream));
      assertEquals(
          "wss://stream.data.alpaca.markets/v1beta1/news",
          field(stream, AlpacaNewsStream.class, "url"));
      assertSame(listener, field(stream, AlpacaNewsStream.class, "listener"));
    }
  }

  @Test
  void newsStream_baseUrlOverload_usesCustomStreamBaseUrl() {
    NewsStreamListener listener = new NewsStreamListener() {};

    try (var stream =
        AlpacaClientFactory.newsStream(CREDS, "wss://stream-proxy.example", listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v1beta1/news", field(stream, AlpacaNewsStream.class, "url"));
    }
  }

  @Test
  void newsStream_baseUrlAndHttpClientOverload_usesCustomStreamBaseUrlAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    NewsStreamListener listener = new NewsStreamListener() {};

    try (var stream =
        AlpacaClientFactory.newsStream(
            CREDS, "wss://stream-proxy.example/", listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertEquals(
          "wss://stream-proxy.example/v1beta1/news", field(stream, AlpacaNewsStream.class, "url"));
    }
  }

  @Test
  void newsStream_rejectsSandboxEnvironment() {
    NewsStreamListener listener = new NewsStreamListener() {};

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> AlpacaClientFactory.newsStream(CREDS, AlpacaStreamEnvironment.SANDBOX, listener));

    assertEquals(
        "News stream is only available on AlpacaStreamEnvironment.PRODUCTION", thrown.getMessage());
  }

  @Test
  void newsStream_policyAndExecutorOverloads_wireProvidedValues() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var reconnectPolicy = AlpacaStreamReconnectPolicy.disabled();
    Executor callbackExecutor = Runnable::run;
    NewsStreamListener listener = new NewsStreamListener() {};

    try (var policyOnly =
            AlpacaClientFactory.newsStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, reconnectPolicy);
        var executorOnly =
            AlpacaClientFactory.newsStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, callbackExecutor);
        var httpAndExecutor =
            AlpacaClientFactory.newsStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient, callbackExecutor);
        var httpAndPolicy =
            AlpacaClientFactory.newsStream(
                CREDS, AlpacaStreamEnvironment.PRODUCTION, listener, httpClient, reconnectPolicy);
        var allCustom =
            AlpacaClientFactory.newsStream(
                CREDS,
                AlpacaStreamEnvironment.PRODUCTION,
                listener,
                httpClient,
                reconnectPolicy,
                callbackExecutor)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(policyOnly));
      assertSame(reconnectPolicy, streamReconnectPolicy(policyOnly));

      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(executorOnly));
      assertDefaultReconnectPolicy(streamReconnectPolicy(executorOnly));
      assertSame(callbackExecutor, streamCallbackExecutor(executorOnly));

      assertSame(httpClient, streamHttpClient(httpAndExecutor));
      assertDefaultReconnectPolicy(streamReconnectPolicy(httpAndExecutor));
      assertSame(callbackExecutor, streamCallbackExecutor(httpAndExecutor));

      assertSame(httpClient, streamHttpClient(httpAndPolicy));
      assertSame(reconnectPolicy, streamReconnectPolicy(httpAndPolicy));

      assertSame(httpClient, streamHttpClient(allCustom));
      assertSame(reconnectPolicy, streamReconnectPolicy(allCustom));
      assertSame(callbackExecutor, streamCallbackExecutor(allCustom));
    }
  }

  @Test
  void tradingStream_noArgOverload_usesDefaultSingletonAndWiresArguments() {
    TradingStreamListener listener = new TradingStreamListener() {};

    try (var stream =
        AlpacaClientFactory.tradingStream(CREDS, TradingEnvironment.PAPER, listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertSame(CREDS, field(stream, AlpacaTradingStream.class, "credentials"));
      assertEquals(
          "wss://paper-api.alpaca.markets/stream", field(stream, AlpacaTradingStream.class, "url"));
      assertSame(listener, field(stream, AlpacaTradingStream.class, "listener"));
    }
  }

  @Test
  void tradingStream_customHttpClientOverload_usesProvidedHttpClientAndEnvironment() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    TradingStreamListener listener = new TradingStreamListener() {};

    try (var stream =
        AlpacaClientFactory.tradingStream(
            CREDS, TradingEnvironment.PRODUCTION, listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertSame(CREDS, field(stream, AlpacaTradingStream.class, "credentials"));
      assertEquals(
          "wss://api.alpaca.markets/stream", field(stream, AlpacaTradingStream.class, "url"));
      assertSame(listener, field(stream, AlpacaTradingStream.class, "listener"));
    }
  }

  @Test
  void tradingStream_baseUrlOverload_usesCustomStreamBaseUrl() {
    TradingStreamListener listener = new TradingStreamListener() {};

    try (var stream =
        AlpacaClientFactory.tradingStream(CREDS, "wss://trading-proxy.example", listener)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(stream));
      assertEquals(
          "wss://trading-proxy.example/stream", field(stream, AlpacaTradingStream.class, "url"));
    }
  }

  @Test
  void tradingStream_baseUrlAndHttpClientOverload_usesCustomStreamBaseUrlAndHttpClient() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    TradingStreamListener listener = new TradingStreamListener() {};

    try (var stream =
        AlpacaClientFactory.tradingStream(
            CREDS, "wss://trading-proxy.example/", listener, httpClient)) {
      assertSame(httpClient, streamHttpClient(stream));
      assertEquals(
          "wss://trading-proxy.example/stream", field(stream, AlpacaTradingStream.class, "url"));
    }
  }

  @Test
  void tradingStream_policyAndExecutorOverloads_wireProvidedValues() {
    var httpClient = AlpacaHttpConfig.defaultBuilder().build();
    var reconnectPolicy = AlpacaStreamReconnectPolicy.disabled();
    Executor callbackExecutor = Runnable::run;
    TradingStreamListener listener = new TradingStreamListener() {};

    try (var policyOnly =
            AlpacaClientFactory.tradingStream(
                CREDS, TradingEnvironment.PAPER, listener, reconnectPolicy);
        var executorOnly =
            AlpacaClientFactory.tradingStream(
                CREDS, TradingEnvironment.PAPER, listener, callbackExecutor);
        var httpAndExecutor =
            AlpacaClientFactory.tradingStream(
                CREDS, TradingEnvironment.PAPER, listener, httpClient, callbackExecutor);
        var httpAndPolicy =
            AlpacaClientFactory.tradingStream(
                CREDS, TradingEnvironment.PAPER, listener, httpClient, reconnectPolicy);
        var allCustom =
            AlpacaClientFactory.tradingStream(
                CREDS,
                TradingEnvironment.PAPER,
                listener,
                httpClient,
                reconnectPolicy,
                callbackExecutor)) {
      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(policyOnly));
      assertSame(reconnectPolicy, streamReconnectPolicy(policyOnly));

      assertSame(AlpacaHttpConfig.defaultClient(), streamHttpClient(executorOnly));
      assertDefaultReconnectPolicy(streamReconnectPolicy(executorOnly));
      assertSame(callbackExecutor, streamCallbackExecutor(executorOnly));

      assertSame(httpClient, streamHttpClient(httpAndExecutor));
      assertDefaultReconnectPolicy(streamReconnectPolicy(httpAndExecutor));
      assertSame(callbackExecutor, streamCallbackExecutor(httpAndExecutor));

      assertSame(httpClient, streamHttpClient(httpAndPolicy));
      assertSame(reconnectPolicy, streamReconnectPolicy(httpAndPolicy));

      assertSame(httpClient, streamHttpClient(allCustom));
      assertSame(reconnectPolicy, streamReconnectPolicy(allCustom));
      assertSame(callbackExecutor, streamCallbackExecutor(allCustom));
    }
  }
}
