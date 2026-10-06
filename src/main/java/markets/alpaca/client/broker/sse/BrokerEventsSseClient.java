package markets.alpaca.client.broker.sse;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.broker.api.AccountsApi;
import markets.alpaca.client.openapi.broker.api.EventsApi;
import markets.alpaca.client.openapi.broker.http.ApiException;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.AccountStatusEvent;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2;
import markets.alpaca.client.openapi.broker.model.IPOEvent;
import markets.alpaca.client.openapi.broker.model.JournalStatusEvent;
import markets.alpaca.client.openapi.broker.model.JournalStatusEventV2;
import markets.alpaca.client.openapi.broker.model.NonTradeActivityEvent;
import markets.alpaca.client.openapi.broker.model.StatusFundingEvent;
import markets.alpaca.client.openapi.broker.model.SubscribeToAdminActionSSE200ResponseInner;
import markets.alpaca.client.openapi.broker.model.SystemEventV2;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;
import markets.alpaca.client.sse.AlpacaSseDeserializationException;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseHttpException;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseProtocolException;
import markets.alpaca.client.sse.AlpacaSseReconnectPolicy;
import markets.alpaca.client.sse.AlpacaSseSubscription;
import markets.alpaca.client.sse.internal.SseDecodeFailurePolicy;
import markets.alpaca.client.sse.internal.SseDecoder;
import markets.alpaca.client.sse.internal.SseTransport;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Streaming wrapper for Broker Events SSE endpoints.
 *
 * <p>The generated {@link EventsApi} exposes SSE endpoints as ordinary blocking REST calls, which
 * can hang indefinitely for live streams. This wrapper reuses the generated request builders for
 * path, query parameters, and authentication, then opens them through the SDK's incremental OkHttp
 * transport.
 */
public final class BrokerEventsSseClient {

  private static final Executor DIRECT_CALLBACK_EXECUTOR = Runnable::run;

  private final EventsApi eventsApi;
  private final AccountsApi accountsApi;
  private final OkHttpClient httpClient;
  private final AlpacaSseOptions sseOptions;
  private final Executor callbackExecutor;

  /** Creates an SSE wrapper around a generated Broker {@link EventsApi}. */
  public BrokerEventsSseClient(EventsApi eventsApi) {
    this(eventsApi, AlpacaSseOptions.reconnectDisabled(), DIRECT_CALLBACK_EXECUTOR);
  }

  /** Creates an SSE wrapper with explicit transport options and direct callbacks. */
  public BrokerEventsSseClient(EventsApi eventsApi, AlpacaSseOptions sseOptions) {
    this(eventsApi, sseOptions, DIRECT_CALLBACK_EXECUTOR);
  }

  /** Creates an SSE wrapper around a generated Broker {@link EventsApi}. */
  public BrokerEventsSseClient(EventsApi eventsApi, Executor callbackExecutor) {
    this(eventsApi, AlpacaSseOptions.reconnectDisabled(), callbackExecutor);
  }

  /** Creates an SSE wrapper with explicit transport options and callback executor. */
  public BrokerEventsSseClient(
      EventsApi eventsApi, AlpacaSseOptions sseOptions, Executor callbackExecutor) {
    this.eventsApi = Objects.requireNonNull(eventsApi, "eventsApi must not be null");
    this.accountsApi = new AccountsApi(eventsApi.getApiClient());
    this.sseOptions = Objects.requireNonNull(sseOptions, "sseOptions must not be null");
    this.callbackExecutor =
        Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
    this.httpClient =
        AlpacaHttpConfig.withAgentInformation(eventsApi.getApiClient().getHttpClient())
            .newBuilder()
            .build();
  }

  /** Creates an SSE wrapper from a generated Broker {@code ApiClient}. */
  public BrokerEventsSseClient(markets.alpaca.client.openapi.broker.http.ApiClient apiClient) {
    this(new EventsApi(apiClient));
  }

  /** Creates an SSE wrapper from a generated Broker client with explicit transport options. */
  public BrokerEventsSseClient(
      markets.alpaca.client.openapi.broker.http.ApiClient apiClient, AlpacaSseOptions sseOptions) {
    this(new EventsApi(apiClient), sseOptions);
  }

  /** Creates an SSE wrapper from a generated Broker {@code ApiClient}. */
  public BrokerEventsSseClient(
      markets.alpaca.client.openapi.broker.http.ApiClient apiClient, Executor callbackExecutor) {
    this(new EventsApi(apiClient), callbackExecutor);
  }

