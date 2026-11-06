package markets.alpaca.client.broker.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.RebalancerEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerPortfolioStatusChangedEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerRunCreatedEvent;
import markets.alpaca.client.openapi.broker.model.RebalancerRunStatusChangedEvent;

/** Discriminator-aware decoder for generated Broker rebalancer event models. */
final class BrokerRebalancerEventDecoder {

  private static final Map<String, String> SCHEMAS =
      Map.of(
          "portfolio.status_changed",
          "RebalancerPortfolioStatusChangedEvent",
          "run.created",
          "RebalancerRunCreatedEvent",
          "run.status_changed",
          "RebalancerRunStatusChangedEvent");

  static {
    if (!SCHEMAS.keySet().equals(generatedEventTypes())) {
      throw new ExceptionInInitializerError(
          "Broker rebalancer decoder registry does not match generated event types");
    }
    if (!Set.copyOf(SCHEMAS.values()).equals(Set.copyOf(RebalancerEvent.schemas.keySet()))) {
      throw new ExceptionInInitializerError(
          "Broker rebalancer decoder registry does not match generated oneOf schemas");
    }
  }

  RebalancerEvent decode(String data) throws IOException {
    JsonObject root;
    try {
      root = JsonParser.parseString(data).getAsJsonObject();
    } catch (RuntimeException failure) {
      throw new IOException("Broker rebalancer event must be a JSON object", failure);
    }
    JsonElement discriminator = root.get("event_type");
    if (discriminator == null || discriminator.isJsonNull() || !discriminator.isJsonPrimitive()) {
      throw new IOException("Broker rebalancer event is missing event_type");
    }

    String eventType = discriminator.getAsString();
    String schemaName = SCHEMAS.get(eventType);
    Class<?> modelClass = schemaName == null ? null : RebalancerEvent.schemas.get(schemaName);
    if (schemaName == null || modelClass == null) {
      throw new IOException("Unsupported Broker rebalancer event_type: " + eventType);
    }

    Object model = decodeConcrete(schemaName, modelClass, root);
    return new RebalancerEvent(model);
  }

  static Set<String> supportedEventTypes() {
    return Set.copyOf(SCHEMAS.keySet());
  }

  static Set<String> supportedSchemaNames() {
    return Set.copyOf(SCHEMAS.values());
  }

  private static Set<String> generatedEventTypes() {
    var types = new HashSet<String>();
    for (var value : RebalancerPortfolioStatusChangedEvent.EventTypeEnum.values()) {
      types.add(value.getValue());
    }
    for (var value : RebalancerRunCreatedEvent.EventTypeEnum.values()) {
      types.add(value.getValue());
    }
    for (var value : RebalancerRunStatusChangedEvent.EventTypeEnum.values()) {
      types.add(value.getValue());
    }
    return types;
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
