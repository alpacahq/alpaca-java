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
`BrokerSseSubscription` declaration in the file and in the call's lexical scope, recognized through
either the exact import or the fully qualified class name. Automatic rewrites are limited to
parameters and local variables; fields are report-only because a text scanner cannot safely resolve
inheritance and member lookup. The same identifier must have no other detected binder anywhere in
the file. It reports `SSE001` and leaves unchanged qualified or chained access
(`this.subscription` or `other.subscription`), fields, out-of-scope declarations, shadowed names or
same-named local/nested types, `var`, lambda parameters, comments inside the call chain, and any
declaration it cannot prove unique. Control-flow header declarations are also report-only because
an unbraced statement's scope cannot be established safely by this text scanner. It also reports
ambiguous imports,
`EventSource` casts/identity assumptions, raw Gson exception checks, deep OkHttp `Response` use,
blocking callbacks, generated SSE invocations or method references, and listeners overriding both
rich and legacy callbacks. Invocations and method references on a uniquely bound imported or fully
qualified `BrokerEventsSseClient`, using either a bare receiver or `this.receiver`, are recognized
as the handwritten replacement and are not reported as generated SSE usage. Other qualified
receivers remain report-only. These deliberate false negatives keep `--write` source-safe; migrate
`SSE001` findings manually.
Missing input paths and explicit non-Java files fail with exit code 2 instead of producing an empty
success report.

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
backpressure and deterministic callback order. The default executor runs event callbacks directly
on a transport thread. Opening and terminal callback delivery that is not already queued starts
through separate bounded SDK dispatchers.

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

- `close()` selects `USER_CLOSED` when it wins the terminal transition, cancels active/future
  transport work, and waits for `completion()` to settle before returning; if failure already won,
  it observes the exceptional completion without rethrowing it;
- a timeout or terminal failure also completes lifecycle state without waiting for a blocked
  callback;
- terminal listener delivery remains ordered after callbacks that were already admitted;
- no event, comment, retry, or reconnect callback is admitted after the terminal transition.

Terminal listener delivery is admitted before lifecycle futures notify synchronous continuations,
then waits for lifecycle completion to settle before invoking the listener. Closing while opening
callback startup is queued also releases the HTTP response thread without violating callback order;
the already-admitted callbacks may finish later. Completion settlement is handed off from an active
callback, so a callback may close even when a synchronous completion continuation waits for the
ordered terminal listener. Lifecycle completion uses isolated, direct-handoff SDK workers, so a
blocked continuation for one subscription cannot queue another subscription's settlement or occupy
the scheduler. Synchronous continuations must remain short because concurrently blocked
continuations consume additional daemon workers. The related lifecycle wait above is supported; use
an async continuation with an application-owned executor for unrelated or potentially unbounded
blocking work.

Once response headers are accepted, `opened()` succeeds with that connection even if closure races
with opening. Its admitted `onOpen` callback starts independently before `opened()` continuations
run and remains ordered before the terminal listener callback. A synchronous continuation can
therefore close the subscription and await `onClosed` without preventing callback delivery.

The opening and terminal dispatchers have finite workers and queue capacity. Opening-dispatch
rejection fails the subscription with `AlpacaSseCallbackException`. If the terminal dispatcher is
saturated by blocked terminal callbacks, the SDK preserves the already-settled lifecycle result,
rejects the pending listener delivery, and logs a warning. Keep callbacks non-blocking or supply an
application-owned executor; lifecycle futures are the authoritative termination signal.

A listener `RuntimeException` is logged and considered an application error, not a transport
failure. The event is considered delivered and its resume ID advances. An executor rejection is a
terminal `AlpacaSseCallbackException`. Persist work transactionally and deduplicate by event ID
when exactly-once effects matter.

## Reconnect and decode behavior

Trading SSE reconnects on transient failures by default and sends its committed cursor as the
documented `since_id` query parameter and in `Last-Event-ID`. Date-bounded retries retain the
original date range and can replay events. Existing Broker constructors keep the `0.1.4`
one-connection default; pass `AlpacaSseOptions` to opt into Broker reconnects.

Do not combine a non-empty `AlpacaSseOptions.initialLastEventId` with a date-bounded Trading
request; `0.1.5` rejects that ambiguous combination before opening a connection. Use an ID-bounded
request instead, and keep the initial ID at or before its `untilId`.

Trading malformed payloads terminate the subscription without advancing the event cursor. Broker
malformed payloads invoke `onEventFailure`, advance the cursor after callback invocation, and keep
the healthy connection open. If that callback throws, the cursor still advances.
Broker admin-action events retain `SubscribeToAdminActionSSE200ResponseInner` but now select its
concrete generated model from the required `type` field; missing, unknown, or schema-invalid types
follow that same malformed-event policy.
Both Trading and Broker retain activity type `DIVTXEX` while representing its details as
`CDIVActivityV2`; the pinned OAS accepts the type but does not provide a dedicated detail schema.

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

## Review generated validation changes

The `0.1.5` generated models enforce two newly required payload fields:

- Broker and Trading `OptionContract` requires `ppind`;
- Broker and Trading `CommonFixedIncomeInterestActivityV2` requires `interest_type`.

Update stored JSON, fixtures, mocks, and custom integrations before upgrading. Generated
`validateJsonElement` methods reject payloads that omit these fields even when application code does
not read them. See the complete generated-symbol list in the repository
[`MIGRATIONS.md`](https://github.com/alpacahq/alpaca-java/blob/main/MIGRATIONS.md).

## Migration checklist

- Replace `eventSource().cancel()` with `close()`.
- Review `EventSource` casts, identity checks, and cancellation-response assumptions.
- Replace startup latches with bounded `opened()` waits.
- Choose rich Broker callbacks or confirm the legacy delegation shapes.
- Move blocking callback work to an application-owned executor.
- Confirm listener exceptions may advance the resume cursor.
- Replace generated blocking SSE methods.
- Add `ppind` and `interest_type` to affected stored or mocked generated-model payloads.
- Select the Market Data corporate-actions stream environment explicitly when production is not
  appropriate.
- Test reconnect/replay behavior and deduplicate by event ID.
