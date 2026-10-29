package io.akka.human.api;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Raising and following oversight events from service code. The Akka implementation is
 * {@code io.akka.human.akka.HumanClient}, built from a component client.
 *
 * <p>A workflow that waits raises with {@link Resume#workflow}, then pauses with a timeout past the
 * last escalation; the library calls the workflow back exactly once with the final {@link Decision}.
 */
public interface Human {

  CompletionStage<Raised> raise(RaiseRequest request);

  /** The final outcome, or empty while the event is pending. */
  CompletionStage<Optional<Decision>> outcome(String eventId);

  /** The proposer takes the request back; the event closes as WITHDRAWN. */
  CompletionStage<Void> withdraw(String eventId, Reviewer proposer, String reason);

  /** The question no longer exists (the records it concerned changed); closes the event without a person. */
  CompletionStage<Void> supersede(String eventId, String reason);

  /** Whether a halt stops the target now. Callers check it before tool calls, model calls and writes. */
  CompletionStage<Boolean> halted(String target);
}
