package markets.alpaca.client.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Helpers for Alpaca REST endpoints that return a {@code next_page_token}. */
public final class AlpacaPagination {

  private AlpacaPagination() {}

  @FunctionalInterface
  public interface PageFetcher<T, E extends Exception> {
    AlpacaPage<T> fetch(String pageToken) throws E;
  }

  @FunctionalInterface
  public interface PageConsumer<T, E extends Exception> {
    void accept(AlpacaPage<T> page) throws E;
  }

  @FunctionalInterface
  public interface BrokerPageResponseFetcher<T> {
    markets.alpaca.client.openapi.broker.http.ApiResponse<T> fetch(String pageToken)
        throws markets.alpaca.client.openapi.broker.http.ApiException;
  }

  @FunctionalInterface
  public interface DataPageResponseFetcher<T> {
    markets.alpaca.client.openapi.data.http.ApiResponse<T> fetch(String pageToken)
        throws markets.alpaca.client.openapi.data.http.ApiException;
  }

  @FunctionalInterface
  public interface TradingPageResponseFetcher<T> {
    markets.alpaca.client.openapi.trading.http.ApiResponse<T> fetch(String pageToken)
        throws markets.alpaca.client.openapi.trading.http.ApiException;
  }

  /** Adapts an {@link AlpacaApiResponse} and body token extractor into an {@link AlpacaPage}. */
  public static <T> AlpacaPage<T> page(
      AlpacaApiResponse<T> response, Function<? super T, String> nextPageTokenExtractor) {
    Objects.requireNonNull(response, "response must not be null");
    return new AlpacaPage<>(
        response.body(),
        response.statusCode(),
        response.headers(),
        extractNextPageToken(response.body(), nextPageTokenExtractor));
  }

  /** Adapts a Broker generated {@code ApiResponse<T>} into an {@link AlpacaPage}. */
  public static <T> AlpacaPage<T> brokerPage(
      markets.alpaca.client.openapi.broker.http.ApiResponse<T> response,
      Function<? super T, String> nextPageTokenExtractor) {
    Objects.requireNonNull(response, "response must not be null");
    return new AlpacaPage<>(
        response.getData(),
        response.getStatusCode(),
        response.getHeaders(),
        extractNextPageToken(response.getData(), nextPageTokenExtractor));
  }

  /** Adapts a Market Data generated {@code ApiResponse<T>} into an {@link AlpacaPage}. */
  public static <T> AlpacaPage<T> dataPage(
      markets.alpaca.client.openapi.data.http.ApiResponse<T> response,
      Function<? super T, String> nextPageTokenExtractor) {
    Objects.requireNonNull(response, "response must not be null");
    return new AlpacaPage<>(
        response.getData(),
        response.getStatusCode(),
        response.getHeaders(),
        extractNextPageToken(response.getData(), nextPageTokenExtractor));
  }

  /** Adapts a Trading generated {@code ApiResponse<T>} into an {@link AlpacaPage}. */
  public static <T> AlpacaPage<T> tradingPage(
      markets.alpaca.client.openapi.trading.http.ApiResponse<T> response,
      Function<? super T, String> nextPageTokenExtractor) {
    Objects.requireNonNull(response, "response must not be null");
    return new AlpacaPage<>(
        response.getData(),
        response.getStatusCode(),
        response.getHeaders(),
        extractNextPageToken(response.getData(), nextPageTokenExtractor));
  }

  /** Fetches every page by passing each returned next-page token to {@code fetcher}. */
  public static <T, E extends Exception> List<AlpacaPage<T>> collectPages(PageFetcher<T, E> fetcher)
      throws E {
    return collectPages(fetcher, AlpacaPaginationOptions.defaults());
  }

  /** Fetches pages by passing each returned next-page token to {@code fetcher}. */
  public static <T, E extends Exception> List<AlpacaPage<T>> collectPages(
      PageFetcher<T, E> fetcher, AlpacaPaginationOptions options) throws E {
    List<AlpacaPage<T>> pages = new ArrayList<>();
    forEachPage(fetcher, pages::add, options);
    return Collections.unmodifiableList(pages);
  }

