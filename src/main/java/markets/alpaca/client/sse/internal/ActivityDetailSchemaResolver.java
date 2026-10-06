package markets.alpaca.client.sse.internal;

import java.util.Locale;

/** Maps Activity V2 type/subtype pairs to generated concrete detail schema names. */
public final class ActivityDetailSchemaResolver {

  private ActivityDetailSchemaResolver() {}

  public static String resolve(String type, String subtype) {
    if (type == null) return null;
    String detail = value(subtype);
    return switch (type.toUpperCase(Locale.ROOT)) {
      case "ACATC" -> "AcatcActivityV2";
      case "ACATS" -> "AcatsActivityV2";
      case "CGD" -> oneOf(detail, "", "LTCG", "STCG") ? "CGDActivityV2" : null;
      // The pinned OAS documents CSD but does not yet define a CSD detail schema. Preserve the
      // event with the structurally compatible CSW model until the contract supplies one.
      case "CSD", "CSW" -> "CSWActivityV2";
      case "DIV" ->
          switch (detail) {
            case "CDIV", "ROC" -> "CDIVActivityV2";
            case "SDIV" -> "SDIVActivityV2";
            case "SPD" -> "DIVSPDActivityV2";
            default -> null;
          };
      case "DIVROC" -> "CDIVActivityV2";
      case "DIVNRA" -> "DIVNRAActivityV2";
      case "FEE" ->
          oneOf(detail, "REG", "TAF", "LCT", "ORF", "OCC", "NRC", "NRV", "COM", "CAT")
              ? "FEEActivityV2"
              : null;
      case "FOPT" -> "FOPTActivityV2";
      case "INT" -> "FI".equals(detail) ? "FixedIncomeInterestActivityV2" : null;
      case "JNLC" -> "JNLCActivityV2";
      case "JNLS" -> "JNLSActivityV2";
      case "MA" -> oneOf(detail, "CMA", "SMA", "SCMA") ? "MAActivityV2" : null;
      case "MEM" -> "MEMActivityV2";
      case "NC" -> oneOf(detail, "SNC", "CNC", "SCNC") ? "NCActivityV2" : null;
      case "OCT" -> "OCTActivityV2";
      case "OPASN" -> "OPASNActivityV2";
      case "OPCSH" -> "OPCSHActivityV2";
      case "OPEXC" -> "OPEXCActivityV2";
      case "OPEXP" -> "OPEXPActivityV2";
      case "OPTRD" -> "OPTRDActivityV2";
      case "REO" -> oneOf(detail, "REOS", "REOC", "REOSC") ? "REOActivityV2" : null;
      case "REORG" -> "WRM".equals(detail) ? "WRMActivityV2" : null;
      case "SPIN" -> "SpinoffActivityV2";
      case "SPLIT" ->
          switch (detail) {
            case "FSPLIT" -> "ForwardSplitActivityV2";
            case "RSPLIT" -> "ReverseSplitActivityV2";
            case "USPLIT" -> "UnitSplitActivityV2";
            default -> null;
          };
      case "VOF" ->
          switch (detail) {
            case "VTND" -> "TenderOfferActivityV2";
            case "VWRT" -> "WarrantExerciseElectionActivityV2";
            case "VRGT" -> "RightsSubscriptionElectionActivityV2";
            case "VEXH" -> "ExchangeOfferActivityV2";
            default -> null;
          };
      case "WH" -> oneOf(detail, "SWH", "FWH", "SLWH") ? "DIVWHActivityV2" : null;
      case "OPCA" ->
          switch (detail) {
            case "DIV.CDIV", "DIV.ROC" -> "OpcaCDIVActivityV2";
            case "DIV.SDIV" -> "OpcaSDIVActivityV2";
            case "MA.CMA", "MA.SMA", "MA.SCMA" -> "OpcaMAActivityV2";
            case "NC.CNC", "NC.SNC", "NC.SCNC" -> "OpcaNCActivityV2";
            case "SPIN" -> "OpcaSPINActivityV2";
            case "SPLIT.FSPLIT" -> "OpcaFSPLITActivityV2";
            case "SPLIT.RSPLIT" -> "OpcaRSPLITActivityV2";
            case "SPLIT.USPLIT" -> "OpcaUSPLITActivityV2";
            default -> null;
          };
      default -> null;
    };
  }

  private static String value(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT);
  }

  private static boolean oneOf(String value, String... expected) {
    for (String candidate : expected) {
      if (candidate.equals(value)) return true;
    }
    return false;
  }
}
