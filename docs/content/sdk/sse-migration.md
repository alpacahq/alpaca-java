---
id: sse-migration
title: Migrating SSE from 0.1.4 to 0.1.5
---

Version `0.1.5` adds Trading account-activity and Market Data corporate-actions SSE, and moves
Broker SSE onto the shared bounded, reconnecting transport. Existing Broker method signatures
remain available, but applications should review cancellation, callback scheduling, failure
handling, and uses of the exposed OkHttp `EventSource`.

`BrokerSseSubscription` now implements the shared `AlpacaSseSubscription` interface. This is
additive: existing Broker declarations continue to compile, while shared lifecycle code can accept
Broker, Trading, and Market Data subscriptions through one type.

## Recommended migration

### Close the subscription directly

`eventSource()` remains binary/source compatible, but now returns a stable facade rather than the
live OkHttp implementation. Replace cancellation through that facade:

```java
// 0.1.4
subscription.eventSource().cancel();

// 0.1.5
subscription.close();
```

Do not cast the facade, compare its identity, or rely on implementation-specific methods. Its
supported operations are `request()` and `cancel()`.

The repository includes a conservative scanner/codemod:

```bash
# Report safe rewrites and manual-review findings.
python3 scripts/migrate_sse_0_1_4_to_0_1_5.py src/main/java

# Apply only proven BrokerSseSubscription rewrites.
python3 scripts/migrate_sse_0_1_4_to_0_1_5.py --write src/main/java

# CI/reporting modes.
python3 scripts/migrate_sse_0_1_4_to_0_1_5.py --check --json src/main/java
```

The tool rewrites only a bare identifier receiver with exactly one explicit
`BrokerSseSubscription` declaration in the file, recognized through either the exact import or the
fully qualified class name. The same identifier must have no other detected binder anywhere in the
file. It reports `SSE001` and leaves unchanged qualified or chained access (`this.subscription` or
`other.subscription`), shadowed names, `var`, inferred lambda parameters, comments inside the call
chain, and any declaration it cannot prove unique. It also reports ambiguous imports,
`EventSource` casts/identity assumptions, raw Gson exception checks, deep OkHttp `Response` use,
blocking callbacks, generated SSE calls, and listeners overriding both rich and legacy callbacks.
These deliberate false negatives keep `--write` source-safe; migrate `SSE001` findings manually.

### Await startup without a latch

Replace an `onOpen` latch with the first-connection future and a caller-controlled timeout:

```java
var subscription = events.subscribeToActivities(listener);
var connection = subscription.opened().get(10, TimeUnit.SECONDS);
System.out.printf("%d %s%n", connection.statusCode(), connection.uri());
```

`opened()` retains the first accepted connection. `connection()` returns the latest accepted
connection after reconnects. Closing before the first response makes `opened()` exceptional.

### Prefer structured Broker callbacks

Legacy listeners continue to work:

- malformed events delegate their original deserialization cause to
  `onFailure(Throwable, null)`;
- non-successful HTTP responses delegate to `onFailure(null, boundedResponse)`;
- user cancellation delegates to `onFailure(new IOException("canceled"), null)`;
- other normal remote/bounded endings delegate to `onClosed()`.

The old stripped OkHttp response from cancellation is not reproduced. Protocol/content-type and
resource-limit failures now use the SDK's structured exception hierarchy and do not expose a live
response. HTTP compatibility responses are reconstructed from bounded status, headers, request ID,
and body data; `handshake()`, `networkResponse()`, `cacheResponse()`, and related transport internals
are unavailable.

New listeners can override the richer methods instead:

```java
new BrokerSseEventListener<TradeUpdateEventV2>() {
    @Override
    public void onEventFailure(AlpacaSseDeserializationException failure) {
        System.err.printf(
            "event %s (%s) was malformed%n", failure.eventId(), failure.eventType());
    }

    @Override
    public void onHttpFailure(
            AlpacaSseHttpException failure, okhttp3.Response boundedResponse) {
        System.err.printf("HTTP %d request=%s%n",
            failure.statusCode(), failure.requestId());
    }

    @Override
    public void onClosed(AlpacaSseCloseResult result) {
        System.out.println("closed: " + result.reason());
    }
};
```

