package markets.alpaca.client.broker.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Set;
import markets.alpaca.client.openapi.broker.model.RebalancerEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerPortfolioStatusChangedEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerRunCreatedEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerRunStatusChangedEvent;
import org.junit.jupiter.api.Test;

class BrokerRebalancerEventDecoderTest {

  private final BrokerRebalancerEventDecoder decoder = new BrokerRebalancerEventDecoder();

  @Test
  void registryCoversEveryGeneratedDiscriminatorAndSchema() {
    assertEquals(
        Set.of("portfolio.status_changed", "run.created", "run.status_changed"),
        BrokerRebalancerEventDecoder.supportedEventTypes());
    assertEquals(
        Set.copyOf(RebalancerEvent.schemas.keySet()),
        BrokerRebalancerEventDecoder.supportedSchemaNames());
  }

  @Test
  void decodesEveryKnownDiscriminatorToItsConcreteModel() throws Exception {
    var portfolio =
        assertInstanceOf(
            RebalancerPortfolioStatusChangedEvent.class,
            decoder.decode(portfolioStatusChangedJson()).getActualInstance());
    assertEquals("Balanced", portfolio.getPortfolio().getName());
    assertNull(portfolio.getPortfolio().getWeights().get(1).getSymbol());

    var created =
        assertInstanceOf(
            RebalancerRunCreatedEvent.class, decoder.decode(runCreatedJson()).getActualInstance());
    assertEquals("QUEUED", created.getRun().getStatus().getValue());
    assertNull(created.getRun().getReason());

    var changed =
        assertInstanceOf(
            RebalancerRunStatusChangedEvent.class,
            decoder.decode(runStatusChangedJson()).getActualInstance());
    assertEquals("COMPLETED_SUCCESS", changed.getRun().getStatus().getValue());
  }

  @Test
  void rejectsMissingUnknownAndSchemaInvalidDiscriminators() {
    assertThrows(
        IOException.class,
        () ->
            decoder.decode(
                portfolioStatusChangedJson()
                    .replace("\"event_type\":\"portfolio.status_changed\",", "")));
    assertThrows(IOException.class, () -> decoder.decode("{\"event_type\":\"future.event\"}"));
    assertThrows(
        IOException.class,
        () -> decoder.decode(runCreatedJson().replace("\"status\":\"QUEUED\",", "")));
  }

  static String portfolioStatusChangedJson() {
    return """
        {
          "at":"2026-10-05T14:30:00.123456Z",
          "event_id":"01K6Q6ZJ7M2X3T0E4B9N8RCW5D",
          "event_type":"portfolio.status_changed",
          "portfolio":{
            "cooldown_days":7,
            "created_at":"2026-09-01T12:00:00Z",
            "description":"60/40 with a cash sleeve",
            "id":"2d49d00e-ab1c-4014-89d8-70c5f64df2fc",
            "name":"Balanced",
            "rebalance_conditions":[
              {"percent":"5","sub_type":"absolute","type":"drift_band"},
              {"day":"15","sub_type":"monthly","type":"calendar"}
            ],
            "status":"active",
            "updated_at":"2026-10-05T14:30:00.123456Z",
            "weights":[
              {"percent":"60","symbol":"SPY","type":"asset"},
              {"percent":"40","symbol":null,"type":"cash"}
            ]
          }
        }
        """
        .replace("\n", "");
  }

  static String runCreatedJson() {
    return """
        {
          "at":"2026-10-05T14:31:00Z",
          "event_id":"01K6Q70B3D8H5S2V9F1G6KTMXE",
          "event_type":"run.created",
          "run":{
            "account_id":"6221407b-32b2-3e6b-a7fe-cd79e1b68ba3",
            "canceled_at":null,
            "completed_at":null,
            "created_at":"2026-10-05T14:31:00Z",
            "id":"bdc8e56f-cd91-448d-bb46-a4593cde2f95",
            "initiated_from":"system",
            "portfolio_id":"2d49d00e-ab1c-4014-89d8-70c5f64df2fc",
            "reason":null,
            "status":"QUEUED",
            "type":"full_rebalance",
            "updated_at":"2026-10-05T14:31:00Z"
          }
        }
        """
        .replace("\n", "");
  }

  static String runStatusChangedJson() {
    return """
        {
          "at":"2026-10-05T14:33:12Z",
          "event_id":"01K6Q74R9W2C7N4J0P3Y5HQZAB",
          "event_type":"run.status_changed",
          "run":{
            "account_id":"6221407b-32b2-3e6b-a7fe-cd79e1b68ba3",
            "canceled_at":null,
            "completed_at":"2026-10-05T14:33:12Z",
            "created_at":"2026-10-05T14:31:00Z",
            "id":"bdc8e56f-cd91-448d-bb46-a4593cde2f95",
            "initiated_from":"system",
            "portfolio_id":"2d49d00e-ab1c-4014-89d8-70c5f64df2fc",
            "reason":null,
            "status":"COMPLETED_SUCCESS",
            "type":"full_rebalance",
            "updated_at":"2026-10-05T14:33:12Z"
          }
        }
        """
        .replace("\n", "");
  }
}
