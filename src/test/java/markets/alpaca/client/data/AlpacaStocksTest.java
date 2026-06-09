package markets.alpaca.client.data;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.http.ApiException;
import markets.alpaca.client.openapi.data.http.ApiResponse;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;
import markets.alpaca.client.openapi.data.model.StockTradesResp;
import markets.alpaca.client.openapi.data.model.StockTradesRespSingle;
import org.junit.jupiter.api.Test;

class AlpacaStocksTest {

  @Test
  void generatedApi_returnsWrappedApi() {
    var generated = new CapturingStockApi();
    var stocks = new AlpacaStocks(generated);

    assertSame(generated, stocks.generatedApi());
  }

  @Test
  void trades_mapsNamedRequestToGeneratedMultiSymbolApi() throws Exception {
    var generated = new CapturingStockApi();
    var stocks = new AlpacaStocks(generated);
    var expected = new StockTradesResp();
    generated.tradesResponse = expected;
    var start = OffsetDateTime.parse("2026-06-19T10:15:30Z");
    var end = OffsetDateTime.parse("2026-06-19T16:00:00Z");

    var actual =
        stocks.trades(
            StockTradesRequest.builder()
                .symbols("AAPL", "MSFT")
                .start(start)
                .end(end)
                .limit(100)
                .asof("2026-06-19")
                .feed(StockHistoricalFeed.IEX)
                .currency("USD")
                .pageToken("next-page")
                .sort(Sort.ASC)
                .build());

    assertSame(expected, actual);
    assertEquals("AAPL,MSFT", generated.symbols);
    assertNull(generated.symbol);
    assertEquals(start, generated.start);
    assertEquals(end, generated.end);
    assertEquals(100, generated.limit);
    assertEquals("2026-06-19", generated.asof);
    assertEquals(StockHistoricalFeed.IEX, generated.feed);
    assertEquals("USD", generated.currency);
    assertEquals("next-page", generated.pageToken);
    assertEquals(Sort.ASC, generated.sort);
  }

  @Test
  void tradesWithHttpInfo_returnsGeneratedResponse() throws Exception {
    var generated = new CapturingStockApi();
    var response =
        new ApiResponse<StockTradesResp>(200, Map.of("x-request-id", List.of("request-id")));
    generated.tradesHttpResponse = response;

    var actual =
        new AlpacaStocks(generated)
            .tradesWithHttpInfo(StockTradesRequest.builder().symbols("AAPL").build());

    assertSame(response, actual);
  }

  @Test
  void tradesForSymbol_mapsNamedRequestToGeneratedSingleSymbolApi() throws Exception {
    var generated = new CapturingStockApi();
    var stocks = new AlpacaStocks(generated);
    var expected = new StockTradesRespSingle();
    generated.singleTradesResponse = expected;

    var actual =
        stocks.tradesForSymbol(
            StockTradesRequest.builder()
                .symbols("AAPL")
                .limit(25)
                .feed(StockHistoricalFeed.SIP)
                .sort(Sort.DESC)
                .build());

    assertSame(expected, actual);
    assertEquals("AAPL", generated.symbol);
    assertNull(generated.symbols);
    assertEquals(25, generated.limit);
    assertEquals(StockHistoricalFeed.SIP, generated.feed);
    assertEquals(Sort.DESC, generated.sort);
  }

  @Test
  void tradesForSymbolWithHttpInfo_returnsGeneratedResponse() throws Exception {
    var generated = new CapturingStockApi();
    var response =
        new ApiResponse<StockTradesRespSingle>(200, Map.of("x-request-id", List.of("request-id")));
    generated.singleTradesHttpResponse = response;

    var actual =
        new AlpacaStocks(generated)
            .tradesForSymbolWithHttpInfo(StockTradesRequest.builder().symbols("AAPL").build());

    assertSame(response, actual);
  }

  @Test
  void trades_rejectsNullRequest() {
    var stocks = new AlpacaStocks(new CapturingStockApi());

    assertThrows(NullPointerException.class, () -> stocks.trades(null));
    assertThrows(NullPointerException.class, () -> stocks.tradesWithHttpInfo(null));
    assertThrows(NullPointerException.class, () -> stocks.tradesForSymbol(null));
    assertThrows(NullPointerException.class, () -> stocks.tradesForSymbolWithHttpInfo(null));
  }

  @Test
  void tradesForSymbol_rejectsMultiSymbolRequest() {
    var stocks = new AlpacaStocks(new CapturingStockApi());
    var request = StockTradesRequest.builder().symbols("AAPL", "MSFT").build();

    assertThrows(IllegalArgumentException.class, () -> stocks.tradesForSymbol(request));
  }

  private static final class CapturingStockApi extends StockApi {
    private StockTradesResp tradesResponse = new StockTradesResp();
    private StockTradesRespSingle singleTradesResponse = new StockTradesRespSingle();
    private ApiResponse<StockTradesResp> tradesHttpResponse =
        new ApiResponse<>(200, Map.of(), new StockTradesResp());
    private ApiResponse<StockTradesRespSingle> singleTradesHttpResponse =
        new ApiResponse<>(200, Map.of(), new StockTradesRespSingle());
    private String symbols;
    private String symbol;
    private OffsetDateTime start;
    private OffsetDateTime end;
    private Integer limit;
    private String asof;
    private StockHistoricalFeed feed;
    private String currency;
    private String pageToken;
    private Sort sort;

    @Override
    public StockTradesResp stockTrades(
        String symbols,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws ApiException {
      capture(null, symbols, start, end, limit, asof, feed, currency, pageToken, sort);
      return tradesResponse;
    }

    @Override
    public ApiResponse<StockTradesResp> stockTradesWithHttpInfo(
        String symbols,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws ApiException {
      capture(null, symbols, start, end, limit, asof, feed, currency, pageToken, sort);
      return tradesHttpResponse;
    }

    @Override
    public StockTradesRespSingle stockTradeSingle(
        String symbol,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws ApiException {
      capture(symbol, null, start, end, limit, asof, feed, currency, pageToken, sort);
      return singleTradesResponse;
    }

    @Override
    public ApiResponse<StockTradesRespSingle> stockTradeSingleWithHttpInfo(
        String symbol,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws ApiException {
      capture(symbol, null, start, end, limit, asof, feed, currency, pageToken, sort);
      return singleTradesHttpResponse;
    }

    private void capture(
        String symbol,
        String symbols,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort) {
      this.symbol = symbol;
      this.symbols = symbols;
      this.start = start;
      this.end = end;
      this.limit = limit;
      this.asof = asof;
      this.feed = feed;
      this.currency = currency;
      this.pageToken = pageToken;
      this.sort = sort;
    }
  }
}
