package markets.alpaca.client.data.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import markets.alpaca.client.openapi.data.http.JSON;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.openapi.data.model.CorporateActionEventType;

/** Discriminator-aware decoder for generated corporate-action event models. */
final class CorporateActionEventDecoder {

  private static final Map<CorporateActionEventType, String> SCHEMAS =
      Map.ofEntries(
          entry(
              CorporateActionEventType.CAPITAL_GAINS_DISTRIBUTION_CORPORATEACTION_EVENT,
              "CorporateActionEventCapitalGainsDistribution"),
          entry(
              CorporateActionEventType.CASH_DIVIDEND_CORPORATEACTION_EVENT,
              "CorporateActionEventCashDividend"),
          entry(
              CorporateActionEventType.CASH_MERGER_CORPORATEACTION_EVENT,
              "CorporateActionEventCashMerger"),
          entry(
              CorporateActionEventType.EQUITY_PARTIAL_CALL_CORPORATEACTION_EVENT,
              "CorporateActionEventEquityPartialCall"),
          entry(
              CorporateActionEventType.FORWARD_SPLIT_CORPORATEACTION_EVENT,
              "CorporateActionEventForwardSplit"),
          entry(
              CorporateActionEventType.NAME_CHANGE_CORPORATEACTION_EVENT,
              "CorporateActionEventNameChange"),
          entry(
              CorporateActionEventType.REDEMPTION_CORPORATEACTION_EVENT,
              "CorporateActionEventRedemption"),
          entry(
              CorporateActionEventType.REORGANIZATION_CORPORATEACTION_EVENT,
              "CorporateActionEventReorganization"),
          entry(
              CorporateActionEventType.REVERSE_SPLIT_CORPORATEACTION_EVENT,
              "CorporateActionEventReverseSplit"),
          entry(
              CorporateActionEventType.RIGHTS_DISTRIBUTION_CORPORATEACTION_EVENT,
              "CorporateActionEventRightsDistribution"),
          entry(
              CorporateActionEventType.SPIN_OFF_CORPORATEACTION_EVENT,
              "CorporateActionEventSpinOff"),
          entry(
              CorporateActionEventType.STOCK_AND_CASH_MERGER_CORPORATEACTION_EVENT,
              "CorporateActionEventStockAndCashMerger"),
          entry(
              CorporateActionEventType.STOCK_DIVIDEND_CORPORATEACTION_EVENT,
              "CorporateActionEventStockDividend"),
          entry(
              CorporateActionEventType.STOCK_MERGER_CORPORATEACTION_EVENT,
              "CorporateActionEventStockMerger"),
          entry(
              CorporateActionEventType.UNIT_SPLIT_CORPORATEACTION_EVENT,
              "CorporateActionEventUnitSplit"),
          entry(
              CorporateActionEventType.WORTHLESS_REMOVAL_CORPORATEACTION_EVENT,
              "CorporateActionEventWorthlessRemoval"));

  static {
    Set<CorporateActionEventType> generatedTypes = Set.of(CorporateActionEventType.values());
    if (!SCHEMAS.keySet().equals(generatedTypes)) {
      throw new ExceptionInInitializerError(
          "Corporate-action decoder registry does not match generated event types");
    }
    SCHEMAS.forEach(
        (eventType, schemaName) -> {
          if (CorporateActionEvent.schemas.get(schemaName) == null) {
            throw new ExceptionInInitializerError(
                "Corporate-action decoder schema is missing for " + eventType + ": " + schemaName);
          }
        });
  }

  CorporateActionEvent decode(String data) throws IOException {
    JsonObject root = JsonParser.parseString(data).getAsJsonObject();
    JsonElement discriminator = root.get("event_type");
    if (discriminator == null || discriminator.isJsonNull()) {
      throw new IOException("Corporate-action event is missing event_type");
    }

    CorporateActionEventType eventType;
    try {
      eventType = CorporateActionEventType.fromValue(discriminator.getAsString());
    } catch (IllegalArgumentException failure) {
      throw new IOException("Unsupported corporate-action event_type: " + discriminator, failure);
    }
    String schemaName = SCHEMAS.get(eventType);
    Class<?> modelClass = CorporateActionEvent.schemas.get(schemaName);
    if (schemaName == null || modelClass == null) {
      throw new IOException("Unsupported corporate-action event schema for " + eventType);
    }

    Object model = decodeConcrete(schemaName, modelClass, root);
    return new CorporateActionEvent(model);
  }

  static Set<CorporateActionEventType> supportedEventTypes() {
    return Set.copyOf(SCHEMAS.keySet());
  }

  private static Object decodeConcrete(String schemaName, Class<?> modelClass, JsonElement json)
      throws IOException {
    try {
      modelClass.getMethod("validateJsonElement", JsonElement.class).invoke(null, json);
      return JSON.getGson().fromJson(json, modelClass);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException ioFailure) throw ioFailure;
      if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw new IOException("Failed to validate " + schemaName, cause);
    } catch (ReflectiveOperationException failure) {
      throw new IOException("Cannot validate generated schema " + schemaName, failure);
    }
  }

  private static Map.Entry<CorporateActionEventType, String> entry(
      CorporateActionEventType eventType, String schemaName) {
    return Map.entry(eventType, schemaName);
  }
}
