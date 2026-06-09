package markets.alpaca.client.ws.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every model record correctly maps the Alpaca wire format's single-character JSON
 * field names to typed Java fields via {@code @SerializedName}.
 */
class ModelDeserializationTest {

  private static final Gson GSON = new Gson();

  private static void assertDecimal(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }

  // -------------------------------------------------------------------------
  // Stock models
  // -------------------------------------------------------------------------

  @Test
  void stockTrade_deserializesAllFields() {
    String json =
        """
            {"T":"t","S":"AAPL","i":123,"x":"D","p":150.5,"s":100,
             "c":["@","I"],"t":"2024-01-15T14:30:00Z","z":"C"}
            """;
    StockTrade t = GSON.fromJson(json, StockTrade.class);

    assertEquals("AAPL", t.symbol());
    assertEquals(123L, t.tradeId());
    assertEquals("D", t.exchange());
    assertDecimal("150.5", t.price());
    assertEquals(100L, t.size());
    assertEquals(List.of("@", "I"), t.conditions());
    assertEquals("2024-01-15T14:30:00Z", t.timestamp());
    assertEquals("C", t.tape());
  }

  @Test
  void stockQuote_deserializesAllFields() {
    String json =
        """
            {"T":"q","S":"MSFT","ax":"N","ap":300.1,"as":5,
             "bx":"P","bp":300.0,"bs":10,"s":0,"c":["R"],
             "t":"2024-01-15T14:30:01Z","z":"A"}
            """;
    StockQuote q = GSON.fromJson(json, StockQuote.class);

    assertEquals("MSFT", q.symbol());
    assertEquals("N", q.askExchange());
    assertDecimal("300.1", q.askPrice());
    assertEquals(5L, q.askSize());
    assertEquals("P", q.bidExchange());
    assertDecimal("300.0", q.bidPrice());
    assertEquals(10L, q.bidSize());
    assertEquals(List.of("R"), q.conditions());
    assertEquals("2024-01-15T14:30:01Z", q.timestamp());
    assertEquals("A", q.tape());
  }

  @Test
  void stockBar_deserializesAllFields() {
    String json =
        """
            {"T":"b","S":"TSLA","o":200.0,"h":205.0,"l":199.0,
             "c":203.5,"v":1500000,"t":"2024-01-15T14:00:00Z"}
            """;
    StockBar b = GSON.fromJson(json, StockBar.class);

    assertEquals("TSLA", b.symbol());
    assertDecimal("200.0", b.open());
    assertDecimal("205.0", b.high());
    assertDecimal("199.0", b.low());
    assertDecimal("203.5", b.close());
    assertEquals(1_500_000L, b.volume());
    assertEquals("2024-01-15T14:00:00Z", b.timestamp());
  }

  @Test
  void tradeCorrection_deserializesAllFields() {
    String json =
        """
            {"T":"c","S":"AAPL","x":"D","oi":111,"op":149.0,"os":"100",
             "oc":["@"],"ci":222,"cp":150.0,"cs":100,"cc":["@"],
             "t":"2024-01-15T14:30:05Z","z":"C"}
            """;
    TradeCorrection c = GSON.fromJson(json, TradeCorrection.class);

    assertEquals("AAPL", c.symbol());
    assertEquals(111L, c.origTradeId());
    assertDecimal("149.0", c.origPrice());
    assertEquals(222L, c.correctedTradeId());
    assertDecimal("150.0", c.correctedPrice());
    assertEquals(100L, c.correctedSize());
    assertEquals("2024-01-15T14:30:05Z", c.timestamp());
    assertEquals("C", c.tape());
  }

  @Test
  void tradeCancelError_deserializesAllFields() {
    String json =
        """
            {"T":"x","S":"AAPL","i":456,"x":"D","p":150.5,"s":100,
             "a":"C","t":"2024-01-15T14:30:10Z","z":"C"}
            """;
    TradeCancelError x = GSON.fromJson(json, TradeCancelError.class);

    assertEquals("AAPL", x.symbol());
    assertEquals(456L, x.tradeId());
    assertEquals("D", x.exchange());
    assertDecimal("150.5", x.price());
    assertEquals(100L, x.size());
    assertEquals("C", x.action());
    assertEquals("2024-01-15T14:30:10Z", x.timestamp());
    assertEquals("C", x.tape());
  }

