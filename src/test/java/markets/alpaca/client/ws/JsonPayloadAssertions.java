package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JsonPayloadAssertions {

  private JsonPayloadAssertions() {}

  static void assertStringProperty(String json, String property, String expected) {
    var object = parseObject(json);

    assertTrue(object.has(property), () -> "payload must contain " + property + ": " + json);
    assertEquals(expected, object.get(property).getAsString());
  }

  static void assertStringPayload(String json, String action, Map<String, String> properties) {
    var object = parseObject(json);

    assertEquals(1 + properties.size(), object.size(), () -> "unexpected payload fields: " + json);
    assertEquals(action, object.get("action").getAsString());
    properties.forEach((name, value) -> assertStringProperty(object, name, value));
  }

  static void assertDataStringPayload(String json, String action, Map<String, String> properties) {
    var object = parseObject(json);

    assertEquals(2, object.size(), () -> "unexpected payload fields: " + json);
    assertEquals(action, object.get("action").getAsString());
    assertTrue(object.has("data"), () -> "payload must contain data: " + json);

    var data = object.getAsJsonObject("data");
    assertEquals(properties.size(), data.size(), () -> "unexpected data fields: " + json);
    properties.forEach((name, value) -> assertStringProperty(data, name, value));
  }

  static void assertPayload(String json, String action, Map<String, List<String>> arrays) {
    var object = parseObject(json);

    assertEquals(1 + arrays.size(), object.size(), () -> "unexpected payload fields: " + json);
    assertEquals(action, object.get("action").getAsString());
    arrays.forEach((name, values) -> assertArrayProperty(object, name, values));
  }

  static void assertDataPayload(String json, String action, Map<String, List<String>> arrays) {
    var object = parseObject(json);

    assertEquals(2, object.size(), () -> "unexpected payload fields: " + json);
    assertEquals(action, object.get("action").getAsString());
    assertTrue(object.has("data"), () -> "payload must contain data: " + json);

    var data = object.getAsJsonObject("data");
    assertEquals(arrays.size(), data.size(), () -> "unexpected data fields: " + json);
    arrays.forEach((name, values) -> assertArrayProperty(data, name, values));
  }

  static void assertDataPayloadUnordered(
      String json, String action, Map<String, Set<String>> arrays) {
    var object = parseObject(json);

    assertEquals(2, object.size(), () -> "unexpected payload fields: " + json);
    assertEquals(action, object.get("action").getAsString());
    assertTrue(object.has("data"), () -> "payload must contain data: " + json);

    var data = object.getAsJsonObject("data");
    assertEquals(arrays.size(), data.size(), () -> "unexpected data fields: " + json);
    arrays.forEach((name, values) -> assertUnorderedArrayProperty(data, name, values));
  }

  private static JsonObject parseObject(String json) {
    var element = JsonParser.parseString(json);
    assertTrue(element.isJsonObject(), () -> "payload must be a JSON object: " + json);
    return element.getAsJsonObject();
  }

  private static void assertStringProperty(JsonObject object, String property, String expected) {
    assertTrue(object.has(property), () -> "payload must contain " + property + ": " + object);
    assertEquals(expected, object.get(property).getAsString());
  }

  private static void assertArrayProperty(
      JsonObject object, String property, List<String> expected) {
    assertTrue(object.has(property), () -> "payload must contain " + property + ": " + object);

    var actual = object.getAsJsonArray(property);
    assertEquals(expected.size(), actual.size(), () -> "wrong " + property + " size");
    for (int i = 0; i < expected.size(); i++) {
      int index = i;
      assertEquals(
          expected.get(index),
          actual.get(index).getAsString(),
          () -> "wrong " + property + "[" + index + "]");
    }
  }

  private static void assertUnorderedArrayProperty(
      JsonObject object, String property, Set<String> expected) {
    assertTrue(object.has(property), () -> "payload must contain " + property + ": " + object);

    var actualJson = object.getAsJsonArray(property);
    var actual = new ArrayList<String>();
    actualJson.forEach(element -> actual.add(element.getAsString()));

    assertEquals(expected.size(), actual.size(), () -> "wrong " + property + " size");
    assertEquals(expected, Set.copyOf(actual), () -> "wrong " + property + " members");
  }
}
