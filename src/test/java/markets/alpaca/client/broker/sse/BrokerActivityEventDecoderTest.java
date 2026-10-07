package markets.alpaca.client.broker.sse;

import static org.junit.jupiter.api.Assertions.*;

import markets.alpaca.client.openapi.broker.model.ActivityEventV2;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailNTA;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailTRD;
import markets.alpaca.client.openapi.broker.model.CDIVActivityV2;
import markets.alpaca.client.openapi.broker.model.CGDActivityV2;
import markets.alpaca.client.openapi.broker.model.CSWActivityV2;
import markets.alpaca.client.openapi.broker.model.DIVSPDActivityV2;
import markets.alpaca.client.openapi.broker.model.FEEActivityV2;
import markets.alpaca.client.openapi.broker.model.FixedIncomeRedemptionActivityV2;
import markets.alpaca.client.openapi.broker.model.RightsDistributionActivityV2;
import org.junit.jupiter.api.Test;

class BrokerActivityEventDecoderTest {

  private final BrokerActivityEventDecoder decoder = new BrokerActivityEventDecoder();

  @Test
  void decodesTradeAndNonTradeDetailsWithoutGeneratedOneOfMatching() throws Exception {
    var fill = decoder.decode(eventJson("FILL", null, "{}"));
    assertEquals("123e4567-e89b-12d3-a456-426614174002", fill.getAccountId().toString());
    assertInstanceOf(ActivityV2DetailTRD.class, fill.getDetails().getActualInstance());

    var fee =
        decoder.decode(
            eventJson(
                "FEE",
                "REG",
                """
                {"system_date":"2026-10-01","parent_id":"123e4567-e89b-12d3-a456-426614174001"}
                """));
    var nta = assertInstanceOf(ActivityV2DetailNTA.class, fee.getDetails().getActualInstance());
    assertInstanceOf(FEEActivityV2.class, nta.getActualInstance());
  }

  @Test
  void discriminatesStructurallyOverlappingDividendDetails() throws Exception {
    assertDividend("DIV", "CDIV", CDIVActivityV2.class, dividendDetails(true));
    assertDividend("DIV", "SPD", DIVSPDActivityV2.class, dividendDetails(true));
    assertDividend("DIVROC", null, CDIVActivityV2.class, dividendDetails(true));
    assertDividend("DIVTXEX", null, CDIVActivityV2.class, dividendDetails(true));
    assertDividend("CGD", "LTCG", CGDActivityV2.class, dividendDetails(false));
  }

  @Test
  void rejectsMalformedTaxExemptDividendDetails() {
    assertThrows(
        IllegalArgumentException.class, () -> decoder.decode(eventJson("DIVTXEX", null, "{}")));
  }

  @Test
  void preservesUnknownEnvelopePropertiesUsingGeneratedValueShapes() throws Exception {
    var event =
        decoder.decode(
            eventJson(
                "FILL",
                null,
                "{}",
                """
                "future_string":"value",
                "future_number":12.5,
                "future_boolean":true,
                "future_array":["a",2],
                "future_object":{"nested":"value"},
                "future_null":null,
                """));

    assertEquals("value", event.getAdditionalProperty("future_string"));
    assertEquals(12.5, ((Number) event.getAdditionalProperty("future_number")).doubleValue());
    assertEquals(true, event.getAdditionalProperty("future_boolean"));
    assertInstanceOf(java.util.List.class, event.getAdditionalProperty("future_array"));
    assertInstanceOf(java.util.Map.class, event.getAdditionalProperty("future_object"));
    assertTrue(event.getAdditionalProperties().containsKey("future_null"));
    assertNull(event.getAdditionalProperty("future_null"));
  }

  @Test
  void preservesUnhandledEnvelopePropertyWhenRegenerationMarksItKnown() throws Exception {
    assertTrue(ActivityEventV2.openapiFields.add("future_known"));
    try {
      var event =
          decoder.decode(eventJson("FILL", null, "{}", "\"future_known\":\"regenerated-value\","));

      assertEquals("regenerated-value", event.getAdditionalProperty("future_known"));
    } finally {
      ActivityEventV2.openapiFields.remove("future_known");
    }
  }

