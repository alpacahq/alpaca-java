package markets.alpaca.client.data;

import java.util.Objects;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.http.ApiException;
import markets.alpaca.client.openapi.data.http.ApiResponse;
import markets.alpaca.client.openapi.data.model.StockTradesResp;
import markets.alpaca.client.openapi.data.model.StockTradesRespSingle;

/**
 * Handwritten convenience facade for common Market Data stock workflows.
 *
 * <p>This class wraps the generated Market Data {@link StockApi} and exposes typed request objects
 * for common operations. Use {@link #generatedApi()} when an endpoint is not covered by this
 * facade.
 *
 * @see <a href="https://docs.alpaca.markets/openapi/market-data-api.json">Market Data OpenAPI
 *     spec</a>
 */
public final class AlpacaStocks {

  private final StockApi generatedApi;

  /** Creates a facade around a generated Market Data {@link StockApi}. */
  public AlpacaStocks(StockApi generatedApi) {
    this.generatedApi = Objects.requireNonNull(generatedApi, "generatedApi must not be null");
  }

  /** Creates a facade from a generated Market Data {@code ApiClient}. */
  public AlpacaStocks(markets.alpaca.client.openapi.data.http.ApiClient dataClient) {
    this(new StockApi(Objects.requireNonNull(dataClient, "dataClient must not be null")));
  }

  /** Returns the generated API for operations not covered by this facade. */
  public StockApi generatedApi() {
    return generatedApi;
  }

  /**
   * Returns historical stock trades for one or more symbols.
   *
   * <p>Use {@link StockTradesRequest#builder()} to set symbols, time range, feed, currency,
   * pagination token, and sort order without depending on generated parameter ordering.
   */
  public StockTradesResp trades(StockTradesRequest request) throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.stockTrades(
        request.symbolsParameter(),
        request.start(),
        request.end(),
        request.limit(),
        request.asof(),
        request.feed(),
        request.currency(),
        request.pageToken(),
        request.sort());
  }

  /**
   * Returns historical stock trades with HTTP status code and response headers.
   *
   * <p>Use this overload for pagination and rate-limit headers.
   */
  public ApiResponse<StockTradesResp> tradesWithHttpInfo(StockTradesRequest request)
      throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.stockTradesWithHttpInfo(
        request.symbolsParameter(),
        request.start(),
        request.end(),
        request.limit(),
        request.asof(),
        request.feed(),
        request.currency(),
        request.pageToken(),
        request.sort());
  }

  /**
   * Returns historical stock trades through the single-symbol endpoint.
   *
   * <p>The request must contain exactly one symbol. The single-symbol response model differs from
   * the multi-symbol response model generated from the OpenAPI spec.
   */
  public StockTradesRespSingle tradesForSymbol(StockTradesRequest request) throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.stockTradeSingle(
        request.singleSymbol(),
        request.start(),
        request.end(),
        request.limit(),
        request.asof(),
        request.feed(),
        request.currency(),
        request.pageToken(),
        request.sort());
  }

  /** Returns single-symbol historical stock trades with HTTP status code and response headers. */
  public ApiResponse<StockTradesRespSingle> tradesForSymbolWithHttpInfo(StockTradesRequest request)
      throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.stockTradeSingleWithHttpInfo(
        request.singleSymbol(),
        request.start(),
        request.end(),
        request.limit(),
        request.asof(),
        request.feed(),
        request.currency(),
        request.pageToken(),
        request.sort());
  }
}
