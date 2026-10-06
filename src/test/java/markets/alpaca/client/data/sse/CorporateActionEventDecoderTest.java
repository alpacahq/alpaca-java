package markets.alpaca.client.data.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import markets.alpaca.client.openapi.data.model.CorporateActionEventCashDividend;
import markets.alpaca.client.openapi.data.model.CorporateActionEventType;
import org.junit.jupiter.api.Test;

class CorporateActionEventDecoderTest {

  private final CorporateActionEventDecoder decoder = new CorporateActionEventDecoder();

  @Test
  void registryCoversEveryGeneratedDiscriminatorAndSchema() {
    assertEquals(
        Set.of(CorporateActionEventType.values()),
        CorporateActionEventDecoder.supportedEventTypes());
    assertEquals(
        CorporateActionEventType.values().length,
        markets.alpaca.client.openapi.data.model.CorporateActionEvent.schemas.size());
  }

  @Test
  void decodesKnownDiscriminatorToConcreteGeneratedModel() throws Exception {
    var event = decoder.decode(cashDividendJson());

    assertInstanceOf(CorporateActionEventCashDividend.class, event.getActualInstance());
  }

  @Test
  void rejectsUnknownDiscriminator() {
    String json =
        cashDividendJson()
            .replace("cash_dividend_corporateaction_event", "future_corporateaction_event");

    assertThrows(IOException.class, () -> decoder.decode(json));
  }

  static String cashDividendJson() {
    return """
        {
          "action":"insert",
          "at":"2026-10-01T12:00:00Z",
          "event_id":"01K6G000000000000000000000",
          "region":"us",
          "event_type":"cash_dividend_corporateaction_event",
          "ca":{
            "id":"123e4567-e89b-12d3-a456-426614174000",
            "process_date":"2026-10-01",
            "cusip":"037833100",
            "ex_date":"2026-10-02",
            "foreign":false,
            "rate":0.25,
            "special":false,
            "symbol":"AAPL"
          }
        }
        """
        .replace("\n", "");
  }
}
