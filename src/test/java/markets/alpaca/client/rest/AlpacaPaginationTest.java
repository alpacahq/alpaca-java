package markets.alpaca.client.rest;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlpacaPaginationTest {

  private record TestBody(String nextPageToken, List<String> items) {}

  @Test
  void dataPage_preservesHttpMetadataAndParsesRateLimitHeaders() {
    var limitValues = new ArrayList<>(List.of("200"));
    var headers = new LinkedHashMap<String, List<String>>();
    headers.put("X-RateLimit-Limit", limitValues);
    headers.put("x-ratelimit-remaining", List.of("199"));
    headers.put("X-RateLimit-Reset", List.of("1712345678"));
    headers.put("X-Request-Id", List.of("request-1"));

    var response =
        new markets.alpaca.client.openapi.data.http.ApiResponse<>(
            200, headers, new TestBody("next-token", List.of("a")));

    AlpacaPage<TestBody> page = AlpacaPagination.dataPage(response, TestBody::nextPageToken);
    limitValues.add("mutated");

    assertEquals(200, page.statusCode());
    assertEquals(List.of("200"), page.headers().get("X-RateLimit-Limit"));
    assertEquals(List.of("request-1"), page.headers("x-request-id"));
    assertEquals("request-1", page.header("X-REQUEST-ID").orElseThrow());
    assertEquals("next-token", page.nextPageToken());
    assertEquals("next-token", page.nextPageTokenOptional().orElseThrow());
    assertTrue(page.hasNextPage());

    assertEquals(200L, page.rateLimit().limit().orElseThrow());
    assertEquals(199L, page.rateLimit().remaining().orElseThrow());
    assertEquals(1712345678L, page.rateLimit().resetEpochSeconds().orElseThrow());
    assertEquals(Instant.ofEpochSecond(1712345678L), page.rateLimit().resetAt().orElseThrow());

    assertThrows(UnsupportedOperationException.class, () -> page.headers().put("x", List.of()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> page.headers().get("X-RateLimit-Limit").add("x"));
    assertThrows(UnsupportedOperationException.class, () -> page.headers("X-Request-Id").add("x"));
  }

  @Test
  void page_treatsNullBodyAndBlankTokenAsFinalPage() {
    var response = new AlpacaApiResponse<TestBody>(null, 204, null);

    AlpacaPage<TestBody> page = AlpacaPagination.page(response, TestBody::nextPageToken);

    assertNull(page.body());
    assertEquals(204, page.statusCode());
    assertFalse(page.hasNextPage());
    assertNull(page.nextPageToken());
    assertTrue(page.headers().isEmpty());
    assertTrue(page.rateLimit().limit().isEmpty());
  }

  @Test
  void brokerPageAndTradingPageAdaptGeneratedResponses() {
    var brokerResponse =
        new markets.alpaca.client.openapi.broker.http.ApiResponse<>(
            201, Map.of("x-broker", List.of("yes")), new TestBody("broker-next", List.of()));
    var tradingResponse =
        new markets.alpaca.client.openapi.trading.http.ApiResponse<>(
            202, Map.of("x-trading", List.of("yes")), new TestBody("trading-next", List.of()));

    AlpacaPage<TestBody> brokerPage =
        AlpacaPagination.brokerPage(brokerResponse, TestBody::nextPageToken);
    AlpacaPage<TestBody> tradingPage =
        AlpacaPagination.tradingPage(tradingResponse, TestBody::nextPageToken);

    assertEquals(201, brokerPage.statusCode());
    assertEquals("broker-next", brokerPage.nextPageToken());
    assertEquals(List.of("yes"), brokerPage.headers("X-Broker"));
    assertEquals(202, tradingPage.statusCode());
    assertEquals("trading-next", tradingPage.nextPageToken());
    assertEquals(List.of("yes"), tradingPage.headers("X-Trading"));
  }

  @Test
  void collectDataPagesPassesReturnedTokenUntilBlankToken() throws Exception {
    var requestedTokens = new ArrayList<String>();

    List<AlpacaPage<TestBody>> pages =
        AlpacaPagination.collectDataPages(
            pageToken -> {
              requestedTokens.add(pageToken);
              if (pageToken == null) {
                return new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                    200, Map.of(), new TestBody("token-2", List.of("first")));
              }
              return new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                  200, Map.of(), new TestBody("  ", List.of("second")));
            },
            TestBody::nextPageToken);

    assertEquals(2, pages.size());
    assertEquals(Arrays.asList(null, "token-2"), requestedTokens);
    assertEquals(List.of("first"), pages.get(0).body().items());
    assertEquals(List.of("second"), pages.get(1).body().items());
    assertFalse(pages.get(1).hasNextPage());
    assertThrows(UnsupportedOperationException.class, () -> pages.add(pages.get(0)));
  }

  @Test
  void collectDataItemsFlattensEachPageBody() throws Exception {
    List<String> items =
        AlpacaPagination.collectDataItems(
            pageToken -> {
              if (pageToken == null) {
                return new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                    200, Map.of(), new TestBody("next", List.of("a", "b")));
              }
              return new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                  200, Map.of(), new TestBody(null, List.of("c")));
            },
            TestBody::nextPageToken,
            TestBody::items);

    assertEquals(List.of("a", "b", "c"), items);
    assertThrows(UnsupportedOperationException.class, () -> items.add("d"));
  }

  @Test
  void collectBrokerPagesPassesReturnedTokenUntilNullToken() throws Exception {
    var requestedTokens = new ArrayList<String>();

    List<AlpacaPage<TestBody>> pages =
        AlpacaPagination.collectBrokerPages(
            pageToken -> {
              requestedTokens.add(pageToken);
              if (pageToken == null) {
                return new markets.alpaca.client.openapi.broker.http.ApiResponse<>(
                    200, Map.of(), new TestBody("broker-token", List.of("first")));
              }
              return new markets.alpaca.client.openapi.broker.http.ApiResponse<>(
                  200, Map.of(), new TestBody(null, List.of("second")));
            },
            TestBody::nextPageToken);

    assertEquals(Arrays.asList(null, "broker-token"), requestedTokens);
    assertEquals(List.of("first"), pages.get(0).body().items());
    assertEquals(List.of("second"), pages.get(1).body().items());
    assertFalse(pages.get(1).hasNextPage());
  }

  @Test
  void collectTradingItemsPassesReturnedTokenAndFlattensItems() throws Exception {
    var requestedTokens = new ArrayList<String>();

    List<String> items =
        AlpacaPagination.collectTradingItems(
            pageToken -> {
              requestedTokens.add(pageToken);
              if (pageToken == null) {
                return new markets.alpaca.client.openapi.trading.http.ApiResponse<>(
                    200, Map.of(), new TestBody("trading-token", List.of("a")));
              }
              return new markets.alpaca.client.openapi.trading.http.ApiResponse<>(
                  200, Map.of(), new TestBody(null, List.of("b", "c")));
            },
            TestBody::nextPageToken,
            TestBody::items);

    assertEquals(Arrays.asList(null, "trading-token"), requestedTokens);
    assertEquals(List.of("a", "b", "c"), items);
  }

  @Test
  void collectPagesThrowsWhenNextPageTokenRepeats() {
    var requestedTokens = new ArrayList<String>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AlpacaPagination.collectPages(
                    pageToken -> {
                      requestedTokens.add(pageToken);
                      return new AlpacaPage<>(
                          new TestBody("repeat", List.of()), 200, Map.of(), "repeat");
                    }));

    assertEquals(Arrays.asList(null, "repeat"), requestedTokens);
    assertTrue(thrown.getMessage().contains("repeated next_page_token"));
    assertTrue(thrown.getMessage().contains("repeat"));
  }

  @Test
  void collectPagesCanStopWhenNextPageTokenRepeats() throws Exception {
    var requestedTokens = new ArrayList<String>();
    AlpacaPaginationOptions options =
        AlpacaPaginationOptions.builder()
            .repeatedTokenAction(AlpacaPaginationOptions.RepeatedTokenAction.STOP)
            .build();

    List<AlpacaPage<TestBody>> pages =
        AlpacaPagination.collectPages(
            pageToken -> {
              requestedTokens.add(pageToken);
              return new AlpacaPage<>(
                  new TestBody("repeat", List.of(pageToken == null ? "first" : "second")),
                  200,
                  Map.of(),
                  "repeat");
            },
            options);

    assertEquals(Arrays.asList(null, "repeat"), requestedTokens);
    assertEquals(2, pages.size());
    assertEquals(List.of("first"), pages.get(0).body().items());
    assertEquals(List.of("second"), pages.get(1).body().items());
  }

  @Test
  void collectPagesThrowsWhenMaxPagesWouldBeExceeded() {
    AlpacaPaginationOptions options = AlpacaPaginationOptions.builder().maxPages(2).build();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AlpacaPagination.collectPages(
                    pageToken -> {
                      String nextToken = pageToken == null ? "page-2" : "page-3";
                      return new AlpacaPage<>(
                          new TestBody(nextToken, List.of()), 200, Map.of(), nextToken);
                    },
                    options));

    assertTrue(thrown.getMessage().contains("maxPages"));
    assertTrue(thrown.getMessage().contains("2"));
  }

  @Test
  void collectItemsThrowsWhenMaxItemsWouldBeExceeded() {
    AlpacaPaginationOptions options = AlpacaPaginationOptions.builder().maxItems(2).build();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AlpacaPagination.collectItems(
                    pageToken ->
                        new AlpacaPage<>(
                            new TestBody(null, List.of("a", "b", "c")), 200, Map.of(), null),
                    TestBody::items,
                    options));

    assertTrue(thrown.getMessage().contains("maxItems"));
    assertTrue(thrown.getMessage().contains("2"));
  }

  @Test
  void paginationOptionsRejectInvalidLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaPaginationOptions.builder().maxPages(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaPaginationOptions.builder().maxItems(0).build());
    assertThrows(
        NullPointerException.class,
        () -> AlpacaPaginationOptions.builder().repeatedTokenAction(null));
  }

  @Test
  void forEachPageVisitsEveryPageInOrder() throws Exception {
    var seenItems = new ArrayList<String>();

    AlpacaPagination.forEachPage(
        pageToken -> {
          if (pageToken == null) {
            return new AlpacaPage<>(
                new TestBody("manual-token", List.of("first")), 200, Map.of(), "manual-token");
          }
          return new AlpacaPage<>(new TestBody(null, List.of("second")), 200, Map.of(), null);
        },
        page -> seenItems.addAll(page.body().items()));

    assertEquals(List.of("first", "second"), seenItems);
  }

  @Test
  void collectPagesPropagatesFetcherException() {
    class TestException extends Exception {}
    var thrown = new TestException();

    TestException actual =
        assertThrows(
            TestException.class,
            () ->
                AlpacaPagination.collectPages(
                    pageToken -> {
                      throw thrown;
                    }));

    assertSame(thrown, actual);
  }

  @Test
  void collectPagesRejectsNullPageFromFetcher() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class, () -> AlpacaPagination.collectPages(token -> null));

    assertEquals("fetcher returned null", thrown.getMessage());
  }

  @Test
  void collectBrokerPagesPropagatesGeneratedApiException() {
    var exception = new markets.alpaca.client.openapi.broker.http.ApiException("broker failed");

    var thrown =
        assertThrows(
            markets.alpaca.client.openapi.broker.http.ApiException.class,
            () ->
                AlpacaPagination.collectBrokerPages(
                    pageToken -> {
                      throw exception;
                    },
                    TestBody::nextPageToken));

    assertSame(exception, thrown);
  }

  @Test
  void collectTradingItemsPropagatesGeneratedApiException() {
    var exception = new markets.alpaca.client.openapi.trading.http.ApiException("trading failed");

    var thrown =
        assertThrows(
            markets.alpaca.client.openapi.trading.http.ApiException.class,
            () ->
                AlpacaPagination.collectTradingItems(
                    pageToken -> {
                      throw exception;
                    },
                    TestBody::nextPageToken,
                    TestBody::items));

    assertSame(exception, thrown);
  }

  @Test
  void paginationHelpersRejectNullRequiredArguments() {
    var dataResponse =
        new markets.alpaca.client.openapi.data.http.ApiResponse<>(
            200, Map.of(), new TestBody(null, List.of()));

    assertThrows(
        NullPointerException.class, () -> AlpacaPagination.page(null, TestBody::nextPageToken));
    assertThrows(
        NullPointerException.class, () -> AlpacaPagination.dataPage(null, TestBody::nextPageToken));
    assertThrows(
        NullPointerException.class,
        () -> AlpacaPagination.brokerPage(null, TestBody::nextPageToken));
    assertThrows(
        NullPointerException.class,
        () -> AlpacaPagination.tradingPage(null, TestBody::nextPageToken));
    assertThrows(NullPointerException.class, () -> AlpacaPagination.dataPage(dataResponse, null));
    assertThrows(NullPointerException.class, () -> AlpacaPagination.collectPages(null));
    assertThrows(
        NullPointerException.class,
        () ->
            AlpacaPagination.forEachPage(
                pageToken -> new AlpacaPage<>(new TestBody(null, List.of()), 200, Map.of(), null),
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            AlpacaPagination.collectItems(
                pageToken -> new AlpacaPage<>(new TestBody(null, List.of()), 200, Map.of(), null),
                null));
    assertThrows(
        NullPointerException.class,
        () -> AlpacaPagination.collectDataPages(null, TestBody::nextPageToken));
  }

  @Test
  void rateLimitIgnoresMissingAndInvalidValues() {
    AlpacaRateLimit rateLimit =
        AlpacaRateLimit.from(
            Map.of(
                "X-RateLimit-Limit", List.of("not-a-number"),
                "X-RateLimit-Remaining", List.of(""),
                "X-RateLimit-Reset", List.of("123")));

    assertTrue(rateLimit.limit().isEmpty());
    assertTrue(rateLimit.remaining().isEmpty());
    assertEquals(123L, rateLimit.resetEpochSeconds().orElseThrow());
  }
}