The transport invokes only the richest applicable callback. Its default method performs legacy
delegation, so overriding both a rich method and its legacy target does not cause duplicate
delivery.

User cancellation remains a successful structured lifecycle result:

```java
subscription.close();
var result = subscription.completion().join();
assert result.reason() == AlpacaSseCloseResult.Reason.USER_CLOSED;
```

That successful completion is independent from the legacy cancellation-shaped `onFailure`
callback. Override `onClosed(AlpacaSseCloseResult)` when an application does not want the legacy
callback shape.

## Callback and threading changes

SSE callbacks are serialized per subscription. Parsing waits for each data callback, providing
backpressure and deterministic callback order. The default executor runs callbacks directly on a
transport or lifecycle thread.

Use an application-owned executor when callbacks block:

```java
var callbacks = Executors.newSingleThreadExecutor();
var events = AlpacaClientFactory.tradingEventsSseClient(
    tradingClient, sseOptions, callbacks);
```

The SDK does not shut down that executor. Sharing one single-thread executor between subscriptions
intentionally serializes their callbacks. It cannot stall lifecycle timers or cancellation, but an
active event callback backpressures response parsing and can delay detection of remote EOF and the
following reconnect.

Lifecycle completion is deliberately separate from listener delivery:

- `close()` selects `USER_CLOSED`, cancels active/future transport work, and completes
  `completion()` before returning;
- a timeout or terminal failure also completes lifecycle state without waiting for a blocked
  callback;
- terminal listener delivery remains ordered after callbacks that were already admitted;
- no event, comment, retry, or reconnect callback is admitted after the terminal transition.

A listener `RuntimeException` is logged and considered an application error, not a transport
failure. The event is considered delivered and its resume ID advances. An executor rejection is a
terminal `AlpacaSseCallbackException`. Persist work transactionally and deduplicate by event ID
when exactly-once effects matter.

## Reconnect and decode behavior

Trading SSE reconnects on transient failures by default and sends its committed cursor as the
documented `since_id` query parameter and in `Last-Event-ID`. Date-bounded retries retain the
original date range and can replay events. Existing Broker constructors keep the `0.1.4`
one-connection default; pass `AlpacaSseOptions` to opt into Broker reconnects.

Trading malformed payloads terminate the subscription without advancing the event cursor. Broker
malformed payloads invoke `onEventFailure`, advance the cursor after callback invocation, and keep
the healthy connection open. If that callback throws, the cursor still advances.

Servers may replay the last event inclusively. Resume support provides at-least-once delivery, not
exactly-once processing.

## Replace generated blocking SSE calls

OpenAPI-generated SSE methods buffer a response like an ordinary REST call and can wait
indefinitely. Replace them with the handwritten clients:

```java
var tradingEvents = AlpacaClientFactory.tradingEventsSseClient(tradingClient);
var tradingSubscription = tradingEvents.subscribeToActivities(listener);

var corporateActions =
    AlpacaClientFactory.corporateActionsSseClient(dataClient);
var corporateActionsSubscription =
    corporateActions.subscribeToCorporateActions(corporateActionsListener);

var brokerEvents = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
var brokerSubscription =
    brokerEvents.subscribeToTradeEvents(BrokerSseDateOptions.empty(), brokerListener);
```

See [Streaming & Events](./streaming) for all stream types and [Broker](./broker) for Broker
endpoint-specific examples.

## Migration checklist

- Replace `eventSource().cancel()` with `close()`.
- Review `EventSource` casts, identity checks, and cancellation-response assumptions.
- Replace startup latches with bounded `opened()` waits.
- Choose rich Broker callbacks or confirm the legacy delegation shapes.
- Move blocking callback work to an application-owned executor.
- Confirm listener exceptions may advance the resume cursor.
- Replace generated blocking SSE methods.
- Select the Market Data corporate-actions stream environment explicitly when production is not
  appropriate.
- Test reconnect/replay behavior and deduplicate by event ID.
