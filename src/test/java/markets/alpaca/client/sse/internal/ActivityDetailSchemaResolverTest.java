package markets.alpaca.client.sse.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ActivityDetailSchemaResolverTest {

  @Test
  void resolvesOnlyDocumentedSubtypeMappings() {
    assertEquals(
        "FixedIncomeInterestActivityV2", ActivityDetailSchemaResolver.resolve("INT", "FI"));
    assertEquals("CDIVActivityV2", ActivityDetailSchemaResolver.resolve("DIV", "ROC"));
    assertEquals("ForwardSplitActivityV2", ActivityDetailSchemaResolver.resolve("SPLIT", "FSPLIT"));

    assertNull(ActivityDetailSchemaResolver.resolve("INT", "MGN"));
    assertNull(ActivityDetailSchemaResolver.resolve("DIV", null));
    assertNull(ActivityDetailSchemaResolver.resolve("SPLIT", "UNKNOWN"));
    assertNull(ActivityDetailSchemaResolver.resolve("CSD", null));
    assertNull(ActivityDetailSchemaResolver.resolve("CFEE", null));
  }
}
