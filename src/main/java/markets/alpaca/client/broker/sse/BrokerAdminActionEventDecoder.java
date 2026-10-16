package markets.alpaca.client.broker.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.AdminActionType;
import markets.alpaca.client.openapi.broker.model.SubscribeToAdminActionSSE200ResponseInner;

/** Discriminator-aware decoder for generated Broker admin-action event models. */
final class BrokerAdminActionEventDecoder {

  private static final Map<AdminActionType, String> SCHEMAS =
      Map.of(
          AdminActionType.LEGACY_NOTE_ADMIN_EVENT,
          "AdminActionLegacyNote",
          AdminActionType.LIQUIDATION_ADMIN_EVENT,
          "AdminActionLiquidation",
          AdminActionType.TRANSACTION_CANCEL_ADMIN_EVENT,
          "AdminActionTransactionCancel");

  static {
    if (!SCHEMAS.keySet().equals(Set.of(AdminActionType.values()))) {
      throw new ExceptionInInitializerError(
          "Broker admin-action decoder registry does not match generated event types");
    }
    if (!Set.copyOf(SCHEMAS.values())
        .equals(Set.copyOf(SubscribeToAdminActionSSE200ResponseInner.schemas.keySet()))) {
      throw new ExceptionInInitializerError(
          "Broker admin-action decoder registry does not match generated oneOf schemas");
    }
  }

  SubscribeToAdminActionSSE200ResponseInner decode(String data) throws IOException {
    JsonObject root;
    try {
      root = JsonParser.parseString(data).getAsJsonObject();
    } catch (RuntimeException failure) {
      throw new IOException("Broker admin-action event must be a JSON object", failure);
    }
    JsonElement discriminator = root.get("type");
    if (discriminator == null || discriminator.isJsonNull()) {
      throw new IOException("Broker admin-action event is missing type");
    }

    AdminActionType eventType;
    try {
      eventType = AdminActionType.fromValue(discriminator.getAsString());
    } catch (RuntimeException failure) {
      throw new IOException("Unsupported Broker admin-action type: " + discriminator, failure);
    }
    String schemaName = SCHEMAS.get(eventType);
    Class<?> modelClass = SubscribeToAdminActionSSE200ResponseInner.schemas.get(schemaName);
    if (schemaName == null || modelClass == null) {
      throw new IOException("Unsupported Broker admin-action schema for " + eventType);
    }

    Object model = decodeConcrete(schemaName, modelClass, root);
    return new SubscribeToAdminActionSSE200ResponseInner(model);
  }

  static Set<AdminActionType> supportedEventTypes() {
    return Set.copyOf(SCHEMAS.keySet());
  }

  static Set<String> supportedSchemaNames() {
    return Set.copyOf(SCHEMAS.values());
  }

  private static Object decodeConcrete(String schemaName, Class<?> modelClass, JsonElement json)
      throws IOException {
    try {
      modelClass.getMethod("validateJsonElement", JsonElement.class).invoke(null, json);
      return JSON.getGson().fromJson(json, modelClass);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException ioFailure) throw ioFailure;
      throw new IOException("Failed to validate " + schemaName, cause);
    } catch (ReflectiveOperationException failure) {
      throw new IOException("Cannot validate generated schema " + schemaName, failure);
    } catch (RuntimeException failure) {
      throw new IOException("Failed to decode " + schemaName, failure);
    }
  }
}
