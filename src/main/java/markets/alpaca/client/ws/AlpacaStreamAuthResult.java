package markets.alpaca.client.ws;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of a stream's first authentication attempt.
 *
 * <p>Use {@link #isAuthenticated()} for simple success checks, or inspect {@link #status()}, {@link
 * #code()}, and {@link #message()} when operational diagnostics need the server response or close
 * reason.
 */
public record AlpacaStreamAuthResult(Status status, Integer code, String message) {

  /** Outcome categories for stream authentication. */
  public enum Status {
    /** The stream authenticated successfully. */
    AUTHENTICATED,
    /** The server rejected the credentials or authentication request. */
    SERVER_REJECTED,
    /** The stream closed before authentication completed. */
    CLOSED,
    /** The caller's wait timed out before authentication completed. */
    TIMEOUT,
    /** The caller's wait was interrupted before authentication completed. */
    INTERRUPTED,
    /** The stream became terminal before authentication completed for another reason. */
    FAILED
  }

  public AlpacaStreamAuthResult {
    Objects.requireNonNull(status, "status must not be null");
  }

  /** Returns {@code true} when {@link #status()} is {@link Status#AUTHENTICATED}. */
  public boolean isAuthenticated() {
    return status == Status.AUTHENTICATED;
  }

  /** Creates a successful authentication result. */
  public static AlpacaStreamAuthResult authenticated() {
    return new AlpacaStreamAuthResult(Status.AUTHENTICATED, null, "authenticated");
  }

  /** Creates a result for credentials or auth requests rejected by the server. */
  public static AlpacaStreamAuthResult serverRejected(Integer code, String message) {
    return new AlpacaStreamAuthResult(Status.SERVER_REJECTED, code, message);
  }

  /** Creates a result for streams closed before authentication completed. */
  public static AlpacaStreamAuthResult closed(String reason) {
    return new AlpacaStreamAuthResult(Status.CLOSED, null, reason);
  }

  /** Creates a result for waiting callers whose timeout elapsed. */
  public static AlpacaStreamAuthResult timeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    return new AlpacaStreamAuthResult(Status.TIMEOUT, null, timeout.toString());
  }

  /** Creates a result for waiting callers that were interrupted. */
  public static AlpacaStreamAuthResult interrupted() {
    return new AlpacaStreamAuthResult(Status.INTERRUPTED, null, "interrupted");
  }

  /** Creates a result for non-server-rejection failures before authentication completed. */
  public static AlpacaStreamAuthResult failed(String message, Throwable cause) {
    String detail =
        cause == null || cause.getMessage() == null ? message : message + ": " + cause.getMessage();
    return new AlpacaStreamAuthResult(Status.FAILED, null, detail);
  }
}
