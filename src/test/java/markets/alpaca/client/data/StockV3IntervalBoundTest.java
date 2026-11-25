package markets.alpaca.client.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.data.http.ApiClient;
import markets.alpaca.client.openapi.data.model.StockV3BarsStartParameter;
import org.junit.jupiter.api.Test;

class StockV3IntervalBoundTest {

  @Test
  void startAndEndAcceptADateTimeOrADate() throws Exception {
    ApiClient client = new ApiClient();

    OffsetDateTime when = OffsetDateTime.parse("2026-09-23T09:30:01-04:00");
    LocalDate day = LocalDate.parse("2026-09-23");
    StockV3BarsStartParameter dateTime = new StockV3BarsStartParameter(when);
    StockV3BarsStartParameter date = new StockV3BarsStartParameter(day);

    assertEquals(when, dateTime.getOffsetDateTime());
    assertEquals(day, date.getLocalDate());
    assertEquals(client.parameterToString(when), client.parameterToString(dateTime));
    assertEquals(client.parameterToString(day), client.parameterToString(date));

    OffsetDateTime onTheMinute = OffsetDateTime.parse("2026-09-23T09:30:00-04:00");
    assertEquals(
        client.parameterToString(onTheMinute),
        client.parameterToString(new StockV3BarsStartParameter(onTheMinute)));

    assertInstanceOf(
        OffsetDateTime.class,
        StockV3BarsStartParameter.fromJson("\"2026-09-24T00:00:00Z\"").getActualInstance());
    assertInstanceOf(
        LocalDate.class, StockV3BarsStartParameter.fromJson("\"2026-09-24\"").getActualInstance());
  }
}
