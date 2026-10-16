package markets.alpaca.client.sse.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import markets.alpaca.client.sse.AlpacaSseProtocolException;
import org.junit.jupiter.api.Test;

class SseParserTest {

  @Test
  void parsesFragmentedUtf8MultilineDataAndMetadata() {
    var events = new ArrayList<String>();
    var comments = new ArrayList<String>();
    var retry = new AtomicLong();
    var parser =
        parser(
            (id, type, data) -> events.add(id + "|" + type + "|" + data),
            comments::add,
            retry::set,
            1024);
    byte[] bytes =
        ("\ufeffid: evt-1\r"
                + "event: activity\r\n"
                + "retry: 2500\n"
                + ": slow client\n"
                + "data: {\"text\":\"café\"}\n"
                + "data: second\n\n")
            .getBytes(StandardCharsets.UTF_8);

    for (byte value : bytes) parser.accept(new byte[] {value}, 1);

    assertEquals(List.of("evt-1|activity|{\"text\":\"café\"}\nsecond"), events);
    assertEquals(List.of("slow client"), comments);
    assertEquals(2500, retry.get());
  }

  @Test
  void persistentIdAndEmptyIdAreAppliedToSubsequentEvents() {
    var ids = new ArrayList<String>();
    var parser = parser((id, type, data) -> ids.add(id), ignored -> {}, ignored -> {}, 1024);

    accept(parser, "id: first\ndata: one\n\ndata: two\n\nid:\ndata: three\n\n");

    assertEquals(List.of("first", "first", ""), ids);
  }

  @Test
  void commitsDataLessIdOnlyAfterTerminatingBlankLine() {
    var committedIds = new ArrayList<String>();
    var parser =
        new SseParser(
            new Handler((id, type, data) -> {}, ignored -> {}, ignored -> {}) {
              @Override
              public void onLastEventId(String id) {
                committedIds.add(id);
              }
            },
            1024,
            1024);

    accept(parser, "id: incomplete");
    parser.finish();
    assertTrue(committedIds.isEmpty());

    parser =
        new SseParser(
            new Handler((id, type, data) -> {}, ignored -> {}, ignored -> {}) {
              @Override
              public void onLastEventId(String id) {
                committedIds.add(id);
              }
            },
            1024,
            1024);
    accept(parser, "id: committed\n\n");

    assertEquals(List.of("committed"), committedIds);
  }

  @Test
  void incompleteEventAtEofIsDiscarded() {
    var events = new ArrayList<String>();
    var parser = parser((id, type, data) -> events.add(data), ignored -> {}, ignored -> {}, 1024);

    accept(parser, "data: incomplete");
    parser.finish();

    assertTrue(events.isEmpty());
  }

  @Test
  void rejectsOversizedLineAndEvent() {
    var lineParser = parser((id, type, data) -> {}, ignored -> {}, ignored -> {}, 4);
    assertThrows(AlpacaSseProtocolException.class, () -> accept(lineParser, "data: too long\n"));

    var eventParser =
        new SseParser(new Handler((id, type, data) -> {}, ignored -> {}, ignored -> {}), 1024, 5);
    assertThrows(AlpacaSseProtocolException.class, () -> accept(eventParser, "data: 12345\n"));
  }

  private static SseParser parser(
      EventConsumer event,
      java.util.function.Consumer<String> comment,
      java.util.function.LongConsumer retry,
      long limit) {
    return new SseParser(new Handler(event, comment, retry), limit, limit);
  }

  private static void accept(SseParser parser, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    parser.accept(bytes, bytes.length);
  }

  @FunctionalInterface
  private interface EventConsumer {
    void accept(String id, String type, String data);
  }

  private static class Handler implements SseParser.Handler {
    private final EventConsumer event;
    private final java.util.function.Consumer<String> comment;
    private final java.util.function.LongConsumer retry;

    private Handler(
        EventConsumer event,
        java.util.function.Consumer<String> comment,
        java.util.function.LongConsumer retry) {
      this.event = event;
      this.comment = comment;
      this.retry = retry;
    }

    @Override
    public void onEvent(String id, String type, String data) {
      event.accept(id, type, data);
    }

    @Override
    public void onComment(String value) {
      comment.accept(value);
    }

    @Override
    public void onRetry(long milliseconds) {
      retry.accept(milliseconds);
    }
  }
}
