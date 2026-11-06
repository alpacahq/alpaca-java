package markets.alpaca.client.trading.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import markets.alpaca.client.openapi.trading.http.JSON;
import markets.alpaca.client.openapi.trading.model.AcatcActivityV2;
import markets.alpaca.client.openapi.trading.model.AcatsActivityV2;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2AllOfDetails;
import markets.alpaca.client.openapi.trading.model.ActivityType;
import markets.alpaca.client.openapi.trading.model.ActivityTypeFilter;
import markets.alpaca.client.openapi.trading.model.ActivityV2DetailNTA;
import markets.alpaca.client.openapi.trading.model.ActivityV2DetailTRD;
import markets.alpaca.client.openapi.trading.model.CDIVActivityV2;
import markets.alpaca.client.openapi.trading.model.CGDActivityV2;
import markets.alpaca.client.openapi.trading.model.CSWActivityV2;
import markets.alpaca.client.openapi.trading.model.DIVNRAActivityV2;
import markets.alpaca.client.openapi.trading.model.DIVSPDActivityV2;
import markets.alpaca.client.openapi.trading.model.DIVWHActivityV2;
import markets.alpaca.client.openapi.trading.model.ExchangeOfferActivityV2;
import markets.alpaca.client.openapi.trading.model.FEEActivityV2;
import markets.alpaca.client.openapi.trading.model.FOPTActivityV2;
import markets.alpaca.client.openapi.trading.model.FXTRDActivityV2;
import markets.alpaca.client.openapi.trading.model.FixedIncomeInterestActivityV2;
import markets.alpaca.client.openapi.trading.model.FixedIncomeRedemptionActivityV2;
import markets.alpaca.client.openapi.trading.model.ForwardSplitActivityV2;
import markets.alpaca.client.openapi.trading.model.JNLCActivityV2;
import markets.alpaca.client.openapi.trading.model.JNLSActivityV2;
import markets.alpaca.client.openapi.trading.model.MAActivityV2;
import markets.alpaca.client.openapi.trading.model.MEMActivityV2;
import markets.alpaca.client.openapi.trading.model.NCActivityV2;
import markets.alpaca.client.openapi.trading.model.OCTActivityV2;
import markets.alpaca.client.openapi.trading.model.OPASNActivityV2;
import markets.alpaca.client.openapi.trading.model.OPCSHActivityV2;
import markets.alpaca.client.openapi.trading.model.OPEXCActivityV2;
import markets.alpaca.client.openapi.trading.model.OPEXPActivityV2;
import markets.alpaca.client.openapi.trading.model.OPTRDActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaCDIVActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaCGDActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaFSPLITActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaMAActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaNCActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaRSPLITActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaSDIVActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaSPINActivityV2;
import markets.alpaca.client.openapi.trading.model.OpcaUSPLITActivityV2;
import markets.alpaca.client.openapi.trading.model.REOActivityV2;
import markets.alpaca.client.openapi.trading.model.ReverseSplitActivityV2;
import markets.alpaca.client.openapi.trading.model.RightsDistributionActivityV2;
import markets.alpaca.client.openapi.trading.model.RightsSubscriptionElectionActivityV2;
import markets.alpaca.client.openapi.trading.model.SDIVActivityV2;
import markets.alpaca.client.openapi.trading.model.SpinoffActivityV2;
import markets.alpaca.client.openapi.trading.model.TenderOfferActivityV2;
import markets.alpaca.client.openapi.trading.model.UnitSplitActivityV2;
import markets.alpaca.client.openapi.trading.model.WRMActivityV2;
import markets.alpaca.client.openapi.trading.model.WarrantExerciseElectionActivityV2;
import markets.alpaca.client.sse.internal.ActivityDetailSchema;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaResolver;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaSelector;
import markets.alpaca.client.sse.internal.GeneratedModelAdditionalProperties;

/**
 * Decodes Trading activity events.
 *
 * <p>This class is kept separate from the transport because generated one-of adapters can change
 * independently when the pinned Trading specification is adopted.
 */
