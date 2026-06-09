package markets.alpaca.client.examples;

import java.time.OffsetDateTime;
import java.util.List;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.openapi.data.api.NewsApi;
import markets.alpaca.client.openapi.data.model.News;
import markets.alpaca.client.openapi.data.model.NewsResp;
import markets.alpaca.client.rest.AlpacaPagination;

/** Example of using {@link AlpacaPagination} with a generated paginated endpoint. */
public final class PaginationExample {
  private PaginationExample() {}

  public static void main(String[] args) throws Exception {
    var credentials = ExampleSupport.tradingCredentials();
    var dataClient = AlpacaClientFactory.dataClient(credentials);
    var newsApi = new NewsApi(dataClient);

    String symbol = ExampleSupport.env("APCA_EXAMPLE_SYMBOL", "AAPL");
    OffsetDateTime start = OffsetDateTime.now().minusMonths(6);

    ExampleSupport.printSection("Paginated News");
    List<News> articles =
        AlpacaPagination.collectDataItems(
            pageToken ->
                newsApi.newsWithHttpInfo(start, null, "desc", symbol, 50, false, true, pageToken),
            NewsResp::getNextPageToken,
            NewsResp::getNews);

    System.out.printf("Fetched %d news articles for %s%n", articles.size(), symbol);
    articles.stream()
        .limit(10)
        .forEach(article -> System.out.printf("- %s%n", article.getHeadline()));
  }
}
