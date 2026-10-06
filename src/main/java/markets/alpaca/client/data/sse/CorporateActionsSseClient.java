package markets.alpaca.client.data.sse;

import java.util.Objects;
import java.util.concurrent.Executor;
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.data.api.CorporateActionsApi;
import markets.alpaca.client.openapi.data.http.ApiException;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseSubscription;
import markets.alpaca.client.sse.internal.SseTransport;
import okhttp3.OkHttpClient;

/** Streaming client for Market Data corporate-action Server-Sent Events. */
public final class CorporateActionsSseClient {

  private static final Executor DIRECT_EXECUTOR = Runnable::run;

  private final CorporateActionsApi corporateActionsApi;
  private final OkHttpClient httpClient;
  private final AlpacaSseOptions options;
  private final Executor callbackExecutor;
  private final CorporateActionEventDecoder decoder = new CorporateActionEventDecoder();

  /** Creates a resilient production corporate-actions SSE client. */
  public CorporateActionsSseClient(markets.alpaca.client.openapi.data.http.ApiClient apiClient) {
    this(apiClient, MarketDataSseEnvironment.PRODUCTION);
  }

  /** Creates a resilient corporate-actions SSE client for an environment. */
  public CorporateActionsSseClient(
      markets.alpaca.client.openapi.data.http.ApiClient apiClient,
      MarketDataSseEnvironment environment) {
    this(apiClient, environment, AlpacaSseOptions.defaults(), DIRECT_EXECUTOR);
  }

  /** Creates a corporate-actions SSE client with explicit options and direct callbacks. */
  public CorporateActionsSseClient(
      markets.alpaca.client.openapi.data.http.ApiClient apiClient,
      MarketDataSseEnvironment environment,
      AlpacaSseOptions options) {
    this(apiClient, environment, options, DIRECT_EXECUTOR);
  }

  /** Creates a resilient corporate-actions SSE client with a callback executor. */
  public CorporateActionsSseClient(
      markets.alpaca.client.openapi.data.http.ApiClient apiClient,
      MarketDataSseEnvironment environment,
      Executor callbackExecutor) {
    this(apiClient, environment, AlpacaSseOptions.defaults(), callbackExecutor);
  }

  /** Creates a corporate-actions SSE client with explicit transport and callback settings. */
  public CorporateActionsSseClient(
      markets.alpaca.client.openapi.data.http.ApiClient apiClient,
      MarketDataSseEnvironment environment,
      AlpacaSseOptions options,
      Executor callbackExecutor) {
    Objects.requireNonNull(apiClient, "apiClient must not be null");
    this.corporateActionsApi = new CorporateActionsApi(apiClient);
    this.corporateActionsApi.setCustomBaseUrl(
        Objects.requireNonNull(environment, "environment must not be null").baseUrl());
    this.httpClient = AlpacaHttpConfig.withAgentInformation(apiClient.getHttpClient());
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.callbackExecutor =
        Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
  }

  /** Opens a live stream without history or event filters. */
  public AlpacaSseSubscription subscribeToCorporateActions(
      AlpacaSseListener<CorporateActionEvent> listener) throws ApiException {
    return subscribeToCorporateActions(CorporateActionsSseRequest.live(), listener);
  }

  /** Opens a corporate-actions stream with filters and history/boundary cursors. */
  public AlpacaSseSubscription subscribeToCorporateActions(
      CorporateActionsSseRequest request, AlpacaSseListener<CorporateActionEvent> listener)
      throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    if (options.initialLastEventId() != null && !options.initialLastEventId().isEmpty()) {
      CorporateActionsSseRequest.validateEventId(
          options.initialLastEventId(), "options.initialLastEventId");
    }
    return SseTransport.open(
        httpClient,
        corporateActionsApi
            .subscribeToCorporateActionsEventsSSECall(
                request.eventTypes().isEmpty() ? null : request.eventTypes(),
                request.regionParameter(),
                request.since(),
                request.until(),
                request.sinceId(),
                request.untilId(),
                null,
                null)
            .request(),
        options,
        request.bounded(),
        decoder::decode,
        listener,
        callbackExecutor);
  }
}
