package markets.alpaca.client.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;

/**
 * Adapters from generated {@code *Async(..., ApiCallback<T>)} REST methods to {@link
 * CompletableFuture}.
 *
 * <p>Generated Broker, Market Data, and Trading clients each define their own {@code ApiCallback}
 * and {@code ApiException} types. These overloads keep those generated types intact while letting
 * application code use futures:
 *
 * <pre>{@code
 * CompletableFuture<Account> future =
 *     AlpacaFutures.trading(callback -> accountsApi.getAccountAsync(callback));
 * }</pre>
 *
 * <p>Cancelling the returned future cancels the underlying OkHttp {@link Call}.
 */
public final class AlpacaFutures {

  private AlpacaFutures() {}

  @FunctionalInterface
  public interface BrokerAsyncCall<T> {
    Call start(markets.alpaca.client.openapi.broker.http.ApiCallback<T> callback)
        throws markets.alpaca.client.openapi.broker.http.ApiException;
  }

  @FunctionalInterface
  public interface DataAsyncCall<T> {
    Call start(markets.alpaca.client.openapi.data.http.ApiCallback<T> callback)
        throws markets.alpaca.client.openapi.data.http.ApiException;
  }

  @FunctionalInterface
  public interface TradingAsyncCall<T> {
    Call start(markets.alpaca.client.openapi.trading.http.ApiCallback<T> callback)
        throws markets.alpaca.client.openapi.trading.http.ApiException;
  }

  /** Completes with the deserialized Broker response body. */
  public static <T> CompletableFuture<T> broker(BrokerAsyncCall<T> asyncCall) {
    return bodyFuture(brokerResponse(asyncCall));
  }

  /** Completes with the Broker response body, HTTP status code, and headers. */
  public static <T> CompletableFuture<AlpacaApiResponse<T>> brokerResponse(
      BrokerAsyncCall<T> asyncCall) {
    Objects.requireNonNull(asyncCall, "asyncCall must not be null");
    var future = new CompletableFuture<AlpacaApiResponse<T>>();
    var callRef = new AtomicReference<Call>();
    future.whenComplete(
        (ignored, error) -> {
          if (future.isCancelled()) cancel(callRef.get());
        });

    try {
      Call call =
          asyncCall.start(
              new markets.alpaca.client.openapi.broker.http.ApiCallback<>() {
                @Override
                public void onFailure(
                    markets.alpaca.client.openapi.broker.http.ApiException e,
                    int statusCode,
                    Map<String, List<String>> responseHeaders) {
                  future.completeExceptionally(e);
                }

                @Override
                public void onSuccess(
                    T result, int statusCode, Map<String, List<String>> responseHeaders) {
                  future.complete(new AlpacaApiResponse<>(result, statusCode, responseHeaders));
                }

                @Override
                public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {}

                @Override
                public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {}
              });
      callRef.set(call);
      if (future.isCancelled()) cancel(call);
    } catch (markets.alpaca.client.openapi.broker.http.ApiException | RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  /** Completes with the deserialized Market Data response body. */
  public static <T> CompletableFuture<T> data(DataAsyncCall<T> asyncCall) {
    return bodyFuture(dataResponse(asyncCall));
  }

  /** Completes with the Market Data response body, HTTP status code, and headers. */
  public static <T> CompletableFuture<AlpacaApiResponse<T>> dataResponse(
      DataAsyncCall<T> asyncCall) {
    Objects.requireNonNull(asyncCall, "asyncCall must not be null");
    var future = new CompletableFuture<AlpacaApiResponse<T>>();
    var callRef = new AtomicReference<Call>();
    future.whenComplete(
        (ignored, error) -> {
          if (future.isCancelled()) cancel(callRef.get());
        });

    try {
      Call call =
          asyncCall.start(
              new markets.alpaca.client.openapi.data.http.ApiCallback<>() {
                @Override
                public void onFailure(
                    markets.alpaca.client.openapi.data.http.ApiException e,
                    int statusCode,
                    Map<String, List<String>> responseHeaders) {
                  future.completeExceptionally(e);
                }

                @Override
                public void onSuccess(
                    T result, int statusCode, Map<String, List<String>> responseHeaders) {
                  future.complete(new AlpacaApiResponse<>(result, statusCode, responseHeaders));
                }

                @Override
                public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {}

                @Override
                public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {}
              });
      callRef.set(call);
      if (future.isCancelled()) cancel(call);
    } catch (markets.alpaca.client.openapi.data.http.ApiException | RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  /** Completes with the deserialized Trading response body. */
  public static <T> CompletableFuture<T> trading(TradingAsyncCall<T> asyncCall) {
    return bodyFuture(tradingResponse(asyncCall));
  }

  /** Completes with the Trading response body, HTTP status code, and headers. */
  public static <T> CompletableFuture<AlpacaApiResponse<T>> tradingResponse(
      TradingAsyncCall<T> asyncCall) {
    Objects.requireNonNull(asyncCall, "asyncCall must not be null");
    var future = new CompletableFuture<AlpacaApiResponse<T>>();
    var callRef = new AtomicReference<Call>();
    future.whenComplete(
        (ignored, error) -> {
          if (future.isCancelled()) cancel(callRef.get());
        });

    try {
      Call call =
          asyncCall.start(
              new markets.alpaca.client.openapi.trading.http.ApiCallback<>() {
                @Override
                public void onFailure(
                    markets.alpaca.client.openapi.trading.http.ApiException e,
                    int statusCode,
                    Map<String, List<String>> responseHeaders) {
                  future.completeExceptionally(e);
                }

                @Override
                public void onSuccess(
                    T result, int statusCode, Map<String, List<String>> responseHeaders) {
                  future.complete(new AlpacaApiResponse<>(result, statusCode, responseHeaders));
                }

                @Override
                public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {}

                @Override
                public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {}
              });
      callRef.set(call);
      if (future.isCancelled()) cancel(call);
    } catch (markets.alpaca.client.openapi.trading.http.ApiException | RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  private static void cancel(Call call) {
    if (call != null) call.cancel();
  }

  private static <T> CompletableFuture<T> bodyFuture(
      CompletableFuture<AlpacaApiResponse<T>> responseFuture) {
    CompletableFuture<T> bodyFuture = responseFuture.thenApply(AlpacaApiResponse::body);
    bodyFuture.whenComplete(
        (ignored, error) -> {
          if (bodyFuture.isCancelled()) responseFuture.cancel(true);
        });
    return bodyFuture;
  }
}