  /** Fetches every page and invokes {@code consumer} for each one. */
  public static <T, E extends Exception> void forEachPage(
      PageFetcher<T, E> fetcher, PageConsumer<T, E> consumer) throws E {
    forEachPage(fetcher, consumer, AlpacaPaginationOptions.defaults());
  }

  /** Fetches pages and invokes {@code consumer} for each one. */
  public static <T, E extends Exception> void forEachPage(
      PageFetcher<T, E> fetcher, PageConsumer<T, E> consumer, AlpacaPaginationOptions options)
      throws E {
    Objects.requireNonNull(fetcher, "fetcher must not be null");
    Objects.requireNonNull(consumer, "consumer must not be null");
    Objects.requireNonNull(options, "options must not be null");

    String pageToken = null;
    int pageCount = 0;
    Set<String> returnedTokens = new HashSet<>();
    do {
      AlpacaPage<T> page =
          Objects.requireNonNull(fetcher.fetch(pageToken), "fetcher returned null");
      consumer.accept(page);
      pageCount++;
      pageToken = page.nextPageToken();
      if (pageToken != null) {
        if (!returnedTokens.add(pageToken)) {
          pageToken = handleRepeatedToken(pageToken, options);
        }
        enforceMaxPages(options, pageCount, pageToken);
      }
    } while (pageToken != null);
  }

