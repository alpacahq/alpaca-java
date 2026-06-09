package markets.alpaca.client.rest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class AlpacaFuturesTest {

  private static Call newCall() {
    return new OkHttpClient()
        .newCall(new Request.Builder().url("http://localhost/alpaca-futures-test").build());
  }

  @Test
  void data_completesWithResponseBodyOnSuccess() {
    var call = newCall();

    var future =
        AlpacaFutures.data(
            callback -> {
              callback.onSuccess("ok", 200, java.util.Map.of());
              return call;
            });

    assertEquals("ok", future.join());
  }

  @Test
  void dataResponse_preservesStatusCodeAndHeaders() {
    var headerValues = new ArrayList<>(List.of("first"));
    var headers = new LinkedHashMap<String, List<String>>();
    headers.put("x-test", headerValues);

    var future =
        AlpacaFutures.dataResponse(
            callback -> {
              callback.onSuccess("ok", 202, headers);
              return newCall();
            });
    headerValues.add("mutated");

    var response = future.join();
    assertEquals("ok", response.body());
    assertEquals(202, response.statusCode());
    assertEquals(List.of("first"), response.headers().get("x-test"));
    assertThrows(UnsupportedOperationException.class, () -> response.headers().put("x", List.of()));
    assertThrows(
        UnsupportedOperationException.class, () -> response.headers().get("x-test").add("x"));
  }

  @Test
  void dataResponse_acceptsNullBodyAndNullHeaders() {
    var future =
        AlpacaFutures.dataResponse(
            callback -> {
              callback.onSuccess(null, 204, null);
              return newCall();
            });

    var response = future.join();
    assertNull(response.body());
    assertEquals(204, response.statusCode());
    assertTrue(response.headers().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> response.headers().put("x", List.of()));
  }

  @Test
  void data_completesExceptionallyOnCallbackFailure() {
    var exception = new markets.alpaca.client.openapi.data.http.ApiException("boom");

    var future =
        AlpacaFutures.data(
            callback -> {
              callback.onFailure(exception, 500, java.util.Map.of());
              return newCall();
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void data_completesExceptionallyWhenAsyncMethodThrowsBeforeEnqueue() {
    var exception =
        new markets.alpaca.client.openapi.data.http.ApiException("missing required param");

    var future =
        AlpacaFutures.data(
            callback -> {
              throw exception;
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void data_completesExceptionallyWhenAsyncMethodThrowsRuntimeException() {
    var exception = new IllegalStateException("broken generated wrapper");

    var future =
        AlpacaFutures.data(
            callback -> {
              throw exception;
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void data_cancellingBodyFutureCancelsUnderlyingCall() {
    var callRef = new AtomicReference<Call>();

    var future =
        AlpacaFutures.data(
            callback -> {
              Call call = newCall();
              callRef.set(call);
              return call;
            });

    assertTrue(future.cancel(true));
    assertTrue(callRef.get().isCanceled());
  }

  @Test
  void dataResponse_cancellingResponseFutureCancelsUnderlyingCall() {
    var callRef = new AtomicReference<Call>();

    var future =
        AlpacaFutures.dataResponse(
            callback -> {
              Call call = newCall();
              callRef.set(call);
              return call;
            });

    assertTrue(future.cancel(true));
    assertTrue(callRef.get().isCanceled());
  }

  @Test
  void data_lateSuccessAfterCancellationDoesNotCompleteFuture() {
    var callbackRef =
        new AtomicReference<markets.alpaca.client.openapi.data.http.ApiCallback<String>>();

    var future =
        AlpacaFutures.<String>data(
            callback -> {
              callbackRef.set(callback);
              return newCall();
            });

    assertTrue(future.cancel(true));
    callbackRef.get().onSuccess("too-late", 200, java.util.Map.of());

    assertTrue(future.isCancelled());
    assertThrows(CancellationException.class, future::join);
  }

  @Test
  void data_lateFailureAfterCancellationDoesNotCompleteFuture() {
    var callbackRef =
        new AtomicReference<markets.alpaca.client.openapi.data.http.ApiCallback<String>>();

    var future =
        AlpacaFutures.<String>data(
            callback -> {
              callbackRef.set(callback);
              return newCall();
            });

    assertTrue(future.cancel(true));
    callbackRef
        .get()
        .onFailure(new markets.alpaca.client.openapi.data.http.ApiException("too late"), 500, null);

    assertTrue(future.isCancelled());
    assertThrows(CancellationException.class, future::join);
  }

  @Test
  void dataResponse_progressCallbacksAreNoops() {
    var future =
        AlpacaFutures.dataResponse(
            callback -> {
              callback.onUploadProgress(10, 20, false);
              callback.onDownloadProgress(20, 20, true);
              callback.onSuccess("ok", 200, java.util.Map.of());
              return newCall();
            });

    assertEquals("ok", future.join().body());
  }

  @Test
  void data_rejectsNullAsyncCall() {
    assertThrows(NullPointerException.class, () -> AlpacaFutures.data(null));
    assertThrows(NullPointerException.class, () -> AlpacaFutures.dataResponse(null));
  }

  @Test
  void broker_completesWithResponseBodyOnSuccess() {
    var future =
        AlpacaFutures.broker(
            callback -> {
              callback.onSuccess("broker-ok", 200, java.util.Map.of());
              return newCall();
            });

    assertEquals("broker-ok", future.join());
  }

  @Test
  void brokerResponse_preservesStatusCodeAndHeaders() {
    var future =
        AlpacaFutures.brokerResponse(
            callback -> {
              callback.onSuccess("broker-ok", 201, java.util.Map.of("x-broker", List.of("yes")));
              return newCall();
            });

    var response = future.join();
    assertEquals("broker-ok", response.body());
    assertEquals(201, response.statusCode());
    assertEquals(List.of("yes"), response.headers().get("x-broker"));
  }

  @Test
  void broker_completesExceptionallyOnCallbackFailure() {
    var exception = new markets.alpaca.client.openapi.broker.http.ApiException("broker-failed");

    var future =
        AlpacaFutures.broker(
            callback -> {
              callback.onFailure(exception, 500, java.util.Map.of());
              return newCall();
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void broker_completesExceptionallyWhenAsyncMethodThrowsBeforeEnqueue() {
    var exception =
        new markets.alpaca.client.openapi.broker.http.ApiException("missing required param");

    var future =
        AlpacaFutures.broker(
            callback -> {
              throw exception;
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void broker_cancellingBodyFutureCancelsUnderlyingCall() {
    var callRef = new AtomicReference<Call>();

    var future =
        AlpacaFutures.broker(
            callback -> {
              Call call = newCall();
              callRef.set(call);
              return call;
            });

    assertTrue(future.cancel(true));
    assertTrue(callRef.get().isCanceled());
  }

  @Test
  void broker_rejectsNullAsyncCall() {
    assertThrows(NullPointerException.class, () -> AlpacaFutures.broker(null));
    assertThrows(NullPointerException.class, () -> AlpacaFutures.brokerResponse(null));
  }

  @Test
  void trading_completesWithResponseBodyOnSuccess() {
    var future =
        AlpacaFutures.trading(
            callback -> {
              callback.onSuccess("trading-ok", 200, java.util.Map.of());
              return newCall();
            });

    assertEquals("trading-ok", future.join());
  }

  @Test
  void tradingResponse_preservesStatusCodeAndHeaders() {
    var future =
        AlpacaFutures.tradingResponse(
            callback -> {
              callback.onSuccess("trading-ok", 202, java.util.Map.of("x-trading", List.of("yes")));
              return newCall();
            });

    var response = future.join();
    assertEquals("trading-ok", response.body());
    assertEquals(202, response.statusCode());
    assertEquals(List.of("yes"), response.headers().get("x-trading"));
  }

  @Test
  void trading_completesExceptionallyOnCallbackFailure() {
    var exception = new markets.alpaca.client.openapi.trading.http.ApiException("trading-failed");

    var future =
        AlpacaFutures.trading(
            callback -> {
              callback.onFailure(exception, 500, java.util.Map.of());
              return newCall();
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void trading_completesExceptionallyWhenAsyncMethodThrowsBeforeEnqueue() {
    var exception =
        new markets.alpaca.client.openapi.trading.http.ApiException("missing required param");

    var future =
        AlpacaFutures.trading(
            callback -> {
              throw exception;
            });

    CompletionException thrown = assertThrows(CompletionException.class, future::join);
    assertSame(exception, thrown.getCause());
  }

  @Test
  void trading_cancellingBodyFutureCancelsUnderlyingCall() {
    var callRef = new AtomicReference<Call>();

    var future =
        AlpacaFutures.trading(
            callback -> {
              Call call = newCall();
              callRef.set(call);
              return call;
            });

    assertTrue(future.cancel(true));
    assertTrue(callRef.get().isCanceled());
  }

  @Test
  void trading_rejectsNullAsyncCall() {
    assertThrows(NullPointerException.class, () -> AlpacaFutures.trading(null));
    assertThrows(NullPointerException.class, () -> AlpacaFutures.tradingResponse(null));
  }
}