final class TradingActivityEventDecoder {

  private static final Set<String> HANDLED_ENVELOPE_FIELDS =
      Set.of(
          "activity_subtype",
          "activity_type",
          "at",
          "currency",
          "event_id",
          "executed_at",
          "net_amount",
          "previous_id",
          "price",
          "qty",
          "ref_id",
          "settle_date",
          "status",
          "swap_fee_bps",
          "swap_rate",
          "details");
  private static final List<Candidate> CANDIDATES =
      List.of(
          candidate(DIVSPDActivityV2.class, DIVSPDActivityV2::validateJsonElement),
          candidate(CDIVActivityV2.class, CDIVActivityV2::validateJsonElement),
          candidate(SDIVActivityV2.class, SDIVActivityV2::validateJsonElement),
          candidate(CGDActivityV2.class, CGDActivityV2::validateJsonElement),
          candidate(ForwardSplitActivityV2.class, ForwardSplitActivityV2::validateJsonElement),
          candidate(ReverseSplitActivityV2.class, ReverseSplitActivityV2::validateJsonElement),
          candidate(UnitSplitActivityV2.class, UnitSplitActivityV2::validateJsonElement),
          candidate(SpinoffActivityV2.class, SpinoffActivityV2::validateJsonElement),
          candidate(MAActivityV2.class, MAActivityV2::validateJsonElement),
          candidate(NCActivityV2.class, NCActivityV2::validateJsonElement),
          candidate(
              FixedIncomeRedemptionActivityV2.class,
              FixedIncomeRedemptionActivityV2::validateJsonElement),
          candidate(
              FixedIncomeInterestActivityV2.class,
              FixedIncomeInterestActivityV2::validateJsonElement),
          candidate(REOActivityV2.class, REOActivityV2::validateJsonElement),
          candidate(
              RightsDistributionActivityV2.class,
              RightsDistributionActivityV2::validateJsonElement),
          candidate(
              RightsSubscriptionElectionActivityV2.class,
              RightsSubscriptionElectionActivityV2::validateJsonElement),
          candidate(TenderOfferActivityV2.class, TenderOfferActivityV2::validateJsonElement),
          candidate(ExchangeOfferActivityV2.class, ExchangeOfferActivityV2::validateJsonElement),
          candidate(
              WarrantExerciseElectionActivityV2.class,
              WarrantExerciseElectionActivityV2::validateJsonElement),
          candidate(WRMActivityV2.class, WRMActivityV2::validateJsonElement),
          candidate(OpcaCDIVActivityV2.class, OpcaCDIVActivityV2::validateJsonElement),
          candidate(OpcaSDIVActivityV2.class, OpcaSDIVActivityV2::validateJsonElement),
          candidate(OpcaMAActivityV2.class, OpcaMAActivityV2::validateJsonElement),
          candidate(OpcaNCActivityV2.class, OpcaNCActivityV2::validateJsonElement),
          candidate(OpcaSPINActivityV2.class, OpcaSPINActivityV2::validateJsonElement),
          candidate(OpcaFSPLITActivityV2.class, OpcaFSPLITActivityV2::validateJsonElement),
          candidate(OpcaUSPLITActivityV2.class, OpcaUSPLITActivityV2::validateJsonElement),
          candidate(OpcaRSPLITActivityV2.class, OpcaRSPLITActivityV2::validateJsonElement),
          candidate(OpcaCGDActivityV2.class, OpcaCGDActivityV2::validateJsonElement),
          candidate(OPASNActivityV2.class, OPASNActivityV2::validateJsonElement),
          candidate(OPEXCActivityV2.class, OPEXCActivityV2::validateJsonElement),
          candidate(OPEXPActivityV2.class, OPEXPActivityV2::validateJsonElement),
          candidate(OPTRDActivityV2.class, OPTRDActivityV2::validateJsonElement),
          candidate(OPCSHActivityV2.class, OPCSHActivityV2::validateJsonElement),
          candidate(AcatsActivityV2.class, AcatsActivityV2::validateJsonElement),
          candidate(AcatcActivityV2.class, AcatcActivityV2::validateJsonElement),
          candidate(FOPTActivityV2.class, FOPTActivityV2::validateJsonElement),
          candidate(DIVNRAActivityV2.class, DIVNRAActivityV2::validateJsonElement),
          candidate(DIVWHActivityV2.class, DIVWHActivityV2::validateJsonElement),
          candidate(JNLSActivityV2.class, JNLSActivityV2::validateJsonElement),
          candidate(JNLCActivityV2.class, JNLCActivityV2::validateJsonElement),
          candidate(CSWActivityV2.class, CSWActivityV2::validateJsonElement),
          candidate(FEEActivityV2.class, FEEActivityV2::validateJsonElement),
          candidate(MEMActivityV2.class, MEMActivityV2::validateJsonElement),
          candidate(OCTActivityV2.class, OCTActivityV2::validateJsonElement),
          candidate(FXTRDActivityV2.class, FXTRDActivityV2::validateJsonElement));
  private static final Map<Class<?>, Candidate> CANDIDATES_BY_CLASS =
      CANDIDATES.stream()
          .collect(Collectors.toUnmodifiableMap(Candidate::modelClass, value -> value));