  /** Fetches every page and flattens each page body's items into one list. */
  public static <T, I, E extends Exception> List<I> collectItems(
      PageFetcher<T, E> fetcher,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor)
      throws E {
    return collectItems(fetcher, itemExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches pages and flattens each page body's items into one list. */
  public static <T, I, E extends Exception> List<I> collectItems(
      PageFetcher<T, E> fetcher,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor,
      AlpacaPaginationOptions options)
      throws E {
    Objects.requireNonNull(itemExtractor, "itemExtractor must not be null");
    Objects.requireNonNull(options, "options must not be null");
    List<I> items = new ArrayList<>();
    forEachPage(
        fetcher,
        page -> {
          Collection<? extends I> pageItems = itemExtractor.apply(page.body());
          if (pageItems != null) {
            enforceMaxItems(options, items.size(), pageItems.size());
            items.addAll(pageItems);
          }
        },
        options);
    return Collections.unmodifiableList(items);
  }

  /** Fetches all Broker pages using a generated {@code *WithHttpInfo} call. */
  public static <T> List<AlpacaPage<T>> collectBrokerPages(
      BrokerPageResponseFetcher<T> fetcher, Function<? super T, String> nextPageTokenExtractor)
      throws markets.alpaca.client.openapi.broker.http.ApiException {
    return collectBrokerPages(fetcher, nextPageTokenExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Broker pages using a generated {@code *WithHttpInfo} call and options. */
  public static <T> List<AlpacaPage<T>> collectBrokerPages(
      BrokerPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.broker.http.ApiException {
    Objects.requireNonNull(fetcher, "fetcher must not be null");
    return collectPages(
        pageToken -> brokerPage(fetcher.fetch(pageToken), nextPageTokenExtractor), options);
  }

  /** Fetches all Market Data pages using a generated {@code *WithHttpInfo} call. */
  public static <T> List<AlpacaPage<T>> collectDataPages(
      DataPageResponseFetcher<T> fetcher, Function<? super T, String> nextPageTokenExtractor)
      throws markets.alpaca.client.openapi.data.http.ApiException {
    return collectDataPages(fetcher, nextPageTokenExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Market Data pages using a generated {@code *WithHttpInfo} call and options. */
  public static <T> List<AlpacaPage<T>> collectDataPages(
      DataPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.data.http.ApiException {
    Objects.requireNonNull(fetcher, "fetcher must not be null");
    return collectPages(
        pageToken -> dataPage(fetcher.fetch(pageToken), nextPageTokenExtractor), options);
  }

  /** Fetches all Trading pages using a generated {@code *WithHttpInfo} call. */
  public static <T> List<AlpacaPage<T>> collectTradingPages(
      TradingPageResponseFetcher<T> fetcher, Function<? super T, String> nextPageTokenExtractor)
      throws markets.alpaca.client.openapi.trading.http.ApiException {
    return collectTradingPages(fetcher, nextPageTokenExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Trading pages using a generated {@code *WithHttpInfo} call and options. */
  public static <T> List<AlpacaPage<T>> collectTradingPages(
      TradingPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.trading.http.ApiException {
    Objects.requireNonNull(fetcher, "fetcher must not be null");
    return collectPages(
        pageToken -> tradingPage(fetcher.fetch(pageToken), nextPageTokenExtractor), options);
  }

  /** Fetches all Broker pages and flattens each page body's items into one list. */
  public static <T, I> List<I> collectBrokerItems(
      BrokerPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor)
      throws markets.alpaca.client.openapi.broker.http.ApiException {
    return collectBrokerItems(
        fetcher, nextPageTokenExtractor, itemExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Broker pages and flattens items using pagination options. */
  public static <T, I> List<I> collectBrokerItems(
      BrokerPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.broker.http.ApiException {
    return collectItems(
        pageToken -> brokerPage(fetcher.fetch(pageToken), nextPageTokenExtractor),
        itemExtractor,
        options);
  }

  /** Fetches all Market Data pages and flattens each page body's items into one list. */
  public static <T, I> List<I> collectDataItems(
      DataPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor)
      throws markets.alpaca.client.openapi.data.http.ApiException {
    return collectDataItems(
        fetcher, nextPageTokenExtractor, itemExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Market Data pages and flattens items using pagination options. */
  public static <T, I> List<I> collectDataItems(
      DataPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.data.http.ApiException {
    return collectItems(
        pageToken -> dataPage(fetcher.fetch(pageToken), nextPageTokenExtractor),
        itemExtractor,
        options);
  }

  /** Fetches all Trading pages and flattens each page body's items into one list. */
  public static <T, I> List<I> collectTradingItems(
      TradingPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor)
      throws markets.alpaca.client.openapi.trading.http.ApiException {
    return collectTradingItems(
        fetcher, nextPageTokenExtractor, itemExtractor, AlpacaPaginationOptions.defaults());
  }

  /** Fetches Trading pages and flattens items using pagination options. */
  public static <T, I> List<I> collectTradingItems(
      TradingPageResponseFetcher<T> fetcher,
      Function<? super T, String> nextPageTokenExtractor,
      Function<? super T, ? extends Collection<? extends I>> itemExtractor,
      AlpacaPaginationOptions options)
      throws markets.alpaca.client.openapi.trading.http.ApiException {
    return collectItems(
        pageToken -> tradingPage(fetcher.fetch(pageToken), nextPageTokenExtractor),
        itemExtractor,
        options);
  }

  private static String handleRepeatedToken(String pageToken, AlpacaPaginationOptions options) {
    if (options.repeatedTokenAction() == AlpacaPaginationOptions.RepeatedTokenAction.STOP) {
      return null;
    }
    throw new IllegalStateException("pagination returned repeated next_page_token: " + pageToken);
  }

  private static void enforceMaxPages(
      AlpacaPaginationOptions options, int pageCount, String nextPageToken) {
    Integer maxPages = options.maxPagesLimit();
    if (maxPages != null && pageCount >= maxPages && nextPageToken != null) {
      throw new IllegalStateException(
          "pagination exceeded maxPages " + maxPages + " before reaching the final page");
    }
  }

  private static void enforceMaxItems(
      AlpacaPaginationOptions options, int currentItemCount, int nextPageItemCount) {
    Integer maxItems = options.maxItemsLimit();
    if (maxItems != null && currentItemCount + nextPageItemCount > maxItems) {
      throw new IllegalStateException(
          "pagination exceeded maxItems " + maxItems + " while collecting items");
    }
  }

  private static <T> String extractNextPageToken(
      T body, Function<? super T, String> nextPageTokenExtractor) {
    Objects.requireNonNull(nextPageTokenExtractor, "nextPageTokenExtractor must not be null");
    return body == null ? null : AlpacaPage.normalizePageToken(nextPageTokenExtractor.apply(body));
  }
}
