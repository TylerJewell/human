package io.akka.human.api;

import java.util.List;

/** The question the person answers, and what to check first. Consoles show the question as the title. */
public record Ask(String question, List<Check> checks) {

  /** A check, and the part of the event that shows it: trigger, subject, evidence, blast or judge. */
  public record Check(String text, String section) {}

  public Ask {
    if (question == null || question.isBlank()) throw new IllegalArgumentException("an event needs a question");
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  public static Ask of(String question) {
    return new Ask(question, List.of());
  }
}
