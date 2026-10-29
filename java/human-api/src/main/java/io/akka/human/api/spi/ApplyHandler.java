package io.akka.human.api.spi;

import io.akka.human.api.Decision;
import io.akka.human.api.Subject;
import java.util.concurrent.CompletionStage;

/**
 * Carries a decision out for an event nothing waits on (raise-and-carry-on patterns), or for a
 * {@code handler} resume target. Answers a line saying what was applied; the library records it on
 * the event and marks the event APPLIED. A failed stage records the error and leaves the event
 * DECIDED, and the verdict becomes {@code error}.
 */
@FunctionalInterface
public interface ApplyHandler {
  CompletionStage<String> apply(String id, Subject subject, Decision decision);
}