  @Test
  void registryCoversEveryGeneratedNtaCandidate() {
    assertEquals(
        ActivityV2DetailNTA.schemas.keySet(), BrokerActivityEventDecoder.candidateSchemaNames());
    assertFalse(
        ActivityV2DetailNTA.schemas.containsKey("CSDActivityV2"),
        "Remove the temporary CSD-to-CSW mapping when generation provides CSDActivityV2");
    assertFalse(
        ActivityV2DetailNTA.schemas.containsKey("DIVTXEXActivityV2"),
        "Remove the temporary DIVTXEX-to-CDIV mapping when generation provides DIVTXEXActivityV2");
  }

  @Test
  void preservesCsdEventsThroughTemporaryCswCompatibilityModel() throws Exception {
    var event =
        decoder.decode(
            eventJson(
                "CSD",
                null,
                """
                {"system_date":"2026-10-01","deposit_source":"ach"}
                """));

    assertEquals("CSD", event.getActivityType());
    var nta = assertInstanceOf(ActivityV2DetailNTA.class, event.getDetails().getActualInstance());
    var details = assertInstanceOf(CSWActivityV2.class, nta.getActualInstance());
    assertEquals("ach", details.getAdditionalProperty("deposit_source"));
  }

  @Test
  void selectsUniqueMostSpecificSchemaWithoutDocumentedDiscriminants() throws Exception {
    assertFallbackDetail(
        """
        {"system_date":"2026-10-01","ca_id":"123e4567-e89b-12d3-a456-426614174010",
        "cash_payout":"100.00","cusip":"912810TM0","payment_date":"2026-10-02","qty":"10"}
        """,
        FixedIncomeRedemptionActivityV2.class);
    assertFallbackDetail(
        """
        {"system_date":"2026-10-01","position_date":"2026-09-30","new_cusip":"123456789",
        "new_qty":"20","new_symbol":"NEW","rate":"2","source_cusip":"987654321",
        "source_qty":"10","source_symbol":"OLD"}
        """,
        RightsDistributionActivityV2.class);
  }

  private void assertFallbackDetail(String details, Class<?> expected) throws Exception {
    var event = decoder.decode(eventJson("UNMAPPED", null, details.replace("\n", "")));
    var nta = assertInstanceOf(ActivityV2DetailNTA.class, event.getDetails().getActualInstance());
    assertInstanceOf(expected, nta.getActualInstance());
  }

  private void assertDividend(String type, String subtype, Class<?> expected, String details)
      throws Exception {
    var event = decoder.decode(eventJson(type, subtype, details));
    var nta = assertInstanceOf(ActivityV2DetailNTA.class, event.getDetails().getActualInstance());
    assertInstanceOf(expected, nta.getActualInstance());
  }

  private static String dividendDetails(boolean flags) {
    return """
        {"system_date":"2026-10-01","position_date":"2026-09-30","cash_payout":"1.25",
        "cusip":"037833100","entitled_qty":"10","rate":"0.125","symbol":"AAPL"%s}
        """
        .formatted(flags ? ",\"foreign\":\"false\",\"special\":\"false\"" : "")
        .replace("\n", "");
  }

  private static String eventJson(String type, String subtype, String details) {
    return eventJson(type, subtype, details, "");
  }

  private static String eventJson(
      String type, String subtype, String details, String additionalFields) {
    String subtypeField = subtype == null ? "" : "\"activity_subtype\":\"" + subtype + "\",";
    return """
        {
          %s
          %s
          "account_id":"123e4567-e89b-12d3-a456-426614174002",
          "activity_type":"%s",
          "at":"2026-10-01T12:00:00Z",
          "currency":"USD",
          "event_id":"01K6G000000000000000000000",
          "executed_at":"2026-10-01T12:00:00Z",
          "ref_id":"123e4567-e89b-12d3-a456-426614174000",
          "settle_date":"2026-10-02",
          "status":"executed",
          "details":%s
        }
        """
        .formatted(subtypeField, additionalFields, type, details);
  }
}
