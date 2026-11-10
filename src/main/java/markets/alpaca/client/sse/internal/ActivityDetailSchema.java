package markets.alpaca.client.sse.internal;

/**
 * Detail model selected by explicit Activity V2 type/subtype dispatch.
 *
 * <p>{@code ActivityType}, {@code ActivityTypeFilter}, and {@code
 * NonTradeActivities.ActivityTypeEnum} name the wire activity type. Each constant here names one
 * generated detail model. Several activity types share a model: {@code CFEE} and {@code FEE} both
 * use {@link #FEE}. One activity type selects several models from its subtype: {@code DIV} selects
 * {@link #CDIV}, {@link #SDIV}, or {@link #DIVSPD}.
 *
 * <p>This enum covers explicit dispatch, not every supported detail model. Decoders can also select
 * models such as fixed-income redemption and rights distribution through structural fallback.
 *
 * <p>Broker and Trading each generate their own model classes. {@link ActivityDetailSchemaResolver}
 * returns this shared identity, and each decoder binds a constant to the class generated for that
 * API.
 */
public enum ActivityDetailSchema {
  ACATC,
  ACATS,
  CDIV,
  CGD,
  CSW,
  DIVNRA,
  DIVSPD,
  DIVWH,
  EXCHANGE_OFFER,
  FEE,
  FIXED_INCOME_INTEREST,
  FOPT,
  FORWARD_SPLIT,
  FXTRD,
  JNLC,
  JNLS,
  MA,
  MEM,
  NC,
  OCT,
  OPASN,
  OPCA_CDIV,
  OPCA_CGD,
  OPCA_FORWARD_SPLIT,
  OPCA_MA,
  OPCA_NC,
  OPCA_REVERSE_SPLIT,
  OPCA_SDIV,
  OPCA_SPINOFF,
  OPCA_UNIT_SPLIT,
  OPCSH,
  OPEXC,
  OPEXP,
  OPTRD,
  REO,
  REVERSE_SPLIT,
  RIGHTS_SUBSCRIPTION,
  SDIV,
  SPINOFF,
  TENDER_OFFER,
  UNIT_SPLIT,
  WARRANT_EXERCISE,
  WRM
}
