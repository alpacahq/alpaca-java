package markets.alpaca.client.sse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.broker.sse.BrokerSseSubscription;
import markets.alpaca.client.data.sse.CorporateActionsSseClient;
import markets.alpaca.client.data.sse.CorporateActionsSseRequest;
import markets.alpaca.client.data.sse.MarketDataSseEnvironment;
import markets.alpaca.client.trading.sse.TradingActivitySseRequest;
import markets.alpaca.client.trading.sse.TradingEventsSseClient;
import org.junit.jupiter.api.Test;

class SsePublicApiBoundaryTest {

  private static final List<Class<?>> SUPPORTED_TYPES =
      List.of(
          AlpacaClient.class,
          AlpacaClientFactory.class,
          AlpacaSseCallbackException.class,
          AlpacaSseCloseResult.class,
          AlpacaSseConnectionInfo.class,
          AlpacaSseDeserializationException.class,
          AlpacaSseEvent.class,
          AlpacaSseException.class,
          AlpacaSseHttpException.class,
          AlpacaSseListener.class,
          AlpacaSseOptions.class,
          AlpacaSseProtocolException.class,
          AlpacaSseReconnectPolicy.class,
          AlpacaSseState.class,
          AlpacaSseSubscription.class,
          TradingActivitySseRequest.class,
          TradingEventsSseClient.class,
          CorporateActionsSseClient.class,
          CorporateActionsSseRequest.class,
          MarketDataSseEnvironment.class,
          BrokerEventsSseClient.class,
          BrokerSseEventListener.class,
          BrokerSseSubscription.class);

  @Test
  void supportedPublicSignaturesDoNotExposeInternalSseTypes() {
    SUPPORTED_TYPES.forEach(
        type -> {
          Stream.concat(
                  Stream.of(type.getDeclaredConstructors()), Stream.of(type.getDeclaredMethods()))
              .filter(member -> Modifier.isPublic(member.getModifiers()))
              .forEach(member -> assertExecutableBoundary(type, member));
          Stream.of(type.getDeclaredFields())
              .filter(field -> Modifier.isPublic(field.getModifiers()))
              .forEach(field -> assertSupportedType(type, field.toString(), field.getType()));
        });
  }

  @Test
  void brokerSubscriptionImplementsSharedLifecycleContract() {
    assertTrue(AlpacaSseSubscription.class.isAssignableFrom(BrokerSseSubscription.class));
  }

  private static void assertExecutableBoundary(Class<?> owner, Executable executable) {
    if (executable instanceof java.lang.reflect.Method method) {
      assertSupportedType(owner, executable.toString(), method.getReturnType());
    }
    Stream.concat(
            Stream.of(executable.getParameterTypes()), Stream.of(executable.getExceptionTypes()))
        .forEach(type -> assertSupportedType(owner, executable.toString(), type));
  }

  private static void assertSupportedType(Class<?> owner, String member, Class<?> type) {
    Class<?> component = type;
    while (component.isArray()) component = component.getComponentType();
    assertFalse(
        component.getName().startsWith("markets.alpaca.client.sse.internal."),
        () -> owner.getName() + " exposes internal SSE type through " + member);
  }
}
