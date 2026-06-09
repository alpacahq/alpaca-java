package markets.alpaca.client;

import java.util.List;
import java.util.Objects;
import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
import markets.alpaca.client.data.AlpacaStocks;
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.data.http.ApiException;
import markets.alpaca.client.openapi.data.model.StockTradesResp;
import markets.alpaca.client.openapi.data.model.StockTradesRespSingle;
import markets.alpaca.client.openapi.trading.model.Order;
import markets.alpaca.client.trading.AlpacaOrders;
import markets.alpaca.client.trading.ListOrdersRequest;
import okhttp3.OkHttpClient;

/**
 * Immutable top-level facade for common Alpaca workflows.
 *
 * <p>The generated REST {@code ApiClient} classes are mutable. This client owns configured
 * generated clients internally and exposes narrower workflow facades for common calls. When an
 * unsupported generated endpoint is needed, call one of the {@code new*Client()} methods to get a
 * fresh mutable generated client that can be customized without mutating this client.
 */
public final class AlpacaClient {

  private final AlpacaCredentials tradingCredentials;
  private final AlpacaCredentials dataCredentials;
  private final AlpacaCredentials brokerCredentials;
  private final TradingApiEnvironment tradingEnvironment;
  private final BrokerApiEnvironment brokerEnvironment;
  private final String tradingBaseUrl;
  private final String dataBaseUrl;
  private final String brokerBaseUrl;
  private final OkHttpClient tradingHttpClient;
  private final OkHttpClient dataHttpClient;
  private final OkHttpClient brokerHttpClient;
  private final markets.alpaca.client.openapi.trading.http.ApiClient tradingClient;
  private final markets.alpaca.client.openapi.data.http.ApiClient dataClient;
  private final markets.alpaca.client.openapi.broker.http.ApiClient brokerClient;
  private final Orders orders;
  private final Stocks stocks;
  private final BrokerEventsSseClient brokerEventsSseClient;

  private AlpacaClient(Builder builder) {
    this.tradingCredentials =
        builder.tradingCredentials != null ? builder.tradingCredentials : builder.credentials;
    this.dataCredentials =
        builder.dataCredentials != null ? builder.dataCredentials : builder.credentials;
    this.brokerCredentials =
        builder.brokerCredentials != null ? builder.brokerCredentials : builder.credentials;
    this.tradingEnvironment = builder.tradingEnvironment;
    this.brokerEnvironment = builder.brokerEnvironment;
    this.tradingBaseUrl =
        builder.tradingBaseUrl != null ? builder.tradingBaseUrl : tradingEnvironment.baseUrl();
    this.dataBaseUrl = builder.dataBaseUrl;
    this.brokerBaseUrl =
        builder.brokerBaseUrl != null ? builder.brokerBaseUrl : brokerEnvironment.baseUrl();
    this.tradingHttpClient = builder.tradingHttpClient;
    this.dataHttpClient = builder.dataHttpClient;
    this.brokerHttpClient = builder.brokerHttpClient;

    this.tradingClient =
        AlpacaClientFactory.tradingClient(tradingCredentials, tradingBaseUrl, tradingHttpClient);
    this.dataClient =
        dataBaseUrl == null
            ? AlpacaClientFactory.dataClient(dataCredentials, dataHttpClient)
            : AlpacaClientFactory.dataClient(dataCredentials, dataBaseUrl, dataHttpClient);
    this.brokerClient =
        AlpacaClientFactory.brokerClient(brokerCredentials, brokerBaseUrl, brokerHttpClient);

    this.orders = new Orders(new AlpacaOrders(tradingClient));
    this.stocks = new Stocks(new AlpacaStocks(dataClient));
    this.brokerEventsSseClient = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
  }

  /**
   * Creates a client builder.
   *
   * <p>The supplied credentials are used for Trading, Market Data, and Broker APIs unless
   * overridden with {@link Builder#tradingCredentials(AlpacaCredentials)}, {@link
   * Builder#dataCredentials(AlpacaCredentials)}, or {@link
   * Builder#brokerCredentials(AlpacaCredentials)}.
   */
  public static Builder builder(AlpacaCredentials credentials) {
    return new Builder(credentials);
  }

  /** Returns common Trading order workflows without exposing the mutable generated client. */
  public Orders orders() {
    return orders;
  }

  /** Returns common Market Data stock workflows without exposing the mutable generated client. */
  public Stocks stocks() {
    return stocks;
  }

