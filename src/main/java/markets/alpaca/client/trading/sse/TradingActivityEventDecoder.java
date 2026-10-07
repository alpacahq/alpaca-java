package markets.alpaca.client.trading.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import markets.alpaca.client.openapi.trading.http.JSON;
import markets.alpaca.client.openapi.trading.model.AcatcActivityV2;
import markets.alpaca.client.openapi.trading.model.AcatsActivityV2;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2AllOfDetails;
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
          candidate(
              "DIVSPDActivityV2", DIVSPDActivityV2.class, DIVSPDActivityV2::validateJsonElement),
          candidate("CDIVActivityV2", CDIVActivityV2.class, CDIVActivityV2::validateJsonElement),
          candidate("SDIVActivityV2", SDIVActivityV2.class, SDIVActivityV2::validateJsonElement),
          candidate("CGDActivityV2", CGDActivityV2.class, CGDActivityV2::validateJsonElement),
          candidate(
              "ForwardSplitActivityV2",
              ForwardSplitActivityV2.class,
              ForwardSplitActivityV2::validateJsonElement),
          candidate(
              "ReverseSplitActivityV2",
              ReverseSplitActivityV2.class,
              ReverseSplitActivityV2::validateJsonElement),
          candidate(
              "UnitSplitActivityV2",
              UnitSplitActivityV2.class,
              UnitSplitActivityV2::validateJsonElement),
          candidate(
              "SpinoffActivityV2", SpinoffActivityV2.class, SpinoffActivityV2::validateJsonElement),
          candidate("MAActivityV2", MAActivityV2.class, MAActivityV2::validateJsonElement),
          candidate("NCActivityV2", NCActivityV2.class, NCActivityV2::validateJsonElement),
          candidate(
              "FixedIncomeRedemptionActivityV2",
              FixedIncomeRedemptionActivityV2.class,
              FixedIncomeRedemptionActivityV2::validateJsonElement),
          candidate(
              "FixedIncomeInterestActivityV2",
              FixedIncomeInterestActivityV2.class,
              FixedIncomeInterestActivityV2::validateJsonElement),
          candidate("REOActivityV2", REOActivityV2.class, REOActivityV2::validateJsonElement),
          candidate(
              "RightsDistributionActivityV2",
              RightsDistributionActivityV2.class,
              RightsDistributionActivityV2::validateJsonElement),
          candidate(
              "RightsSubscriptionElectionActivityV2",
              RightsSubscriptionElectionActivityV2.class,
              RightsSubscriptionElectionActivityV2::validateJsonElement),
          candidate(
              "TenderOfferActivityV2",
              TenderOfferActivityV2.class,
              TenderOfferActivityV2::validateJsonElement),
          candidate(
              "ExchangeOfferActivityV2",
              ExchangeOfferActivityV2.class,
              ExchangeOfferActivityV2::validateJsonElement),
          candidate(
              "WarrantExerciseElectionActivityV2",
              WarrantExerciseElectionActivityV2.class,
              WarrantExerciseElectionActivityV2::validateJsonElement),
          candidate("WRMActivityV2", WRMActivityV2.class, WRMActivityV2::validateJsonElement),
          candidate(
              "OpcaCDIVActivityV2",
              OpcaCDIVActivityV2.class,
              OpcaCDIVActivityV2::validateJsonElement),
          candidate(
              "OpcaSDIVActivityV2",
              OpcaSDIVActivityV2.class,
              OpcaSDIVActivityV2::validateJsonElement),
          candidate(
              "OpcaMAActivityV2", OpcaMAActivityV2.class, OpcaMAActivityV2::validateJsonElement),
          candidate(
              "OpcaNCActivityV2", OpcaNCActivityV2.class, OpcaNCActivityV2::validateJsonElement),
          candidate(
              "OpcaSPINActivityV2",
              OpcaSPINActivityV2.class,
              OpcaSPINActivityV2::validateJsonElement),
          candidate(
              "OpcaFSPLITActivityV2",
              OpcaFSPLITActivityV2.class,
              OpcaFSPLITActivityV2::validateJsonElement),
          candidate(
              "OpcaUSPLITActivityV2",
              OpcaUSPLITActivityV2.class,
              OpcaUSPLITActivityV2::validateJsonElement),
          candidate(
              "OpcaRSPLITActivityV2",
              OpcaRSPLITActivityV2.class,
              OpcaRSPLITActivityV2::validateJsonElement),
          candidate("OPASNActivityV2", OPASNActivityV2.class, OPASNActivityV2::validateJsonElement),
          candidate("OPEXCActivityV2", OPEXCActivityV2.class, OPEXCActivityV2::validateJsonElement),
          candidate("OPEXPActivityV2", OPEXPActivityV2.class, OPEXPActivityV2::validateJsonElement),
          candidate("OPTRDActivityV2", OPTRDActivityV2.class, OPTRDActivityV2::validateJsonElement),
          candidate("OPCSHActivityV2", OPCSHActivityV2.class, OPCSHActivityV2::validateJsonElement),
          candidate("AcatsActivityV2", AcatsActivityV2.class, AcatsActivityV2::validateJsonElement),
          candidate("AcatcActivityV2", AcatcActivityV2.class, AcatcActivityV2::validateJsonElement),
          candidate("FOPTActivityV2", FOPTActivityV2.class, FOPTActivityV2::validateJsonElement),
          candidate(
              "DIVNRAActivityV2", DIVNRAActivityV2.class, DIVNRAActivityV2::validateJsonElement),
          candidate("DIVWHActivityV2", DIVWHActivityV2.class, DIVWHActivityV2::validateJsonElement),
          candidate("JNLSActivityV2", JNLSActivityV2.class, JNLSActivityV2::validateJsonElement),
          candidate("JNLCActivityV2", JNLCActivityV2.class, JNLCActivityV2::validateJsonElement),
          candidate("CSWActivityV2", CSWActivityV2.class, CSWActivityV2::validateJsonElement),
          candidate("FEEActivityV2", FEEActivityV2.class, FEEActivityV2::validateJsonElement),
          candidate("MEMActivityV2", MEMActivityV2.class, MEMActivityV2::validateJsonElement),
          candidate("OCTActivityV2", OCTActivityV2.class, OCTActivityV2::validateJsonElement));
  private static final Map<String, Candidate> CANDIDATES_BY_NAME =
      CANDIDATES.stream()
          .collect(Collectors.toUnmodifiableMap(Candidate::schemaName, value -> value));
  private static final Map<String, Class<?>> CANDIDATE_SCHEMAS =
      CANDIDATES.stream()
          .collect(Collectors.toUnmodifiableMap(Candidate::schemaName, Candidate::modelClass));

  ActivityEventV2 decode(String data) throws IOException {
    JsonObject root = JsonParser.parseString(data).getAsJsonObject();
    String activityType = string(root, "activity_type");
    String activitySubtype = nullableString(root, "activity_subtype");
    JsonElement details = root.get("details");
    if (details == null || !details.isJsonObject()) {
      throw new IOException("Activity event details must be an object");
    }

    ActivityEventV2 event = commonFields(root);
    if ("FILL".equals(activityType)) {
      ActivityV2DetailTRD.validateJsonElement(details);
      event.setDetails(
          new ActivityEventV2AllOfDetails(
              JSON.getGson().fromJson(details, ActivityV2DetailTRD.class)));
      return event;
    }

    Candidate selected = preferred(activityType, activitySubtype);
    if (selected != null) {
      selected.validator().validate(details);
    } else {
      List<Candidate> matches =
          CANDIDATES.stream().filter(candidate -> candidate.matches(details)).toList();
      String selectedSchema =
          ActivityDetailSchemaSelector.uniqueMostSpecific(
              details.getAsJsonObject(),
              matches.stream().map(Candidate::schemaName).toList(),
              CANDIDATE_SCHEMAS);
      if (selectedSchema == null) {
        throw new IOException(
            "Cannot uniquely decode "
                + activityType
                + "/"
                + activitySubtype
                + " activity details; matches="
                + matches.stream().map(Candidate::schemaName).toList());
      }
      selected = CANDIDATES_BY_NAME.get(selectedSchema);
    }

    Object detail = JSON.getGson().fromJson(details, selected.modelClass());
    event.setDetails(new ActivityEventV2AllOfDetails(new ActivityV2DetailNTA(detail)));
    return event;
  }

  static Set<String> candidateSchemaNames() {
    return CANDIDATES.stream().map(Candidate::schemaName).collect(Collectors.toUnmodifiableSet());
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

  private static Candidate preferred(String type, String subtype) {
    String schema = ActivityDetailSchemaResolver.resolve(type, subtype);
    if (schema == null) return null;
    return CANDIDATES.stream()
        .filter(candidate -> candidate.schemaName().equals(schema))
        .findFirst()
        .orElse(null);
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

  private static Candidate candidate(String schemaName, Class<?> modelClass, Validator validator) {
    return new Candidate(schemaName, modelClass, validator);
  }

  private record Candidate(String schemaName, Class<?> modelClass, Validator validator) {
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
