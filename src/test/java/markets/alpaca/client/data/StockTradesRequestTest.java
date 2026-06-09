package markets.alpaca.client.data;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;
import org.junit.jupiter.api.Test;

class StockTradesRequestTest {

  @Test
  void builder_setsNamedParameters() {
    var start = OffsetDateTime.parse("2026-06-19T10:15:30Z");
    var end = OffsetDateTime.parse("2026-06-19T16:00:00Z");

    var request =
        StockTradesRequest.builder()
            .symbols("AAPL", "MSFT")
            .start(start)
            .end(end)
            .limit(100)
            .asof(LocalDate.of(2026, 6, 19))
            .feed(StockHistoricalFeed.IEX)
            .currency("USD")
            .pageToken("next-page")
            .sort(Sort.ASC)
            .build();

    assertEquals(List.of("AAPL", "MSFT"), request.symbols());
    assertEquals("AAPL,MSFT", request.symbolsParameter());
    assertEquals(start, request.start());
    assertEquals(end, request.end());
    assertEquals(100, request.limit());
    assertEquals("2026-06-19", request.asof());
    assertEquals(StockHistoricalFeed.IEX, request.feed());
    assertEquals("USD", request.currency());
    assertEquals("next-page", request.pageToken());
    assertEquals(Sort.ASC, request.sort());
  }

  @Test
  void builder_acceptsSymbolCollections() {
    var request = StockTradesRequest.builder().symbols(List.of("AAPL", "MSFT")).build();

    assertEquals(List.of("AAPL", "MSFT"), request.symbols());
    assertEquals("AAPL,MSFT", request.symbolsParameter());
  }

  @Test
  void skipSymbolMapping_setsAsOfDash() {
    var request = StockTradesRequest.builder().symbols("AAPL").skipSymbolMapping().build();

    assertEquals("-", request.asof());
  }

  @Test
  void singleSymbol_returnsOnlySymbol() {
    var request = StockTradesRequest.builder().symbols("AAPL").build();

    assertEquals("AAPL", request.singleSymbol());
  }

  @Test
  void singleSymbol_rejectsMultiSymbolRequest() {
    var request = StockTradesRequest.builder().symbols("AAPL", "MSFT").build();

    assertThrows(IllegalArgumentException.class, request::singleSymbol);
  }

  @Test
  void constructor_defensivelyCopiesSymbols() {
    var symbols = new ArrayList<>(List.of("AAPL"));
    var request = new StockTradesRequest(symbols, null, null, null, null, null, null, null, null);

    symbols.add("MSFT");

    assertEquals(List.of("AAPL"), request.symbols());
    assertThrows(UnsupportedOperationException.class, () -> request.symbols().add("MSFT"));
  }

  @Test
  void build_rejectsMissingSymbols() {
    assertThrows(IllegalArgumentException.class, () -> StockTradesRequest.builder().build());
    assertThrows(
        IllegalArgumentException.class, () -> StockTradesRequest.builder().symbols(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> StockTradesRequest.builder().symbols(new String[0]));
  }

  @Test
  void build_rejectsInvalidValues() {
    var symbols = new ArrayList<String>();
    symbols.add("AAPL");
    symbols.add(null);

    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL", " ").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL", null).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols(symbols).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL").limit(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL").asof(" ").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL").currency(" ").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> StockTradesRequest.builder().symbols("AAPL").pageToken(" ").build());
  }
}