  @Test
  void luldBand_deserializesAllFields() {
    String json =
        """
            {"T":"l","S":"AAPL","u":160.0,"d":140.0,"i":"A",
             "t":"2024-01-15T09:45:00Z","z":"C"}
            """;
    LuldBand l = GSON.fromJson(json, LuldBand.class);

    assertEquals("AAPL", l.symbol());
    assertDecimal("160.0", l.limitUp());
    assertDecimal("140.0", l.limitDown());
    assertEquals("A", l.indicator());
    assertEquals("2024-01-15T09:45:00Z", l.timestamp());
    assertEquals("C", l.tape());
  }

  @Test
  void stockTradingStatus_deserializesAllFields() {
    String json =
        """
            {"T":"s","S":"AAPL","sc":"2","sm":"Trading Halt","rc":"T1",
             "rm":"Halt News Pending","t":"2024-01-15T10:00:00Z","z":"C"}
            """;
    StockTradingStatus s = GSON.fromJson(json, StockTradingStatus.class);

    assertEquals("AAPL", s.symbol());
    assertEquals("2", s.statusCode());
    assertEquals("Trading Halt", s.statusMessage());
    assertEquals("T1", s.reasonCode());
    assertEquals("Halt News Pending", s.reasonMessage());
    assertEquals("2024-01-15T10:00:00Z", s.timestamp());
    assertEquals("C", s.tape());
  }

  // -------------------------------------------------------------------------
  // Crypto models
  // -------------------------------------------------------------------------

  @Test
  void cryptoTrade_deserializesAllFields() {
    String json =
        """
            {"T":"t","S":"BTC/USD","p":45000.0,"s":0.5,
             "t":"2024-01-15T14:30:00Z","i":789,"tks":"B"}
            """;
    CryptoTrade t = GSON.fromJson(json, CryptoTrade.class);

    assertEquals("BTC/USD", t.symbol());
    assertDecimal("45000.0", t.price());
    assertDecimal("0.5", t.size());
    assertEquals("2024-01-15T14:30:00Z", t.timestamp());
    assertEquals(789L, t.tradeId());
    assertEquals("B", t.takerSide());
  }

  @Test
  void cryptoQuote_deserializesAllFields() {
    String json =
        """
            {"T":"q","S":"ETH/USD","bp":3000.0,"bs":1.5,
             "ap":3001.0,"as":2.0,"t":"2024-01-15T14:30:01Z"}
            """;
    CryptoQuote q = GSON.fromJson(json, CryptoQuote.class);

    assertEquals("ETH/USD", q.symbol());
    assertDecimal("3000.0", q.bidPrice());
    assertDecimal("1.5", q.bidSize());
    assertDecimal("3001.0", q.askPrice());
    assertDecimal("2.0", q.askSize());
    assertEquals("2024-01-15T14:30:01Z", q.timestamp());
  }

  @Test
  void cryptoBar_deserializesAllFields() {
    String json =
        """
            {"T":"b","S":"BTC/USD","o":44900.0,"h":45100.0,"l":44800.0,
             "c":45000.0,"v":12.5,"t":"2024-01-15T14:00:00Z","n":250,"vw":44990.0}
            """;
    CryptoBar b = GSON.fromJson(json, CryptoBar.class);

    assertEquals("BTC/USD", b.symbol());
    assertDecimal("44900.0", b.open());
    assertDecimal("45100.0", b.high());
    assertDecimal("44800.0", b.low());
    assertDecimal("45000.0", b.close());
    assertDecimal("12.5", b.volume());
    assertEquals("2024-01-15T14:00:00Z", b.timestamp());
    assertEquals(250, b.tradeCount());
    assertDecimal("44990.0", b.vwap());
  }

