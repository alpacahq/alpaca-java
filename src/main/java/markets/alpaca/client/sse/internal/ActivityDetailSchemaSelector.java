package markets.alpaca.client.sse.internal;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Selects a unique generated detail model by the number of recognized JSON fields. */
public final class ActivityDetailSchemaSelector {

  private static final Map<Class<?>, Set<String>> FIELDS = new ConcurrentHashMap<>();

  private ActivityDetailSchemaSelector() {}

  public static String uniqueMostSpecific(
      JsonObject details, List<String> matches, Map<String, Class<?>> schemas) {
    String selected = null;
    int selectedScore = -1;
    boolean tied = false;
    for (String schema : matches) {
      Class<?> modelClass = schemas.get(schema);
      if (modelClass == null) continue;
      Set<String> fields = FIELDS.computeIfAbsent(modelClass, ActivityDetailSchemaSelector::fields);
      int score = (int) details.keySet().stream().filter(fields::contains).count();
      if (score > selectedScore) {
        selected = schema;
        selectedScore = score;
        tied = false;
      } else if (score == selectedScore) {
        tied = true;
      }
    }
    return tied ? null : selected;
  }

  private static Set<String> fields(Class<?> modelClass) {
    try {
      Field field = modelClass.getField("openapiFields");
      Object value = field.get(null);
      if (value instanceof Set<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
      }
    } catch (ReflectiveOperationException ignored) {
      // Generated models are expected to expose openapiFields; an absent field scores as empty.
    }
    return Set.of();
  }
}
