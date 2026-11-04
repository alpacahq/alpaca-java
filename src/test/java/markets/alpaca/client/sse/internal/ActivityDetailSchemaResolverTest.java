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
    assertEquals("CDIVActivityV2", ActivityDetailSchemaResolver.resolve("DIVTXEX", null));
    assertEquals("ForwardSplitActivityV2", ActivityDetailSchemaResolver.resolve("SPLIT", "FSPLIT"));
    assertEquals("CSWActivityV2", ActivityDetailSchemaResolver.resolve("CSD", null));
    // CFEE (crypto fee) has no dedicated detail schema in the pinned OAS; its payload matches
    // FEEActivityV2, so it is resolved explicitly rather than left to ambiguous structural
    // matching (see ActivityDetailSchemaResolver).
    assertEquals("FEEActivityV2", ActivityDetailSchemaResolver.resolve("CFEE", null));

    assertNull(ActivityDetailSchemaResolver.resolve("INT", "MGN"));
    assertNull(ActivityDetailSchemaResolver.resolve("DIV", null));
    assertNull(ActivityDetailSchemaResolver.resolve("SPLIT", "UNKNOWN"));
  }
}
