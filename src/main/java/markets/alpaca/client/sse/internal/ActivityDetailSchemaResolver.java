package markets.alpaca.client.sse.internal;

import java.util.Locale;
import markets.alpaca.client.openapi.trading.model.ActivityType;

/**
 * Maps Activity V2 type/subtype pairs to a {@link ActivityDetailSchema}.
 *
 * <p>Trading and Broker each have a {@code resolve} overload that switches on its own generated
 * {@code ActivityType}. A constant or rule that exists on only one API is changed in that overload.
 * Subtypes stay strings: the contract describes them as free-form and does not generate an enum.
 * {@code TRD} is an {@code ActivityTypeFilter} value used by the live activity stream; the decoders
 * handle it before calling this class. Decoders bind the returned schema to their own generated
 * model class.
 */
public final class ActivityDetailSchemaResolver {

  private ActivityDetailSchemaResolver() {}

  /** Trading activity types. A Trading-only constant or rule is changed in this method. */
  public static ActivityDetailSchema resolve(ActivityType type, String subtype) {
    if (type == null) return null;
    String detail = normalizeSubtype(subtype);
    return switch (type) {
      case ACATC -> ActivityDetailSchema.ACATC;
      case ACATS -> ActivityDetailSchema.ACATS;
      // CFEE has no dedicated detail schema; FEE avoids ambiguous system_date-only matches.
      case CFEE -> ActivityDetailSchema.FEE;
      case CGD -> oneOf(detail, "", "LTCG", "STCG") ? ActivityDetailSchema.CGD : null;
      // CSD has no dedicated detail schema; preserve it with the compatible CSW model.
      case CSD, CSW -> ActivityDetailSchema.CSW;
      case DIV ->
          switch (detail) {
            case "CDIV", "ROC" -> ActivityDetailSchema.CDIV;
            case "SDIV" -> ActivityDetailSchema.SDIV;
            case "SPD" -> ActivityDetailSchema.DIVSPD;
            default -> null;
          };
      case DIVROC -> ActivityDetailSchema.CDIV;
      // Tax-exempt dividends use the cash-dividend shape, which structurally ties with SPD.
      case DIVTXEX -> ActivityDetailSchema.CDIV;
      case DIVNRA -> ActivityDetailSchema.DIVNRA;
      case FEE ->
          oneOf(detail, "REG", "TAF", "LCT", "ORF", "OCC", "NRC", "NRV", "COM", "CAT")
              ? ActivityDetailSchema.FEE
              : null;
      case FOPT -> ActivityDetailSchema.FOPT;
      case FXTRD -> ActivityDetailSchema.FXTRD;
      case INT -> "FI".equals(detail) ? ActivityDetailSchema.FIXED_INCOME_INTEREST : null;
      case JNLC -> ActivityDetailSchema.JNLC;
      case JNLS -> ActivityDetailSchema.JNLS;
      case MA -> oneOf(detail, "CMA", "SMA", "SCMA") ? ActivityDetailSchema.MA : null;
      case MEM -> ActivityDetailSchema.MEM;
      case NC -> oneOf(detail, "SNC", "CNC", "SCNC") ? ActivityDetailSchema.NC : null;
      case OCT -> ActivityDetailSchema.OCT;
      case OPASN -> ActivityDetailSchema.OPASN;
      case OPCSH -> ActivityDetailSchema.OPCSH;
      case OPEXC -> ActivityDetailSchema.OPEXC;
      case OPEXP -> ActivityDetailSchema.OPEXP;
      case OPTRD -> ActivityDetailSchema.OPTRD;
      case REO -> oneOf(detail, "REOS", "REOC", "REOSC") ? ActivityDetailSchema.REO : null;
      case REORG -> "WRM".equals(detail) ? ActivityDetailSchema.WRM : null;
      case SPIN -> ActivityDetailSchema.SPINOFF;
      case SPLIT ->
          switch (detail) {
            case "FSPLIT" -> ActivityDetailSchema.FORWARD_SPLIT;
            case "RSPLIT" -> ActivityDetailSchema.REVERSE_SPLIT;
            case "USPLIT" -> ActivityDetailSchema.UNIT_SPLIT;
            default -> null;
          };
      case VOF ->
          switch (detail) {
            case "VTND" -> ActivityDetailSchema.TENDER_OFFER;
            case "VWRT" -> ActivityDetailSchema.WARRANT_EXERCISE;
            case "VRGT" -> ActivityDetailSchema.RIGHTS_SUBSCRIPTION;
            case "VEXH" -> ActivityDetailSchema.EXCHANGE_OFFER;
            default -> null;
          };
      case WH -> oneOf(detail, "SWH", "FWH", "SLWH") ? ActivityDetailSchema.DIVWH : null;
      case OPCA ->
          switch (detail) {
            case "DIV.CDIV", "DIV.ROC" -> ActivityDetailSchema.OPCA_CDIV;
            case "DIV.SDIV" -> ActivityDetailSchema.OPCA_SDIV;
            case "MA.CMA", "MA.SMA", "MA.SCMA" -> ActivityDetailSchema.OPCA_MA;
            case "NC.CNC", "NC.SNC", "NC.SCNC" -> ActivityDetailSchema.OPCA_NC;
            case "SPIN" -> ActivityDetailSchema.OPCA_SPINOFF;
            case "CGD.LTCG", "CGD.STCG" -> ActivityDetailSchema.OPCA_CGD;
            case "SPLIT.FSPLIT" -> ActivityDetailSchema.OPCA_FORWARD_SPLIT;
            case "SPLIT.RSPLIT" -> ActivityDetailSchema.OPCA_REVERSE_SPLIT;
            case "SPLIT.USPLIT" -> ActivityDetailSchema.OPCA_UNIT_SPLIT;
            default -> null;
          };
      // No dedicated detail schema: decoders use structural fallback. Add new constants to the
      // mapped cases above or to this list so the compiler flags unhandled generated types.
      case FILL,
          ALLOC,
          CIL,
          CORR,
          CSR,
          DIVCGL,
          DIVCGS,
          DIVFEE,
          DIVFT,
          DIVTW,
          DIVWH,
          DTCS,
          DVF,
          ECEXP,
          EXTRD,
          FIMAT,
          FXCNV,
          INTNRA,
          INTTW,
          INVPNL,
          JNL,
          PTC,
          PTR,
          SC,
          SEG,
          SSO,
          SSP,
          SUB ->
          null;
    };
  }