  /** Creates an SSE wrapper from a generated Broker client with explicit transport settings. */
  public BrokerEventsSseClient(
      markets.alpaca.client.openapi.broker.http.ApiClient apiClient,
      AlpacaSseOptions sseOptions,
      Executor callbackExecutor) {
    this(new EventsApi(apiClient), sseOptions, callbackExecutor);
  }

  /**
   * Opens the Broker non-trading activities SSE stream.
   *
   * <p>The generated OpenAPI operation is {@code getV1EventsNta}. Use the options object for the
   * date/id cursor filters documented in the Broker OpenAPI spec.
   */
  public BrokerSseSubscription subscribeToNonTradingActivities(
      BrokerSseNonTradingActivitiesOptions options,
      BrokerSseEventListener<NonTradeActivityEvent> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .getV1EventsNtaCall(
                options.id(),
                options.since(),
                options.until(),
                options.sinceId(),
                options.untilId(),
                options.sinceUlid(),
                options.untilUlid(),
                options.includePreprocessing(),
                options.groupId(),
                null)
            .request(),
        NonTradeActivityEvent.class,
        listener);
  }

  /** Opens the Broker non-trading activities SSE stream with explicit filter parameters. */
  public BrokerSseSubscription subscribeToNonTradingActivities(
      String id,
      LocalDate since,
      LocalDate until,
      Integer sinceId,
      Integer untilId,
      String sinceUlid,
      String untilUlid,
      Boolean includePreprocessing,
      UUID groupId,
      BrokerSseEventListener<NonTradeActivityEvent> listener)
      throws ApiException {
    return subscribeToNonTradingActivities(
        new BrokerSseNonTradingActivitiesOptions(
            id,
            since,
            until,
            sinceId,
            untilId,
            sinceUlid,
            untilUlid,
            includePreprocessing,
            groupId),
        listener);
  }

  /** Opens the Broker activities SSE stream. */
  public BrokerSseSubscription subscribeToActivities(
      BrokerSseDateTimeOptions options, BrokerSseEventListener<ActivityEventV2> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToActivitiesSSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        new BrokerActivityEventDecoder()::decode,
        listener);
  }

  /** Opens the Broker activities SSE stream with explicit date/time cursor filters. */
  public BrokerSseSubscription subscribeToActivities(
      OffsetDateTime since,
      OffsetDateTime until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<ActivityEventV2> listener)
      throws ApiException {
    return subscribeToActivities(
        new BrokerSseDateTimeOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the Broker admin actions SSE stream. */
  public BrokerSseSubscription subscribeToAdminActions(
      BrokerSseDateTimeOptions options,
      BrokerSseEventListener<SubscribeToAdminActionSSE200ResponseInner> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToAdminActionSSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        SubscribeToAdminActionSSE200ResponseInner.class,
        listener);
  }

  /** Opens the Broker admin actions SSE stream with explicit date/time cursor filters. */
  public BrokerSseSubscription subscribeToAdminActions(
      OffsetDateTime since,
      OffsetDateTime until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<SubscribeToAdminActionSSE200ResponseInner> listener)
      throws ApiException {
    return subscribeToAdminActions(
        new BrokerSseDateTimeOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the Broker funding status SSE stream. */
  public BrokerSseSubscription subscribeToFundingStatus(
      BrokerSseDateOptions options, BrokerSseEventListener<StatusFundingEvent> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToFundingStatusSSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        StatusFundingEvent.class,
        listener);
  }

  /** Opens the Broker funding status SSE stream with explicit date cursor filters. */
  public BrokerSseSubscription subscribeToFundingStatus(
      LocalDate since,
      LocalDate until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<StatusFundingEvent> listener)
      throws ApiException {
    return subscribeToFundingStatus(
        new BrokerSseDateOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the Broker IPO events SSE stream. */
  public BrokerSseSubscription subscribeToIpoEvents(
      BrokerSseDateTimeOptions options, BrokerSseEventListener<IPOEvent> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToIPOEventsSSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        IPOEvent.class,
        listener);
  }

  /** Opens the Broker IPO events SSE stream with explicit date/time cursor filters. */
  public BrokerSseSubscription subscribeToIpoEvents(
      OffsetDateTime since,
      OffsetDateTime until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<IPOEvent> listener)
      throws ApiException {
    return subscribeToIpoEvents(
        new BrokerSseDateTimeOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the legacy Broker journal status SSE stream. */
  public BrokerSseSubscription subscribeToJournalStatusLegacy(
      BrokerSseIdentifiedLegacyDateOptions options,
      BrokerSseEventListener<JournalStatusEvent> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToJournalStatusSSECall(
                options.since(),
                options.until(),
                options.sinceId(),
                options.untilId(),
                options.sinceUlid(),
                options.untilUlid(),
                options.id(),
                null)
            .request(),
        JournalStatusEvent.class,
        listener);
  }

  /** Opens the legacy Broker journal status SSE stream with explicit legacy cursor filters. */
  public BrokerSseSubscription subscribeToJournalStatusLegacy(
      LocalDate since,
      LocalDate until,
      Integer sinceId,
      Integer untilId,
      String sinceUlid,
      String untilUlid,
      String id,
      BrokerSseEventListener<JournalStatusEvent> listener)
      throws ApiException {
    return subscribeToJournalStatusLegacy(
        new BrokerSseIdentifiedLegacyDateOptions(
            since, until, sinceId, untilId, sinceUlid, untilUlid, id),
        listener);
  }

  /** Opens the current Broker journal status SSE stream. */
  public BrokerSseSubscription subscribeToJournalStatus(
      BrokerSseIdentifiedDateTimeOptions options,
      BrokerSseEventListener<JournalStatusEventV2> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToJournalStatusV2SSECall(
                options.since(),
                options.until(),
                options.sinceId(),
                options.untilId(),
                options.id(),
                null)
            .request(),
        JournalStatusEventV2.class,
        listener);
  }

  /** Opens the current Broker journal status SSE stream with explicit date/time cursor filters. */
  public BrokerSseSubscription subscribeToJournalStatus(
      OffsetDateTime since,
      OffsetDateTime until,
      String sinceId,
      String untilId,
      String id,
      BrokerSseEventListener<JournalStatusEventV2> listener)
      throws ApiException {
    return subscribeToJournalStatus(
        new BrokerSseIdentifiedDateTimeOptions(since, until, sinceId, untilId, id), listener);
  }

  /** Opens the Broker system events SSE stream. */
  public BrokerSseSubscription subscribeToSystemEvents(
      BrokerSseDateTimeOptions options, BrokerSseEventListener<SystemEventV2> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToSystemEventV2SSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        SystemEventV2.class,
        listener);
  }

  /** Opens the Broker system events SSE stream with explicit date/time cursor filters. */
  public BrokerSseSubscription subscribeToSystemEvents(
      OffsetDateTime since,
      OffsetDateTime until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<SystemEventV2> listener)
      throws ApiException {
    return subscribeToSystemEvents(
        new BrokerSseDateTimeOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the Broker trade events SSE stream. */
  public BrokerSseSubscription subscribeToTradeEvents(
      BrokerSseDateOptions options, BrokerSseEventListener<TradeUpdateEventV2> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToTradeV2SSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        TradeUpdateEventV2.class,
        listener);
  }

  /** Opens the Broker trade events SSE stream with explicit date cursor filters. */
  public BrokerSseSubscription subscribeToTradeEvents(
      LocalDate since,
      LocalDate until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<TradeUpdateEventV2> listener)
      throws ApiException {
    return subscribeToTradeEvents(
        new BrokerSseDateOptions(since, until, sinceId, untilId), listener);
  }

  /** Opens the Broker account status SSE stream. */
  public BrokerSseSubscription subscribeToAccountStatus(
      BrokerSseIdentifiedLegacyDateOptions options,
      BrokerSseEventListener<AccountStatusEvent> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToAccountStatusSSECall(
                options.since(),
                options.until(),
                options.sinceId(),
                options.untilId(),
                options.sinceUlid(),
                options.untilUlid(),
                options.id(),
                null)
            .request(),
        AccountStatusEvent.class,
        listener);
  }

  /** Opens the Broker account status SSE stream with explicit legacy cursor filters. */
  public BrokerSseSubscription subscribeToAccountStatus(
      LocalDate since,
      LocalDate until,
      Integer sinceId,
      Integer untilId,
      String sinceUlid,
      String untilUlid,
      String id,
      BrokerSseEventListener<AccountStatusEvent> listener)
      throws ApiException {
    return subscribeToAccountStatus(
        new BrokerSseIdentifiedLegacyDateOptions(
            since, until, sinceId, untilId, sinceUlid, untilUlid, id),
        listener);
  }

  /**
   * Fetches one previously observed Broker activity event through its single-event SSE endpoint.
   */
  public CompletableFuture<ActivityEventV2> getAccountActivityEventAsync(
      UUID accountId, String eventId) throws ApiException {
    return getAccountActivityEventAsync(accountId, eventId, Duration.ofSeconds(30));
  }

  /** Fetches one Broker activity event, failing if no event arrives before {@code timeout}. */
  public CompletableFuture<ActivityEventV2> getAccountActivityEventAsync(
      UUID accountId, String eventId, Duration timeout) throws ApiException {
    Objects.requireNonNull(timeout, "timeout must not be null");
    var result = new CompletableFuture<ActivityEventV2>();
    var subscription = new AtomicReference<AlpacaSseSubscription>();
    var received = new AtomicReference<ActivityEventV2>();
    AlpacaSseOptions oneEventOptions =
        sseOptions.toBuilder()
            .reconnectPolicy(AlpacaSseReconnectPolicy.disabled())
            .maxDuration(timeout)
            .build();
    AlpacaSseSubscription opened =
        SseTransport.open(
            httpClient,
            accountsApi.getAccountActivityEventCall(accountId, eventId, null).request(),
            oneEventOptions,
            true,
            new BrokerActivityEventDecoder()::decode,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<ActivityEventV2> event) {
                received.compareAndSet(null, event.data());
                result.complete(event.data());
                AlpacaSseSubscription active = subscription.get();
                if (active != null) active.close();
              }
            },
            callbackExecutor);
    subscription.set(opened);
    if (result.isDone()) opened.close();
    opened
        .completion()
        .whenComplete(
            (closeResult, failure) ->
                SseTransport.executeLifecycle(
                    () -> {
                      ActivityEventV2 event = received.get();
                      if (event != null) {
                        result.complete(event);
                      } else if (failure != null) {
                        result.completeExceptionally(failure);
                      } else {
                        result.completeExceptionally(
                            new AlpacaSseProtocolException(
                                "Single-activity SSE response ended without an event"));
                      }
                    }));
    result.whenComplete(
        (ignored, failure) -> {
          if (result.isCancelled()) opened.close();
        });
    return result;
  }

  private <T> BrokerSseSubscription open(
      Request request, Type eventType, BrokerSseEventListener<T> listener) {
    return open(request, data -> JSON.getGson().fromJson(data, eventType), listener);
  }

  private <T> BrokerSseSubscription open(
      Request request, SseDecoder<T> decoder, BrokerSseEventListener<T> listener) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(decoder, "decoder must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    boolean bounded =
        request.url().queryParameter("until") != null
            || request.url().queryParameter("until_id") != null
            || request.url().queryParameter("until_ulid") != null;
    AlpacaSseSubscription subscription =
        SseTransport.open(
            httpClient,
            request,
            sseOptions,
            bounded,
            decoder,
            adapt(listener, request),
            callbackExecutor,
            SseDecodeFailurePolicy.REPORT_AND_CONTINUE);
    return new BrokerSseSubscription(subscription);
  }

  private static <T> AlpacaSseListener<T> adapt(
      BrokerSseEventListener<T> listener, Request request) {
    return new AlpacaSseListener<>() {
      @Override
      public void onOpen() {
        listener.onOpen();
      }

      @Override
      public void onEvent(AlpacaSseEvent<T> event) {
        listener.onEvent(event.data(), event.id(), event.type());
      }

      @Override
      public void onComment(String comment) {
        listener.onComment(comment);
      }

      @Override
      public void onRetryChanged(Duration delay) {
        listener.onRetryChanged(delay);
      }

      @Override
      public void onReconnecting(int attempt, Duration delay) {
        listener.onReconnecting(attempt, delay);
      }

      @Override
      public void onReconnected() {
        listener.onReconnected();
      }

      @Override
      public void onClosed(markets.alpaca.client.sse.AlpacaSseCloseResult result) {
        listener.onClosed(result);
      }

      @Override
      public void onFailure(Throwable failure) {
        if (failure instanceof AlpacaSseDeserializationException decodingFailure) {
          listener.onEventFailure(decodingFailure);
        } else if (failure instanceof AlpacaSseHttpException httpFailure) {
          listener.onHttpFailure(httpFailure, responseFor(httpFailure, request));
        } else {
          listener.onFailure(failure, null);
        }
      }
    };
  }

  private static Response responseFor(Throwable failure, Request request) {
    if (!(failure instanceof AlpacaSseHttpException httpFailure)) return null;
    var builder =
        new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(httpFailure.statusCode())
            .message("SSE request failed");
    httpFailure
        .headers()
        .forEach((name, values) -> values.forEach(value -> builder.addHeader(name, value)));
    String contentType =
        httpFailure.headers().entrySet().stream()
            .filter(entry -> "Content-Type".equalsIgnoreCase(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream())
            .findFirst()
            .orElse(null);
    MediaType mediaType = contentType == null ? null : MediaType.parse(contentType);
    builder.body(ResponseBody.create(httpFailure.responseBody(), mediaType));
    return builder.build();
  }
}
