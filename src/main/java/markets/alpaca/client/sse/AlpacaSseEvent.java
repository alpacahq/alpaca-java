package markets.alpaca.client.sse;

import java.util.Objects;

/** One decoded Server-Sent Event and its protocol metadata. */
public record AlpacaSseEvent<T>(T data, String id, String type) {

  public AlpacaSseEvent {
    Objects.requireNonNull(data, "data must not be null");
  }
}