  static {
    for (ActivityDetailSchema schema : ActivityDetailSchema.values()) {
      if (!CANDIDATES_BY_CLASS.containsKey(modelClass(schema))) {
        throw new ExceptionInInitializerError("No trading detail model for " + schema);
      }
    }
  }

  ActivityEventV2 decode(String data) throws IOException {
    JsonObject root = JsonParser.parseString(data).getAsJsonObject();
    String activityType = string(root, "activity_type");
    String activitySubtype = nullableString(root, "activity_subtype");
    JsonElement details = root.get("details");
    if (details == null || !details.isJsonObject()) {
      throw new IOException("Activity event details must be an object");
    }

    ActivityEventV2 event = commonFields(root);
    // The REST activities endpoint labels fills FILL. The live Activity SSE endpoint labels
    // the same trade events TRD (documented on ActivityEventV2Asset: "Only present when
    // activity_type is TRD"). TRD is a filter value, not an ActivityType. Both route to the
    // same trade detail schema.
    if (ActivityType.FILL.getValue().equals(activityType)
        || ActivityTypeFilter.TRD.getValue().equals(activityType)) {
      ActivityV2DetailTRD.validateJsonElement(details);
      event.setDetails(
          new ActivityEventV2AllOfDetails(
              JSON.getGson().fromJson(details, ActivityV2DetailTRD.class)));
      return event;
    }

    Candidate selected = preferred(parseActivityType(activityType), activitySubtype);
    if (selected != null) {
      selected.validator().validate(details);
    } else {
      List<Candidate> matches =
          CANDIDATES.stream().filter(candidate -> candidate.matches(details)).toList();
      Class<?> selectedClass =
          ActivityDetailSchemaSelector.uniqueMostSpecific(
              details.getAsJsonObject(), matches.stream().map(Candidate::modelClass).toList());
      if (selectedClass == null) {
        throw new IOException(
            "Cannot uniquely decode "
                + activityType
                + "/"
                + activitySubtype
                + " activity details; matches="
                + matches.stream()
                    .map(candidate -> candidate.modelClass().getSimpleName())
                    .toList());
      }
      selected = CANDIDATES_BY_CLASS.get(selectedClass);
      if (selected == null) {
        throw new IOException("No trading detail candidate for " + selectedClass.getSimpleName());
      }
    }

    Object detail = JSON.getGson().fromJson(details, selected.modelClass());
    event.setDetails(new ActivityEventV2AllOfDetails(new ActivityV2DetailNTA(detail)));
    return event;
  }

  static Set<Class<?>> candidateClasses() {
    return CANDIDATES.stream().map(Candidate::modelClass).collect(Collectors.toUnmodifiableSet());
  }