  /** Broker activity types. A Broker-only constant or rule is changed in this method. */
  public static ActivityDetailSchema resolve(
      markets.alpaca.client.openapi.broker.model.ActivityType type, String subtype) {
    if (type == null) return null;
    String detail = normalizeSubtype(subtype);
    return switch (type) {
      case ACATC -> ActivityDetailSchema.ACATC;
      case ACATS -> ActivityDetailSchema.ACATS;
      // CFEE has no dedicated detail schema; FEE avoids ambiguous system_date-only matches.
      case CFEE -> ActivityDetailSchema.FEE;
      case CGD -> oneOf(detail, "", "LTCG", "STCG") ? ActivityDetailSchema.CGD : null;
      // CSD has no dedicated detail schema; preserve it with the compatible CSW model.
      case CSD, CSW -> ActivityDetailSchema.CSW;
      case DIV ->
          switch (detail) {
            case "CDIV", "ROC" -> ActivityDetailSchema.CDIV;
            case "SDIV" -> ActivityDetailSchema.SDIV;
            case "SPD" -> ActivityDetailSchema.DIVSPD;
            default -> null;
          };
      case DIVROC -> ActivityDetailSchema.CDIV;
      // Tax-exempt dividends use the cash-dividend shape, which structurally ties with SPD.
      case DIVTXEX -> ActivityDetailSchema.CDIV;
      case DIVNRA -> ActivityDetailSchema.DIVNRA;
      case FEE ->
          oneOf(detail, "REG", "TAF", "LCT", "ORF", "OCC", "NRC", "NRV", "COM", "CAT")
              ? ActivityDetailSchema.FEE
              : null;
      case FOPT -> ActivityDetailSchema.FOPT;
      case FXTRD -> ActivityDetailSchema.FXTRD;
      case INT -> "FI".equals(detail) ? ActivityDetailSchema.FIXED_INCOME_INTEREST : null;
      case JNLC -> ActivityDetailSchema.JNLC;
      case JNLS -> ActivityDetailSchema.JNLS;
      case MA -> oneOf(detail, "CMA", "SMA", "SCMA") ? ActivityDetailSchema.MA : null;
      case MEM -> ActivityDetailSchema.MEM;
      case NC -> oneOf(detail, "SNC", "CNC", "SCNC") ? ActivityDetailSchema.NC : null;
      case OCT -> ActivityDetailSchema.OCT;
      case OPASN -> ActivityDetailSchema.OPASN;
      case OPCSH -> ActivityDetailSchema.OPCSH;
      case OPEXC -> ActivityDetailSchema.OPEXC;
      case OPEXP -> ActivityDetailSchema.OPEXP;
      case OPTRD -> ActivityDetailSchema.OPTRD;
      case REO -> oneOf(detail, "REOS", "REOC", "REOSC") ? ActivityDetailSchema.REO : null;
      case REORG -> "WRM".equals(detail) ? ActivityDetailSchema.WRM : null;
      case SPIN -> ActivityDetailSchema.SPINOFF;
      case SPLIT ->
          switch (detail) {
            case "FSPLIT" -> ActivityDetailSchema.FORWARD_SPLIT;
            case "RSPLIT" -> ActivityDetailSchema.REVERSE_SPLIT;
            case "USPLIT" -> ActivityDetailSchema.UNIT_SPLIT;
            default -> null;
          };
      case VOF ->
          switch (detail) {
            case "VTND" -> ActivityDetailSchema.TENDER_OFFER;
            case "VWRT" -> ActivityDetailSchema.WARRANT_EXERCISE;
            case "VRGT" -> ActivityDetailSchema.RIGHTS_SUBSCRIPTION;
            case "VEXH" -> ActivityDetailSchema.EXCHANGE_OFFER;
            default -> null;
          };
      case WH -> oneOf(detail, "SWH", "FWH", "SLWH") ? ActivityDetailSchema.DIVWH : null;
      case OPCA ->
          switch (detail) {
            case "DIV.CDIV", "DIV.ROC" -> ActivityDetailSchema.OPCA_CDIV;
            case "DIV.SDIV" -> ActivityDetailSchema.OPCA_SDIV;
            case "MA.CMA", "MA.SMA", "MA.SCMA" -> ActivityDetailSchema.OPCA_MA;
            case "NC.CNC", "NC.SNC", "NC.SCNC" -> ActivityDetailSchema.OPCA_NC;
            case "SPIN" -> ActivityDetailSchema.OPCA_SPINOFF;
            case "CGD.LTCG", "CGD.STCG" -> ActivityDetailSchema.OPCA_CGD;
            case "SPLIT.FSPLIT" -> ActivityDetailSchema.OPCA_FORWARD_SPLIT;
            case "SPLIT.RSPLIT" -> ActivityDetailSchema.OPCA_REVERSE_SPLIT;
            case "SPLIT.USPLIT" -> ActivityDetailSchema.OPCA_UNIT_SPLIT;
            default -> null;
          };
      // No dedicated detail schema: decoders use structural fallback. Add new constants to the
      // mapped cases above or to this list so the compiler flags unhandled generated types.
      case FILL,
          ALLOC,
          CIL,
          CORR,
          CSR,
          DIVCGL,
          DIVCGS,
          DIVFEE,
          DIVFT,
          DIVTW,
          DIVWH,
          DTCS,
          DVF,
          ECEXP,
          EXTRD,
          FIMAT,
          FXCNV,
          INTNRA,
          INTTW,
          INVPNL,
          JNL,
          PTC,
          PTR,
          SC,
          SEG,
          SSO,
          SSP,
          SUB ->
          null;
    };
  }

  /**
   * Returns the subtype in upper case, or an empty string when absent, so it is safe to switch on.
   */
  private static String normalizeSubtype(String subtype) {
    return subtype == null ? "" : subtype.toUpperCase(Locale.ROOT);
  }

  private static boolean oneOf(String value, String... expected) {
    for (String candidate : expected) {
      if (candidate.equals(value)) return true;
    }
    return false;
  }
}
