package markets.alpaca.client.sse.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import markets.alpaca.client.openapi.broker.model.ActivityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ActivityDetailSchemaResolverTest {

  /**
   * Activity types that exist on only one generated enum. Adding a name here records that the
   * difference is intentional.
   */
  private static final Set<String> TRADING_ONLY_ACTIVITY_TYPES = Set.of();

  private static final Set<String> BROKER_ONLY_ACTIVITY_TYPES = Set.of();

  /**
   * Type/subtype pairs that Trading and Broker resolve differently on purpose. The subtype is the
   * normalized form: upper case, with an absent subtype stored as an empty string.
   */
  private static final Set<Resolution> EXPLICIT_DEVIATIONS = Set.of();

  private static final List<String> SUBTYPE_PROBES = subtypeProbes();

  @ParameterizedTest(name = "{0}/{1} -> {2}")
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          ACATC, NULL, ACATC
          ACATS, NULL, ACATS
          CFEE, NULL, FEE
          CGD, NULL, CGD
          CGD, '', CGD
          CGD, LTCG, CGD
          CGD, STCG, CGD
          CSD, NULL, CSW
          CSW, NULL, CSW
          DIV, CDIV, CDIV
          DIV, ROC, CDIV
          DIV, roc, CDIV
          DIV, SDIV, SDIV
          DIV, SPD, DIVSPD
          DIVROC, NULL, CDIV
          DIVTXEX, NULL, CDIV
          DIVNRA, NULL, DIVNRA
          FEE, REG, FEE
          FEE, TAF, FEE
          FEE, LCT, FEE
          FEE, ORF, FEE
          FEE, OCC, FEE
          FEE, NRC, FEE
          FEE, NRV, FEE
          FEE, COM, FEE
          FEE, CAT, FEE
          FOPT, NULL, FOPT
          FXTRD, NULL, FXTRD
          INT, FI, FIXED_INCOME_INTEREST
          JNLC, NULL, JNLC
          JNLS, NULL, JNLS
          MA, CMA, MA
          MA, SMA, MA
          MA, SCMA, MA
          MEM, NULL, MEM
          NC, SNC, NC
          NC, CNC, NC
          NC, SCNC, NC
          OCT, NULL, OCT
          OPASN, NULL, OPASN
          OPCSH, NULL, OPCSH
          OPEXC, NULL, OPEXC
          OPEXP, NULL, OPEXP
          OPTRD, NULL, OPTRD
          REO, REOS, REO
          REO, REOC, REO
          REO, REOSC, REO
          REORG, WRM, WRM
          SPIN, NULL, SPINOFF
          SPLIT, FSPLIT, FORWARD_SPLIT
          SPLIT, RSPLIT, REVERSE_SPLIT
          SPLIT, USPLIT, UNIT_SPLIT
          VOF, VTND, TENDER_OFFER
          VOF, VWRT, WARRANT_EXERCISE
          VOF, VRGT, RIGHTS_SUBSCRIPTION
          VOF, VEXH, EXCHANGE_OFFER
          WH, SWH, DIVWH
          WH, FWH, DIVWH
          WH, SLWH, DIVWH
          OPCA, DIV.CDIV, OPCA_CDIV
          OPCA, DIV.ROC, OPCA_CDIV
          OPCA, DIV.SDIV, OPCA_SDIV
          OPCA, MA.CMA, OPCA_MA
          OPCA, MA.SMA, OPCA_MA
          OPCA, MA.SCMA, OPCA_MA
          OPCA, NC.CNC, OPCA_NC
          OPCA, NC.SNC, OPCA_NC
          OPCA, NC.SCNC, OPCA_NC
          OPCA, SPIN, OPCA_SPINOFF
          OPCA, CGD.LTCG, OPCA_CGD
          OPCA, CGD.STCG, OPCA_CGD
          OPCA, SPLIT.FSPLIT, OPCA_FORWARD_SPLIT
          OPCA, SPLIT.RSPLIT, OPCA_REVERSE_SPLIT
          OPCA, SPLIT.USPLIT, OPCA_UNIT_SPLIT
          """)
  void resolvesDocumentedMappingsToExpectedSchema(
      markets.alpaca.client.openapi.trading.model.ActivityType type,
      String subtype,
      ActivityDetailSchema expected) {
    assertBoth(type, subtype, expected);
  }

  @ParameterizedTest(name = "{0}/{1} uses structural fallback")
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          CGD, UNKNOWN
          DIV, NULL
          DIV, UNKNOWN
          FEE, NULL
          FEE, UNKNOWN
          INT, NULL
          INT, MGN
          MA, NULL
          MA, UNKNOWN
          NC, NULL
          NC, UNKNOWN
          REO, NULL
          REO, UNKNOWN
          REORG, NULL
          REORG, UNKNOWN
          SPLIT, NULL
          SPLIT, UNKNOWN
          VOF, NULL
          VOF, UNKNOWN
          WH, NULL
          WH, UNKNOWN
          OPCA, NULL
          OPCA, UNKNOWN
          """)
  void leavesUndocumentedSubtypesToStructuralFallback(
      markets.alpaca.client.openapi.trading.model.ActivityType type, String subtype) {
    assertBoth(type, subtype, null);
  }

  @ParameterizedTest
  @EnumSource(
      value = markets.alpaca.client.openapi.trading.model.ActivityType.class,
      names = {
        "FILL", "ALLOC", "CIL", "CORR", "CSR", "DIVCGL", "DIVCGS", "DIVFEE", "DIVFT", "DIVTW",
        "DIVWH", "DTCS", "DVF", "ECEXP", "EXTRD", "FIMAT", "FXCNV", "INTNRA", "INTTW", "INVPNL",
        "JNL", "PTC", "PTR", "SC", "SEG", "SSO", "SSP", "SUB"
      })
  void leavesTypesWithoutExplicitMappingsToDecoderFallback(
      markets.alpaca.client.openapi.trading.model.ActivityType type) {
    assertBoth(type, null, null);
    assertBoth(type, "FI", null);
  }

  @Test
  void leavesUnknownActivityTypesToStructuralFallback() {
    assertNull(
        ActivityDetailSchemaResolver.resolve(
            (markets.alpaca.client.openapi.trading.model.ActivityType) null, "FI"));
    assertNull(ActivityDetailSchemaResolver.resolve((ActivityType) null, "FI"));
  }

  @Test
  void brokerAndTradingResolutionsMatchUnlessExplicitlyExcepted() {
    Set<String> trading = names(markets.alpaca.client.openapi.trading.model.ActivityType.class);
    Set<String> broker = names(ActivityType.class);
    assertEquals(
        TRADING_ONLY_ACTIVITY_TYPES,
        onlyIn(trading, broker),
        "Trading-only activity types must be listed in TRADING_ONLY_ACTIVITY_TYPES");
    assertEquals(
        BROKER_ONLY_ACTIVITY_TYPES,
        onlyIn(broker, trading),
        "Broker-only activity types must be listed in BROKER_ONLY_ACTIVITY_TYPES");

    Set<String> shared = new TreeSet<>(trading);
    shared.retainAll(broker);
    List<String> mismatches = new ArrayList<>();
    Set<Resolution> observedDeviations = new TreeSet<>();
    for (String activityType : shared) {
      for (String subtype : SUBTYPE_PROBES) {
        ActivityDetailSchema tradingResult =
            ActivityDetailSchemaResolver.resolve(
                markets.alpaca.client.openapi.trading.model.ActivityType.valueOf(activityType),
                subtype);
        ActivityDetailSchema brokerResult =
            ActivityDetailSchemaResolver.resolve(ActivityType.valueOf(activityType), subtype);
        Resolution resolution = new Resolution(activityType, normalized(subtype));
        if (tradingResult != brokerResult) {
          observedDeviations.add(resolution);
          if (!EXPLICIT_DEVIATIONS.contains(resolution)) {
            mismatches.add(
                resolution
                    + " trading="
                    + tradingResult
                    + " broker="
                    + brokerResult
                    + "; add it to EXPLICIT_DEVIATIONS if this difference is intentional");
          }
        }
      }
    }
    for (Resolution deviation : EXPLICIT_DEVIATIONS) {
      if (!observedDeviations.contains(deviation)) {
        mismatches.add(
            deviation + " is listed in EXPLICIT_DEVIATIONS but both APIs agree; remove it");
      }
    }
    assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
  }

  private static void assertBoth(
      markets.alpaca.client.openapi.trading.model.ActivityType type,
      String subtype,
      ActivityDetailSchema expected) {
    assertEquals(
        expected, ActivityDetailSchemaResolver.resolve(type, subtype), () -> "trading " + type);
    assertEquals(
        expected,
        ActivityDetailSchemaResolver.resolve(ActivityType.valueOf(type.name()), subtype),
        () -> "broker " + type);
  }

  private static List<String> subtypeProbes() {
    List<String> probes = new ArrayList<>();
    probes.add(null);
    probes.addAll(
        List.of(
            "LTCG",
            "STCG",
            "CDIV",
            "ROC",
            "roc",
            "SDIV",
            "SPD",
            "REG",
            "TAF",
            "LCT",
            "ORF",
            "OCC",
            "NRC",
            "NRV",
            "COM",
            "CAT",
            "FI",
            "MGN",
            "CMA",
            "SMA",
            "SCMA",
            "SNC",
            "CNC",
            "SCNC",
            "REOS",
            "REOC",
            "REOSC",
            "WRM",
            "FSPLIT",
            "RSPLIT",
            "USPLIT",
            "VTND",
            "VWRT",
            "VRGT",
            "VEXH",
            "SWH",
            "FWH",
            "SLWH",
            "DIV.CDIV",
            "DIV.ROC",
            "DIV.SDIV",
            "MA.CMA",
            "MA.SMA",
            "MA.SCMA",
            "NC.CNC",
            "NC.SNC",
            "NC.SCNC",
            "SPIN",
            "CGD.LTCG",
            "CGD.STCG",
            "SPLIT.FSPLIT",
            "SPLIT.RSPLIT",
            "SPLIT.USPLIT",
            "UNKNOWN"));
    return Collections.unmodifiableList(probes);
  }

  private static String normalized(String subtype) {
    return subtype == null ? "" : subtype.toUpperCase(Locale.ROOT);
  }

  private static Set<String> names(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants())
        .map(Enum::name)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> onlyIn(Set<String> left, Set<String> right) {
    Set<String> difference = new TreeSet<>(left);
    difference.removeAll(right);
    return difference;
  }

  private record Resolution(String activityType, String subtype) implements Comparable<Resolution> {
    @Override
    public int compareTo(Resolution other) {
      int byType = activityType.compareTo(other.activityType);
      return byType != 0 ? byType : subtype.compareTo(other.subtype);
    }

    @Override
    public String toString() {
      return activityType + "/" + (subtype.isEmpty() ? "<none>" : subtype);
    }
  }
}