  private static ActivityEventV2 commonFields(JsonObject root) throws IOException {
    ActivityEventV2 event = new ActivityEventV2();
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
        root, HANDLED_ENVELOPE_FIELDS, JSON.getGson(), event::putAdditionalProperty);
    return event;
  }

  private static ActivityType parseActivityType(String value) {
    if (value == null) return null;
    try {
      return ActivityType.fromValue(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static Candidate preferred(ActivityType type, String subtype) {
    ActivityDetailSchema schema = ActivityDetailSchemaResolver.resolve(type, subtype);
    if (schema == null) return null;
    Candidate candidate = CANDIDATES_BY_CLASS.get(modelClass(schema));
    if (candidate == null) {
      throw new IllegalStateException("No trading detail candidate for " + schema);
    }
    return candidate;
  }

  static Class<?> modelClass(ActivityDetailSchema schema) {
    return switch (schema) {
      case ACATC -> AcatcActivityV2.class;
      case ACATS -> AcatsActivityV2.class;
      case CDIV -> CDIVActivityV2.class;
      case CGD -> CGDActivityV2.class;
      case CSW -> CSWActivityV2.class;
      case DIVNRA -> DIVNRAActivityV2.class;
      case DIVSPD -> DIVSPDActivityV2.class;
      case DIVWH -> DIVWHActivityV2.class;
      case EXCHANGE_OFFER -> ExchangeOfferActivityV2.class;
      case FEE -> FEEActivityV2.class;
      case FIXED_INCOME_INTEREST -> FixedIncomeInterestActivityV2.class;
      case FOPT -> FOPTActivityV2.class;
      case FORWARD_SPLIT -> ForwardSplitActivityV2.class;
      case FXTRD -> FXTRDActivityV2.class;
      case JNLC -> JNLCActivityV2.class;
      case JNLS -> JNLSActivityV2.class;
      case MA -> MAActivityV2.class;
      case MEM -> MEMActivityV2.class;
      case NC -> NCActivityV2.class;
      case OCT -> OCTActivityV2.class;
      case OPASN -> OPASNActivityV2.class;
      case OPCA_CDIV -> OpcaCDIVActivityV2.class;
      case OPCA_CGD -> OpcaCGDActivityV2.class;
      case OPCA_FORWARD_SPLIT -> OpcaFSPLITActivityV2.class;
      case OPCA_MA -> OpcaMAActivityV2.class;
      case OPCA_NC -> OpcaNCActivityV2.class;
      case OPCA_REVERSE_SPLIT -> OpcaRSPLITActivityV2.class;
      case OPCA_SDIV -> OpcaSDIVActivityV2.class;
      case OPCA_SPINOFF -> OpcaSPINActivityV2.class;
      case OPCA_UNIT_SPLIT -> OpcaUSPLITActivityV2.class;
      case OPCSH -> OPCSHActivityV2.class;
      case OPEXC -> OPEXCActivityV2.class;
      case OPEXP -> OPEXPActivityV2.class;
      case OPTRD -> OPTRDActivityV2.class;
      case REO -> REOActivityV2.class;
      case REVERSE_SPLIT -> ReverseSplitActivityV2.class;
      case RIGHTS_SUBSCRIPTION -> RightsSubscriptionElectionActivityV2.class;
      case SDIV -> SDIVActivityV2.class;
      case SPINOFF -> SpinoffActivityV2.class;
      case TENDER_OFFER -> TenderOfferActivityV2.class;
      case UNIT_SPLIT -> UnitSplitActivityV2.class;
      case WARRANT_EXERCISE -> WarrantExerciseElectionActivityV2.class;
      case WRM -> WRMActivityV2.class;
    };
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

  private static Candidate candidate(Class<?> modelClass, Validator validator) {
    return new Candidate(modelClass, validator);
  }

  private record Candidate(Class<?> modelClass, Validator validator) {
    boolean matches(JsonElement details) {
      try {
        validator.validate(details);
        return true;
      } catch (Exception ignored) {
        return false;
      }
    }
  }

  @FunctionalInterface
  private interface Validator {
    void validate(JsonElement element) throws IOException;
  }
}
