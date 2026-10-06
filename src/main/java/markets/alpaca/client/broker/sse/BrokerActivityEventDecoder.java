package markets.alpaca.client.broker.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2AllOfDetails;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailNTA;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailTRD;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaResolver;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaSelector;
import markets.alpaca.client.sse.internal.GeneratedModelAdditionalProperties;

/** Discriminant-aware decoder for Broker Activity V2 generated models. */
final class BrokerActivityEventDecoder {

  ActivityEventV2 decode(String data) throws IOException {
    JsonObject root = JsonParser.parseString(data).getAsJsonObject();
    String type = string(root, "activity_type");
    String subtype = nullableString(root, "activity_subtype");
    JsonElement details = root.get("details");
    if (details == null || !details.isJsonObject()) {
      throw new IOException("Activity event details must be an object");
    }

    ActivityEventV2 event = commonFields(root);
    if ("FILL".equals(type)) {
      ActivityV2DetailTRD.validateJsonElement(details);
      event.setDetails(
          new ActivityEventV2AllOfDetails(
              JSON.getGson().fromJson(details, ActivityV2DetailTRD.class)));
      return event;
    }

    String schema = ActivityDetailSchemaResolver.resolve(type, subtype);
    if (schema == null) {
      List<String> matches =
          ActivityV2DetailNTA.schemas.keySet().stream()
              .filter(candidate -> matches(candidate, details))
              .toList();
      schema =
          ActivityDetailSchemaSelector.uniqueMostSpecific(
              details.getAsJsonObject(), matches, ActivityV2DetailNTA.schemas);
      if (schema == null) {
        throw new IOException(
            "Cannot uniquely decode "
                + type
                + "/"
                + subtype
                + " activity details; matches="
                + matches);
      }
    }

    Object detail = decodeDetail(schema, details);
    event.setDetails(new ActivityEventV2AllOfDetails(new ActivityV2DetailNTA(detail)));
    return event;
  }

  private static boolean matches(String schema, JsonElement details) {
    try {
      decodeDetail(schema, details);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Object decodeDetail(String schema, JsonElement details) throws IOException {
    Class<?> modelClass = ActivityV2DetailNTA.schemas.get(schema);
    if (modelClass == null) {
      throw new IOException("Unsupported Activity V2 detail schema: " + schema);
    }
    try {
      modelClass.getMethod("validateJsonElement", JsonElement.class).invoke(null, details);
      return JSON.getGson().fromJson(details, modelClass);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException ioFailure) throw ioFailure;
      if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw new IOException("Failed to validate Activity V2 detail schema " + schema, cause);
    } catch (ReflectiveOperationException failure) {
      throw new IOException("Cannot validate Activity V2 detail schema " + schema, failure);
    }
  }

  static Set<String> candidateSchemaNames() {
    return Set.copyOf(ActivityV2DetailNTA.schemas.keySet());
  }

  private static ActivityEventV2 commonFields(JsonObject root) throws IOException {
    ActivityEventV2 event = new ActivityEventV2();
    event.setAccountId(UUID.fromString(string(root, "account_id")));
    event.setActivityType(string(root, "activity_type"));
    event.setActivitySubtype(nullableString(root, "activity_subtype"));
    event.setAt(OffsetDateTime.parse(string(root, "at")));
    event.setCurrency(string(root, "currency"));
    event.setEventId(string(root, "event_id"));
    event.setExecutedAt(OffsetDateTime.parse(string(root, "executed_at")));
    event.setNetAmount(decimal(root, "net_amount"));
    event.setPreviousId(uuid(root, "previous_id"));
    event.setPrice(decimal(root, "price"));
    event.setQty(decimal(root, "qty"));
    event.setRefId(UUID.fromString(string(root, "ref_id")));
    event.setSettleDate(LocalDate.parse(string(root, "settle_date")));
    event.setStatus(string(root, "status"));
    event.setSwapFeeBps(decimal(root, "swap_fee_bps"));
    event.setSwapRate(decimal(root, "swap_rate"));
    GeneratedModelAdditionalProperties.copy(
        root, ActivityEventV2.openapiFields, JSON.getGson(), event::putAdditionalProperty);
    return event;
  }

  private static String string(JsonObject object, String name) throws IOException {
    String value = nullableString(object, name);
    if (value == null) throw new IOException("Activity event is missing " + name);
    return value;
  }

  private static String nullableString(JsonObject object, String name) {
    JsonElement value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private static BigDecimal decimal(JsonObject object, String name) {
    String value = nullableString(object, name);
    return value == null ? null : new BigDecimal(value);
  }

  private static UUID uuid(JsonObject object, String name) {
    String value = nullableString(object, name);
    return value == null ? null : UUID.fromString(value);
  }
}
