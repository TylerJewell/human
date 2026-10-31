package io.akka.human.engine;

import java.util.List;

/**
 * Why a call was not accepted, as the contract answers it: 404 the event, control or standing
 * decision is unknown; 409 the event is not in a state that takes the call; 403 the reviewer may not
 * make it; 422 the request is invalid.
 *
 * @param state the event's state, for a 409
 * @param errors each problem found, for a 422
 * @param blockedBy the oversight-quality event that blocks the decision, for a 409
 */
public record Refusal(Kind kind, String error, String state, List<String> errors, String blockedBy) {

  public enum Kind {
    NOT_FOUND(404),
    CONFLICT(409),
    FORBIDDEN(403),
    INVALID(422);

    private final int status;

    Kind(int status) {
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  public Refusal {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public Refusal(Kind kind, String error, String state, List<String> errors) {
    this(kind, error, state, errors, null);
  }

  public static Refusal notFound(String error) {
    return new Refusal(Kind.NOT_FOUND, error, null, List.of());
  }

  public static Refusal conflict(String error, String state) {
    return new Refusal(Kind.CONFLICT, error, state, List.of());
  }

  /** A settling decision while an oversight-quality event blocks the event's control ({@link Blocks}). */
  public static Refusal blocked(String error, String state, String blockedBy) {
    return new Refusal(Kind.CONFLICT, error, state, List.of(), blockedBy);
  }

  public static Refusal forbidden(String error) {
    return new Refusal(Kind.FORBIDDEN, error, null, List.of());
  }

  public static Refusal invalid(String error) {
    return new Refusal(Kind.INVALID, error, null, List.of(error));
  }

  public int status() {
    return kind.status();
  }
}
