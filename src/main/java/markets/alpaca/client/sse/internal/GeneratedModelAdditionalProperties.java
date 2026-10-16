package markets.alpaca.client.sse.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Copies unknown JSON fields using the same value shapes as generated model adapters. */
public final class GeneratedModelAdditionalProperties {

  private GeneratedModelAdditionalProperties() {}

  public static void copy(
      JsonObject source,
      Set<String> knownFields,
      Gson gson,
      BiConsumer<String, Object> destination) {
    source.entrySet().stream()
        .filter(entry -> !knownFields.contains(entry.getKey()))
        .forEach(
            entry -> destination.accept(entry.getKey(), generatedValue(entry.getValue(), gson)));
  }

  private static Object generatedValue(JsonElement value, Gson gson) {
    if (value == null || value.isJsonNull()) return null;
    if (value.isJsonArray()) return gson.fromJson(value, List.class);
    if (value.isJsonObject()) return gson.fromJson(value, HashMap.class);

    JsonPrimitive primitive = value.getAsJsonPrimitive();
    if (primitive.isString()) return primitive.getAsString();
    if (primitive.isNumber()) return primitive.getAsNumber();
    if (primitive.isBoolean()) return primitive.getAsBoolean();
    throw new IllegalArgumentException("Unknown JSON primitive type: " + value);
  }
}
