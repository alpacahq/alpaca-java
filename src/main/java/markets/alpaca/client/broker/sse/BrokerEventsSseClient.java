package markets.alpaca.client.broker.sse;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import markets.alpaca.client.openapi.broker.api.EventsApi;
import markets.alpaca.client.openapi.broker.http.ApiException;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.AccountStatusEvent;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2;
import markets.alpaca.client.openapi.broker.model.IPOEvent;
import markets.alpaca.client.openapi.broker.model.JournalStatusEvent;
import markets.alpaca.client.openapi.broker.model.JournalStatusEventV2;
import markets.alpaca.client.openapi.broker.model.NonTradeActivityEvent;
import markets.alpaca.client.openapi.broker.model.SubscribeToAdminActionSSE200ResponseInner;
import markets.alpaca.client.openapi.broker.model.SubscribeToFundingStatusSSE200ResponseInner;
import markets.alpaca.client.openapi.broker.model.SystemEventV2;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * Streaming wrapper for Broker Events SSE endpoints.
 *
 * <p>The generated {@link EventsApi} exposes SSE endpoints as ordinary blocking REST calls, which
 * can hang indefinitely for live streams. This wrapper reuses the generated request builders for
 * path, query parameters, and authentication, then opens them through OkHttp's SSE client.
 */
public final class BrokerEventsSseClient {

  private static final Logger LOG = Logger.getLogger(BrokerEventsSseClient.class.getName());
  private static final Executor DIRECT_CALLBACK_EXECUTOR = Runnable::run;

  private final EventsApi eventsApi;
  private final EventSource.Factory eventSourceFactory;
  private final Executor callbackExecutor;

  /** Creates an SSE wrapper around a generated Broker {@link EventsApi}. */
  public BrokerEventsSseClient(EventsApi eventsApi) {
    this(eventsApi, DIRECT_CALLBACK_EXECUTOR);
  }

  /** Creates an SSE wrapper around a generated Broker {@link EventsApi}. */
  public BrokerEventsSseClient(EventsApi eventsApi, Executor callbackExecutor) {
    this.eventsApi = Objects.requireNonNull(eventsApi, "eventsApi must not be null");
    this.callbackExecutor =
        Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
    OkHttpClient sseHttpClient =
        eventsApi.getApiClient().getHttpClient().newBuilder().readTimeout(Duration.ZERO).build();
    this.eventSourceFactory = EventSources.createFactory(sseHttpClient);
  }

  /** Creates an SSE wrapper from a generated Broker {@code ApiClient}. */
  public BrokerEventsSseClient(markets.alpaca.client.openapi.broker.http.ApiClient apiClient) {
    this(new EventsApi(apiClient));
  }

  /** Creates an SSE wrapper from a generated Broker {@code ApiClient}. */
  public BrokerEventsSseClient(
      markets.alpaca.client.openapi.broker.http.ApiClient apiClient, Executor callbackExecutor) {
    this(new EventsApi(apiClient), callbackExecutor);
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
        ActivityEventV2.class,
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
      BrokerSseDateOptions options,
      BrokerSseEventListener<SubscribeToFundingStatusSSE200ResponseInner> listener)
      throws ApiException {
    Objects.requireNonNull(options, "options must not be null");
    return open(
        eventsApi
            .subscribeToFundingStatusSSECall(
                options.since(), options.until(), options.sinceId(), options.untilId(), null)
            .request(),
        SubscribeToFundingStatusSSE200ResponseInner.class,
        listener);
  }

  /** Opens the Broker funding status SSE stream with explicit date cursor filters. */
  public BrokerSseSubscription subscribeToFundingStatus(
      LocalDate since,
      LocalDate until,
      String sinceId,
      String untilId,
      BrokerSseEventListener<SubscribeToFundingStatusSSE200ResponseInner> listener)
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
            .suscribeToAccountStatusSSECall(
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

  private <T> BrokerSseSubscription open(
      Request request, Type eventType, BrokerSseEventListener<T> listener) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    EventSource source =
        eventSourceFactory.newEventSource(
            request, new TypedEventSourceListener<>(eventType, listener, callbackExecutor));
    return new BrokerSseSubscription(source);
  }

  private static final class TypedEventSourceListener<T> extends EventSourceListener {

    private final Type eventType;
    private final BrokerSseEventListener<T> listener;
    private final Executor callbackExecutor;

    private TypedEventSourceListener(
        Type eventType, BrokerSseEventListener<T> listener, Executor callbackExecutor) {
      this.eventType = eventType;
      this.listener = listener;
      this.callbackExecutor = callbackExecutor;
    }

    @Override
    public void onOpen(EventSource eventSource, Response response) {
      invokeCallback(callbackExecutor, "onOpen", listener::onOpen);
    }

    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
      try {
        T event = JSON.getGson().fromJson(data, eventType);
        invokeCallback(callbackExecutor, "onEvent", () -> listener.onEvent(event));
      } catch (RuntimeException e) {
        invokeCallback(callbackExecutor, "onFailure", () -> listener.onFailure(e, null));
      }
    }

    @Override
    public void onClosed(EventSource eventSource) {
      invokeCallback(callbackExecutor, "onClosed", listener::onClosed);
    }

    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
      invokeCallback(callbackExecutor, "onFailure", () -> listener.onFailure(t, response));
    }
  }

  private static void invokeCallback(
      Executor callbackExecutor, String callbackName, Runnable callback) {
    try {
      callbackExecutor.execute(
          () -> {
            try {
              callback.run();
            } catch (RuntimeException e) {
              LOG.log(Level.WARNING, "Broker SSE listener callback failed: " + callbackName, e);
            }
          });
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Broker SSE listener callback executor failed: " + callbackName, e);
    }
  }
}
