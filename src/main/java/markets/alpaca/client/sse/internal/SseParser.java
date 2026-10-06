package markets.alpaca.client.sse.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import markets.alpaca.client.sse.AlpacaSseProtocolException;

/** Incremental WHATWG-style SSE field parser. */
final class SseParser {

  interface Handler {
    void onEvent(String id, String type, String data);

    default void onLastEventId(String id) {}

    void onComment(String comment);

    void onRetry(long milliseconds);
  }

  private final Handler handler;
  private final long maxLineBytes;
  private final long maxEventBytes;
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final StringBuilder data = new StringBuilder();
  private boolean skipLf;
  private boolean firstLine = true;
  private long eventBytes;
  private String lastEventId;
  private String pendingLastEventId;
  private boolean pendingLastEventIdChanged;
  private String eventType;

  SseParser(Handler handler, long maxLineBytes, long maxEventBytes) {
    this(handler, maxLineBytes, maxEventBytes, null);
  }

  SseParser(Handler handler, long maxLineBytes, long maxEventBytes, String initialLastEventId) {
    this.handler = handler;
    this.maxLineBytes = maxLineBytes;
    this.maxEventBytes = maxEventBytes;
    this.lastEventId = initialLastEventId;
    this.pendingLastEventId = initialLastEventId;
  }

  void accept(byte[] bytes, int length) {
    for (int i = 0; i < length; i++) {
      int value = bytes[i] & 0xff;
      if (skipLf) {
        skipLf = false;
        if (value == '\n') continue;
      }
      if (value == '\r') {
        dispatchLine();
        skipLf = true;
      } else if (value == '\n') {
        dispatchLine();
      } else {
        if (line.size() >= maxLineBytes) {
          throw new AlpacaSseProtocolException("SSE line exceeds " + maxLineBytes + " bytes");
        }
        line.write(value);
      }
    }
  }

  /** Deliberately discards an event that was not terminated by a blank line. */
  void finish() {
    line.reset();
    data.setLength(0);
    eventBytes = 0;
  }

  private void dispatchLine() {
    String value = decodeLine();
    line.reset();
    if (firstLine) {
      firstLine = false;
      if (!value.isEmpty() && value.charAt(0) == '\ufeff') value = value.substring(1);
    }

    if (value.isEmpty()) {
      dispatchEvent();
      return;
    }
    if (value.charAt(0) == ':') {
      String comment = value.substring(1);
      if (comment.startsWith(" ")) comment = comment.substring(1);
      handler.onComment(comment);
      return;
    }

    int colon = value.indexOf(':');
    String field = colon < 0 ? value : value.substring(0, colon);
    String fieldValue = colon < 0 ? "" : value.substring(colon + 1);
    if (fieldValue.startsWith(" ")) fieldValue = fieldValue.substring(1);

    switch (field) {
      case "data" -> appendData(fieldValue);
      case "event" -> eventType = fieldValue;
      case "id" -> {
        if (fieldValue.indexOf('\0') < 0) {
          pendingLastEventId = fieldValue;
          pendingLastEventIdChanged = true;
        }
      }
      case "retry" -> parseRetry(fieldValue);
      default -> {
        // Unknown fields are ignored by the SSE specification.
      }
    }
  }

  private void appendData(String value) {
    long bytes = value.getBytes(StandardCharsets.UTF_8).length + 1L;
    if (eventBytes + bytes > maxEventBytes) {
      throw new AlpacaSseProtocolException("SSE event exceeds " + maxEventBytes + " bytes");
    }
    data.append(value).append('\n');
    eventBytes += bytes;
  }

  private void dispatchEvent() {
    if (pendingLastEventIdChanged) {
      lastEventId = pendingLastEventId;
      pendingLastEventIdChanged = false;
    }
    if (data.isEmpty()) {
      handler.onLastEventId(lastEventId);
      eventType = null;
      eventBytes = 0;
      return;
    }
    data.setLength(data.length() - 1);
    handler.onEvent(
        lastEventId,
        eventType == null || eventType.isEmpty() ? "message" : eventType,
        data.toString());
    data.setLength(0);
    eventType = null;
    eventBytes = 0;
  }

  private void parseRetry(String value) {
    if (value.isEmpty()) return;
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) return;
    }
    try {
      handler.onRetry(Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      // Values outside long range are invalid and ignored.
    }
  }

  private String decodeLine() {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(line.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      throw new AlpacaSseProtocolException("SSE line is not valid UTF-8", e);
    }
  }
}