  /** Returns the Broker Events SSE client configured for this client. */
  public BrokerEventsSseClient brokerEventsSseClient() {
    return brokerEventsSseClient;
  }

  /**
   * Returns a fresh mutable generated Trading client for endpoints not covered by this facade.
   *
   * <p>Mutating the returned client does not affect this client or future calls to this method.
   */
  public markets.alpaca.client.openapi.trading.http.ApiClient newTradingClient() {
    return AlpacaClientFactory.tradingClient(tradingCredentials, tradingBaseUrl, tradingHttpClient);
  }

  /**
   * Returns a fresh mutable generated Market Data client for endpoints not covered by this facade.
   *
   * <p>Mutating the returned client does not affect this client or future calls to this method.
   */
  public markets.alpaca.client.openapi.data.http.ApiClient newDataClient() {
    return dataBaseUrl == null
        ? AlpacaClientFactory.dataClient(dataCredentials, dataHttpClient)
        : AlpacaClientFactory.dataClient(dataCredentials, dataBaseUrl, dataHttpClient);
  }

  /**
   * Returns a fresh mutable generated Broker client for endpoints not covered by this facade.
   *
   * <p>Mutating the returned client does not affect this client or future calls to this method.
   */
  public markets.alpaca.client.openapi.broker.http.ApiClient newBrokerClient() {
    return AlpacaClientFactory.brokerClient(brokerCredentials, brokerBaseUrl, brokerHttpClient);
  }

  /** Builder for immutable {@link AlpacaClient} instances. */
  public static final class Builder {
    private final AlpacaCredentials credentials;
    private AlpacaCredentials tradingCredentials;
    private AlpacaCredentials dataCredentials;
    private AlpacaCredentials brokerCredentials;
    private TradingApiEnvironment tradingEnvironment = TradingApiEnvironment.PAPER;
    private BrokerApiEnvironment brokerEnvironment = BrokerApiEnvironment.SANDBOX;
    private String tradingBaseUrl;
    private String dataBaseUrl;
    private String brokerBaseUrl;
    private OkHttpClient tradingHttpClient = AlpacaHttpConfig.defaultClient();
    private OkHttpClient dataHttpClient = AlpacaHttpConfig.defaultClient();
    private OkHttpClient brokerHttpClient = AlpacaHttpConfig.defaultClient();

    private Builder(AlpacaCredentials credentials) {
      this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    }

    /** Overrides the credentials used by Trading REST workflows. */
    public Builder tradingCredentials(AlpacaCredentials tradingCredentials) {
      this.tradingCredentials =
          Objects.requireNonNull(tradingCredentials, "tradingCredentials must not be null");
      return this;
    }

    /** Overrides the credentials used by Market Data REST workflows. */
    public Builder dataCredentials(AlpacaCredentials dataCredentials) {
      this.dataCredentials =
          Objects.requireNonNull(dataCredentials, "dataCredentials must not be null");
      return this;
    }

    /** Overrides the credentials used by Broker REST and SSE workflows. */
    public Builder brokerCredentials(AlpacaCredentials brokerCredentials) {
      this.brokerCredentials =
          Objects.requireNonNull(brokerCredentials, "brokerCredentials must not be null");
      return this;
    }

    /** Sets the Trading API environment. Defaults to paper trading. */
    public Builder tradingEnvironment(TradingApiEnvironment tradingEnvironment) {
      this.tradingEnvironment =
          Objects.requireNonNull(tradingEnvironment, "tradingEnvironment must not be null");
      return this;
    }

    /** Sets the Broker API environment. Defaults to sandbox. */
    public Builder brokerEnvironment(BrokerApiEnvironment brokerEnvironment) {
      this.brokerEnvironment =
          Objects.requireNonNull(brokerEnvironment, "brokerEnvironment must not be null");
      return this;
    }

    /** Overrides the Trading REST API base URL. Defaults to the selected Trading environment. */
    public Builder tradingBaseUrl(String tradingBaseUrl) {
      this.tradingBaseUrl = normalizeBaseUrl(tradingBaseUrl, "tradingBaseUrl");
      return this;
    }

    /** Overrides the Market Data REST API base URL. Defaults to the generated client default. */
    public Builder dataBaseUrl(String dataBaseUrl) {
      this.dataBaseUrl = normalizeBaseUrl(dataBaseUrl, "dataBaseUrl");
      return this;
    }

