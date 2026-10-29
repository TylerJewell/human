package io.akka.human.api;

import java.util.List;

/** Why the event was raised. A null detector takes the control's own. */
public record Trigger(String detector, String rule, String reason, List<Value> values, List<Condition> conditions) {

  public record Value(String name, Object value, Object limit) {}

  public record Condition(String name, boolean met) {}

  public Trigger {
    values = values == null ? List.of() : List.copyOf(values);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public static Trigger because(String reason) {
    return new Trigger(null, null, reason, List.of(), List.of());
  }

  public static Trigger rule(String detector, String rule, String reason) {
    return new Trigger(detector, rule, reason, List.of(), List.of());
  }
}
