package markets.alpaca.client.broker.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import markets.alpaca.client.openapi.broker.model.AdminActionLegacyNote;
import markets.alpaca.client.openapi.broker.model.AdminActionLiquidation;
import markets.alpaca.client.openapi.broker.model.AdminActionTransactionCancel;
import markets.alpaca.client.openapi.broker.model.AdminActionType;
import markets.alpaca.client.openapi.broker.model.SubscribeToAdminActionSSE200ResponseInner;
import org.junit.jupiter.api.Test;

class BrokerAdminActionEventDecoderTest {

  private final BrokerAdminActionEventDecoder decoder = new BrokerAdminActionEventDecoder();

  @Test
  void registryCoversEveryGeneratedDiscriminatorAndSchema() {
    assertEquals(
        Set.of(AdminActionType.values()), BrokerAdminActionEventDecoder.supportedEventTypes());
    assertEquals(
        Set.copyOf(SubscribeToAdminActionSSE200ResponseInner.schemas.keySet()),
        BrokerAdminActionEventDecoder.supportedSchemaNames());
  }

  @Test
  void decodesEveryKnownDiscriminatorToItsConcreteModel() throws Exception {
    assertInstanceOf(
        AdminActionLegacyNote.class,
        decoder.decode(adminActionJson("legacy_note_admin_event", "{}")).getActualInstance());
    assertInstanceOf(
        AdminActionLiquidation.class,
        decoder
            .decode(
                adminActionJson(
                    "liquidation_admin_event",
                    """
                    {
                      "available_qty":"0.0001",
                      "error":"",
                      "reason":"risk",
                      "requested_qty":"0.0001",
                      "symbol":"TSLA"
                    }
                    """))
            .getActualInstance());
    assertInstanceOf(
        AdminActionTransactionCancel.class,
        decoder
            .decode(
                adminActionJson(
                    "transaction_cancel_admin_event",
                    """
                    {
                      "entry_type":"JNLC",
                      "external_id":"external-1",
                      "transaction_id":"fdf18af2-142b-409e-8aad-d1731e276af0"
                    }
                    """))
            .getActualInstance());
  }

  @Test
  void preservesAdditionalPropertiesOnTheConcreteModel() throws Exception {
    var event = decoder.decode(adminActionJson("legacy_note_admin_event", "{}"));
    var legacyNote = assertInstanceOf(AdminActionLegacyNote.class, event.getActualInstance());

    assertEquals("future", legacyNote.getAdditionalProperty("future_text"));
    assertEquals(Map.of("enabled", true), legacyNote.getAdditionalProperty("future_nested"));
  }

  @Test
  void rejectsMissingUnknownAndSchemaInvalidDiscriminators() {
    assertThrows(
        IOException.class,
        () ->
            decoder.decode(
                adminActionJson("legacy_note_admin_event", "{}")
                    .replaceFirst("\"type\":\"legacy_note_admin_event\",", "")));
    assertThrows(
        IOException.class, () -> decoder.decode(adminActionJson("future_admin_event", "{}")));
    assertThrows(
        IOException.class,
        () ->
            decoder.decode(
                adminActionJson("liquidation_admin_event", "{}").replace("\"context\":{},", "")));
  }

  static String adminActionJson(String type, String context) {
    return """
        {
          "at":"2026-10-01T12:00:00Z",
          "belongs_to":{"kind":"account","id_reference":"account-1"},
          "category":"other",
          "context":%s,
          "correspondent":"LPCA",
          "created_by":{"kind":"admin","id_reference":"admin-1"},
          "event_id":"01K6G000000000000000000000",
          "note":"reviewed action",
          "type":"%s",
          "visibility":"internal",
          "future_text":"future",
          "future_nested":{"enabled":true}
        }
        """
        .formatted(context, type)
        .replace("\n", "");
  }
}