  @Test
  void cryptoOrderbook_deserializesAllFields() {
    String json =
        """
            {"T":"o","S":"BTC/USD","t":"2024-01-15T14:30:00Z",
             "b":[{"p":44999.0,"s":0.5}],"a":[{"p":45001.0,"s":1.0}],"r":true}
            """;
    CryptoOrderbook o = GSON.fromJson(json, CryptoOrderbook.class);

    assertEquals("BTC/USD", o.symbol());
    assertEquals("2024-01-15T14:30:00Z", o.timestamp());
    assertTrue(o.reset());
    assertEquals(1, o.bids().size());
    assertDecimal("44999.0", o.bids().get(0).price());
    assertDecimal("0.5", o.bids().get(0).size());
    assertEquals(1, o.asks().size());
    assertDecimal("45001.0", o.asks().get(0).price());
    assertDecimal("1.0", o.asks().get(0).size());
  }

  // -------------------------------------------------------------------------
  // News
  // -------------------------------------------------------------------------

  @Test
  void newsArticle_deserializesAllFields() {
    String json =
        """
            {"T":"n","id":42,"headline":"AAPL Q4 Results","summary":"Strong quarter",
             "author":"Jane Doe","created_at":"2024-01-15T20:00:00Z",
             "updated_at":"2024-01-15T20:05:00Z","url":"https://example.com/news/42",
             "content":"<p>Details...</p>","symbols":["AAPL","NVDA"],"source":"benzinga"}
            """;
    NewsArticle a = GSON.fromJson(json, NewsArticle.class);

    assertEquals(42L, a.id());
    assertEquals("AAPL Q4 Results", a.headline());
    assertEquals("Strong quarter", a.summary());
    assertEquals("Jane Doe", a.author());
    assertEquals("2024-01-15T20:00:00Z", a.createdAt());
    assertEquals("2024-01-15T20:05:00Z", a.updatedAt());
    assertEquals("https://example.com/news/42", a.url());
    assertEquals(List.of("AAPL", "NVDA"), a.symbols());
    assertEquals("benzinga", a.source());
  }

  // -------------------------------------------------------------------------
  // Trading models
  // -------------------------------------------------------------------------

  @Test
  void order_deserializesKeyFields() {
    String json =
        """
            {"id":"order-id-1","client_order_id":"client-1","symbol":"AAPL",
             "side":"buy","type":"limit","qty":"10","status":"new",
             "time_in_force":"day","limit_price":"150.00",
             "extended_hours":false,"created_at":"2024-01-15T14:30:00Z"}
            """;
    Order o = GSON.fromJson(json, Order.class);

    assertEquals("order-id-1", o.id());
    assertEquals("client-1", o.clientOrderId());
    assertEquals("AAPL", o.symbol());
    assertEquals("buy", o.side());
    assertEquals("limit", o.type());
    assertEquals("10", o.qty());
    assertEquals("new", o.status());
    assertEquals("day", o.timeInForce());
    assertEquals("150.00", o.limitPrice());
    assertFalse(o.extendedHours());
    assertEquals("2024-01-15T14:30:00Z", o.createdAt());
  }

  @Test
  void tradeUpdate_deserializesAllFields() {
    String json =
        """
            {"event":"fill","execution_id":"exec-1","price":"150.50",
             "qty":"10","position_qty":"10","timestamp":"2024-01-15T14:30:02Z",
             "order":{"id":"order-id-1","symbol":"AAPL","side":"buy",
                      "type":"limit","status":"filled","extended_hours":false}}
            """;
    TradeUpdate u = GSON.fromJson(json, TradeUpdate.class);

    assertEquals("fill", u.event());
    assertEquals("exec-1", u.executionId());
    assertEquals("150.50", u.price());
    assertEquals("10", u.qty());
    assertEquals("10", u.positionQty());
    assertEquals("2024-01-15T14:30:02Z", u.timestamp());
    assertNotNull(u.order());
    assertEquals("order-id-1", u.order().id());
    assertEquals("AAPL", u.order().symbol());
  }
}