    /** Overrides the Broker REST API base URL. Defaults to the selected Broker environment. */
    public Builder brokerBaseUrl(String brokerBaseUrl) {
      this.brokerBaseUrl = normalizeBaseUrl(brokerBaseUrl, "brokerBaseUrl");
      return this;
    }

    /** Uses the same HTTP client for Trading, Market Data, and Broker workloads. */
    public Builder httpClient(OkHttpClient httpClient) {
      var snapshot = copyHttpClient(httpClient, "httpClient");
      this.tradingHttpClient = snapshot;
      this.dataHttpClient = snapshot;
      this.brokerHttpClient = snapshot;
      return this;
    }

    /** Sets the HTTP client used by Trading REST calls. */
    public Builder tradingHttpClient(OkHttpClient tradingHttpClient) {
      this.tradingHttpClient = copyHttpClient(tradingHttpClient, "tradingHttpClient");
      return this;
    }

    /** Sets the HTTP client used by Market Data REST calls. */
    public Builder dataHttpClient(OkHttpClient dataHttpClient) {
      this.dataHttpClient = copyHttpClient(dataHttpClient, "dataHttpClient");
      return this;
    }

    /** Sets the HTTP client used by Broker REST and SSE calls. */
    public Builder brokerHttpClient(OkHttpClient brokerHttpClient) {
      this.brokerHttpClient = copyHttpClient(brokerHttpClient, "brokerHttpClient");
      return this;
    }

    /**
     * Builds an immutable client facade using the configured credentials, environments, base URLs,
     * and HTTP clients.
     *
     * <p>The builder copies supplied {@link OkHttpClient} instances with {@code newBuilder()} so
     * later mutations by the caller cannot change this client.
     */
    public AlpacaClient build() {
      return new AlpacaClient(this);
    }

    private static OkHttpClient copyHttpClient(OkHttpClient httpClient, String name) {
      return Objects.requireNonNull(httpClient, name + " must not be null").newBuilder().build();
    }

    private static String normalizeBaseUrl(String baseUrl, String name) {
      Objects.requireNonNull(baseUrl, name + " must not be null");
      String trimmed = baseUrl.trim();
      if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
      while (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
      return trimmed;
    }
  }

  /** Safe order workflows exposed by {@link AlpacaClient}. */
  public static final class Orders {
    private final AlpacaOrders delegate;

    Orders(AlpacaOrders delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Lists orders through the Trading API {@code GET /v2/orders} endpoint.
     *
     * <p>Use {@link ListOrdersRequest} for named parameters and validation around mutually
     * exclusive pagination modes. For endpoint-specific response fields, see the Trading OpenAPI
     * spec used by this build.
     */
    public List<Order> list(ListOrdersRequest request)
        throws markets.alpaca.client.openapi.trading.http.ApiException {
      return delegate.list(request);
    }

    /**
     * Lists orders and returns the HTTP status code and headers alongside the deserialized orders.
     */
    public markets.alpaca.client.openapi.trading.http.ApiResponse<List<Order>> listWithHttpInfo(
        ListOrdersRequest request) throws markets.alpaca.client.openapi.trading.http.ApiException {
      return delegate.listWithHttpInfo(request);
    }
  }

  /** Safe stock-market-data workflows exposed by {@link AlpacaClient}. */
  public static final class Stocks {
    private final AlpacaStocks delegate;

    Stocks(AlpacaStocks delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Returns historical stock trades through the Market Data multi-symbol stock trades endpoint.
     */
    public StockTradesResp trades(StockTradesRequest request) throws ApiException {
      return delegate.trades(request);
    }

    /**
     * Returns historical stock trades with HTTP status code, response headers, and pagination
     * metadata from the generated response wrapper.
     */
    public markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesResp> tradesWithHttpInfo(
        StockTradesRequest request) throws ApiException {
      return delegate.tradesWithHttpInfo(request);
    }

    /**
     * Returns historical stock trades through the generated single-symbol endpoint.
     *
     * <p>The request must contain exactly one symbol; use {@link #trades(StockTradesRequest)} for
     * multi-symbol queries.
     */
    public StockTradesRespSingle tradesForSymbol(StockTradesRequest request) throws ApiException {
      return delegate.tradesForSymbol(request);
    }

    /** Returns single-symbol historical stock trades with HTTP status code and response headers. */
    public markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesRespSingle>
        tradesForSymbolWithHttpInfo(StockTradesRequest request) throws ApiException {
      return delegate.tradesForSymbolWithHttpInfo(request);
    }
  }
}
