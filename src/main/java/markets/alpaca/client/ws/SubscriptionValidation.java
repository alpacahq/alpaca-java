package markets.alpaca.client.ws;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class SubscriptionValidation {

  private SubscriptionValidation() {}

  static void addAll(Set<String> target, String valueName, String... values) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(values, valueName + " must not be null");
    for (String value : values) {
      target.add(validate(valueName, value));
    }
  }

  static void addAll(Set<String> target, String valueName, Collection<String> values) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(values, valueName + " must not be null");
    for (String value : values) {
      target.add(validate(valueName, value));
    }
  }

  static Set<String> immutableSet(String valueName, Collection<String> values) {
    Objects.requireNonNull(values, valueName + " must not be null");
    var copy = new LinkedHashSet<String>();
    addAll(copy, valueName, values);
    return Collections.unmodifiableSet(copy);
  }

  private static String validate(String valueName, String value) {
    if (value == null) {
      throw new IllegalArgumentException(valueName + " must not contain null values");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(valueName + " must not contain blank values");
    }
    return value;
  }
}
