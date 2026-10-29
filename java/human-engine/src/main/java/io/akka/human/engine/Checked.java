package io.akka.human.engine;

import java.util.List;

/** What a rule accepts (the events to record) or why it refuses. */
public record Checked<T>(T value, Refusal refusal) {

  public static <T> Checked<T> ok(T value) {
    return new Checked<>(value, null);
  }

  public static <T> Checked<T> refuse(Refusal refusal) {
    return new Checked<>(null, refusal);
  }

  public static Checked<List<HumanEventEvent>> events(HumanEventEvent... events) {
    return ok(List.of(events));
  }

  public boolean refused() {
    return refusal != null;
  }
}
