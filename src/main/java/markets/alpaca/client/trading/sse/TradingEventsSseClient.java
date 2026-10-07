package markets.alpaca.client.trading.sse;

import java.util.Objects;
import java.util.concurrent.Executor;
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.trading.api.EventsApi;
import markets.alpaca.client.openapi.trading.http.ApiException;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseProtocolException;
import markets.alpaca.client.sse.AlpacaSseSubscription;
import markets.alpaca.client.sse.internal.SseTransport;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Streaming client for Trading account-activity Server-Sent Events. */
public final class TradingEventsSseClient {

  private static final Executor DIRECT_EXECUTOR = Runnable::run;

  private final EventsApi eventsApi;
  private final OkHttpClient httpClient;
  private final AlpacaSseOptions options;
  private final Executor callbackExecutor;
  private final TradingActivityEventDecoder decoder;

  public TradingEventsSseClient(markets.alpaca.client.openapi.trading.http.ApiClient apiClient) {
    this(apiClient, AlpacaSseOptions.defaults(), DIRECT_EXECUTOR);
  }

  /** Creates a Trading SSE client with explicit transport options and direct callbacks. */
  public TradingEventsSseClient(
      markets.alpaca.client.openapi.trading.http.ApiClient apiClient, AlpacaSseOptions options) {
    this(apiClient, options, DIRECT_EXECUTOR);
  }

  /** Creates a Trading SSE client with resilient defaults and a callback executor. */
  public TradingEventsSseClient(
      markets.alpaca.client.openapi.trading.http.ApiClient apiClient, Executor callbackExecutor) {
    this(apiClient, AlpacaSseOptions.defaults(), callbackExecutor);
  }

  public TradingEventsSseClient(
      markets.alpaca.client.openapi.trading.http.ApiClient apiClient,
      AlpacaSseOptions options,
      Executor callbackExecutor) {
    Objects.requireNonNull(apiClient, "apiClient must not be null");
    this.eventsApi = new EventsApi(apiClient);
    this.httpClient = AlpacaHttpConfig.withAgentInformation(apiClient.getHttpClient());
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.callbackExecutor =
        Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
    this.decoder = new TradingActivityEventDecoder();
  }

  /** Opens a live activity stream without requesting historical events. */
  public AlpacaSseSubscription subscribeToActivities(AlpacaSseListener<ActivityEventV2> listener)
      throws ApiException {
    return subscribeToActivities(TradingActivitySseRequest.live(), listener);
  }

  /**
   * Opens an activity stream with the supplied history/boundary cursors.
   *
   * <p>An initial event ID in the client options cannot be combined with a date-bounded request.
   * For an ID-bounded request, that initial ID must not be after the request's upper bound.
   */
  public AlpacaSseSubscription subscribeToActivities(
      TradingActivitySseRequest request, AlpacaSseListener<ActivityEventV2> listener)
      throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    String initialEventId = options.initialLastEventId();
    if (initialEventId != null) {
      TradingActivitySseRequest.validateEventId(initialEventId, "options.initialLastEventId");
      if (request.until() != null) {
        throw new IllegalArgumentException(
            "options.initialLastEventId cannot be combined with a date-bounded Trading activity "
                + "request; use an ID-bounded request");
      }
      if (request.untilId() != null && initialEventId.compareTo(request.untilId()) > 0) {
        throw new IllegalArgumentException(
            "options.initialLastEventId must not be after request.untilId");
      }
    }
    return SseTransport.open(
        httpClient,
        eventsApi
            .subscribeToActivitiesSSECall(
                request.since(), request.until(), request.sinceId(), request.untilId(), null)
            .request(),
        options,
        request.bounded(),
        decoder::decode,
        listener,
        callbackExecutor,
        TradingEventsSseClient::withResumeCursor);
  }

  static Request withResumeCursor(Request request, String eventId) {
    Request resumedRequest = SseTransport.withLastEventIdHeader(request, eventId);
    if (eventId == null) {
      return resumedRequest;
    }
    boolean boundedByDate =
        request.url().queryParameter("until") != null
            && request.url().queryParameter("until_id") == null;
    if (boundedByDate) {
      return resumedRequest;
    }
    boolean boundedById = request.url().queryParameter("until_id") != null;
    if (boundedById && eventId.isEmpty()) {
      throw new AlpacaSseProtocolException(
          "Cannot resume an ID-bounded Trading activity stream after an empty SSE id");
    }

    var urlBuilder =
        request
            .url()
            .newBuilder()
            .removeAllQueryParameters("since")
            .removeAllQueryParameters("since_id");
    if (!eventId.isEmpty()) {
      urlBuilder.setQueryParameter("since_id", eventId);
    }
    var url = urlBuilder.build();
    return resumedRequest.newBuilder().url(url).build();
  }
}
