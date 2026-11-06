package markets.alpaca.client.broker.sse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import markets.alpaca.client.openapi.broker.http.JSON;
import markets.alpaca.client.openapi.broker.model.AcatcActivityV2;
import markets.alpaca.client.openapi.broker.model.AcatsActivityV2;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2;
import markets.alpaca.client.openapi.broker.model.ActivityEventV2AllOfDetails;
import markets.alpaca.client.openapi.broker.model.ActivityType;
import markets.alpaca.client.openapi.broker.model.ActivityTypeFilter;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailNTA;
import markets.alpaca.client.openapi.broker.model.ActivityV2DetailTRD;
import markets.alpaca.client.openapi.broker.model.CDIVActivityV2;
import markets.alpaca.client.openapi.broker.model.CGDActivityV2;
import markets.alpaca.client.openapi.broker.model.CSWActivityV2;
import markets.alpaca.client.openapi.broker.model.DIVNRAActivityV2;
import markets.alpaca.client.openapi.broker.model.DIVSPDActivityV2;
import markets.alpaca.client.openapi.broker.model.DIVWHActivityV2;
import markets.alpaca.client.openapi.broker.model.ExchangeOfferActivityV2;
import markets.alpaca.client.openapi.broker.model.FEEActivityV2;
import markets.alpaca.client.openapi.broker.model.FOPTActivityV2;
import markets.alpaca.client.openapi.broker.model.FXTRDActivityV2;
import markets.alpaca.client.openapi.broker.model.FixedIncomeInterestActivityV2;
import markets.alpaca.client.openapi.broker.model.ForwardSplitActivityV2;
import markets.alpaca.client.openapi.broker.model.JNLCActivityV2;
import markets.alpaca.client.openapi.broker.model.JNLSActivityV2;
import markets.alpaca.client.openapi.broker.model.MAActivityV2;
import markets.alpaca.client.openapi.broker.model.MEMActivityV2;
import markets.alpaca.client.openapi.broker.model.NCActivityV2;
import markets.alpaca.client.openapi.broker.model.OCTActivityV2;
import markets.alpaca.client.openapi.broker.model.OPASNActivityV2;
import markets.alpaca.client.openapi.broker.model.OPCSHActivityV2;
import markets.alpaca.client.openapi.broker.model.OPEXCActivityV2;
import markets.alpaca.client.openapi.broker.model.OPEXPActivityV2;
import markets.alpaca.client.openapi.broker.model.OPTRDActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaCDIVActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaCGDActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaFSPLITActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaMAActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaNCActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaRSPLITActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaSDIVActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaSPINActivityV2;
import markets.alpaca.client.openapi.broker.model.OpcaUSPLITActivityV2;
import markets.alpaca.client.openapi.broker.model.REOActivityV2;
import markets.alpaca.client.openapi.broker.model.ReverseSplitActivityV2;
import markets.alpaca.client.openapi.broker.model.RightsSubscriptionElectionActivityV2;
import markets.alpaca.client.openapi.broker.model.SDIVActivityV2;
import markets.alpaca.client.openapi.broker.model.SpinoffActivityV2;
import markets.alpaca.client.openapi.broker.model.TenderOfferActivityV2;
import markets.alpaca.client.openapi.broker.model.UnitSplitActivityV2;
import markets.alpaca.client.openapi.broker.model.WRMActivityV2;
import markets.alpaca.client.openapi.broker.model.WarrantExerciseElectionActivityV2;
import markets.alpaca.client.sse.internal.ActivityDetailSchema;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaResolver;
import markets.alpaca.client.sse.internal.ActivityDetailSchemaSelector;
import markets.alpaca.client.sse.internal.GeneratedModelAdditionalProperties;

/** Discriminant-aware decoder for Broker Activity V2 generated models. */
final class BrokerActivityEventDecoder {

  private static final Set<String> HANDLED_ENVELOPE_FIELDS =
      Set.of(
          "account_id",
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

  static {
    Set<Class<?>> generated = Set.copyOf(ActivityV2DetailNTA.schemas.values());
    for (ActivityDetailSchema schema : ActivityDetailSchema.values()) {
      Class<?> model = modelClass(schema);
      if (!generated.contains(model)) {
        throw new ExceptionInInitializerError(
            "Broker detail model is not a generated NTA schema: " + schema);
      }
    }
  }

  ActivityEventV2 decode(String data) throws IOException {
    JsonObject root = JsonParser.parseString(data).getAsJsonObject();
    String type = string(root, "activity_type");
    String subtype = nullableString(root, "activity_subtype");
    JsonElement details = root.get("details");
    if (details == null || !details.isJsonObject()) {
      throw new IOException("Activity event details must be an object");
    }

    ActivityEventV2 event = commonFields(root);
    // The REST activities endpoint labels fills FILL. The live Activity SSE endpoint labels
    // the same trade events TRD (documented on ActivityEventV2Asset: "Only present when
    // activity_type is TRD"). TRD is a filter value, not an ActivityType. Both route to the
    // same trade detail schema.
    if (ActivityType.FILL.getValue().equals(type)
        || ActivityTypeFilter.TRD.getValue().equals(type)) {
      ActivityV2DetailTRD.validateJsonElement(details);
      event.setDetails(
          new ActivityEventV2AllOfDetails(
              JSON.getGson().fromJson(details, ActivityV2DetailTRD.class)));
      return event;
    }

    ActivityDetailSchema schema =
        ActivityDetailSchemaResolver.resolve(parseActivityType(type), subtype);
    Class<?> modelClass;
    if (schema == null) {
      List<Class<?>> matches =
          ActivityV2DetailNTA.schemas.values().stream()
              .filter(candidate -> matches(candidate, details))
              .toList();
      modelClass =
          ActivityDetailSchemaSelector.uniqueMostSpecific(details.getAsJsonObject(), matches);
      if (modelClass == null) {
        throw new IOException(
            "Cannot uniquely decode "
                + type
                + "/"
                + subtype
                + " activity details; matches="
                + matches.stream().map(Class::getSimpleName).toList());
      }
    } else {
      modelClass = modelClass(schema);
    }

    Object detail = decodeDetail(modelClass, details);
    event.setDetails(new ActivityEventV2AllOfDetails(new ActivityV2DetailNTA(detail)));
    return event;
  }

  private static boolean matches(Class<?> modelClass, JsonElement details) {
    try {
      decodeDetail(modelClass, details);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Object decodeDetail(Class<?> modelClass, JsonElement details) throws IOException {
    String schema = modelClass.getSimpleName();
    try {
      modelClass.getMethod("validateJsonElement", JsonElement.class).invoke(null, details);
      return JSON.getGson().fromJson(details, modelClass);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IOException ioFailure) throw ioFailure;
      if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw new IOException("Failed to validate Activity V2 detail schema " + schema, cause);
    } catch (ReflectiveOperationException failure) {
      throw new IOException("Cannot validate Activity V2 detail schema " + schema, failure);
    }
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

  private static ActivityEventV2 commonFields(JsonObject root) throws IOException {
    ActivityEventV2 event = new ActivityEventV2();
    event.setAccountId(UUID.fromString(string(root, "account_id")));
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
}
