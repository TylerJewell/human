package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.spi.ControlCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A service's human controls with their settings resolved, checked once against the registries. */
public final class Controls {

  private final Map<String, ControlSettings> byId;
  private final String digest;

  private Controls(Map<String, ControlSettings> byId, String digest) {
    this.byId = byId;
    this.digest = digest;
  }

  /** Refuses a catalog with any problem, listing them all. */
  public static Controls of(ControlCatalog catalog) {
    var problems = Settings.problems(catalog);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("the control catalog is not valid:\n  " + String.join("\n  ", problems));
    }
    var byId = new LinkedHashMap<String, ControlSettings>();
    for (var control : catalog.controls()) byId.put(control.id(), Settings.resolve(control));
    return new Controls(byId, catalog.digest());
  }

  public Optional<ControlSettings> find(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  public List<ControlSettings> all() {
    return List.copyOf(byId.values());
  }

  public String digest() {
    return digest;
  }

  /** The patterns the service serves, in registry order. */
  public List<Pattern> patterns() {
    return java.util.Arrays.stream(Pattern.values())
        .filter(p -> byId.values().stream().anyMatch(c -> c.pattern() == p)).toList();
  }

  /** The first control of a pattern, for routes that raise one without naming a control. */
  public Optional<ControlSettings> firstOf(Pattern pattern) {
    return byId.values().stream().filter(c -> c.pattern() == pattern).findFirst();
  }

  /** The halt-restart control whose halt stops a target. */
  public Optional<ControlSettings> haltFor(String target) {
    return byId.values().stream().filter(c -> c.haltRestart() && c.haltTarget().equals(target)).findFirst();
  }

  /**
   * The control a halt ({@code lift} false) or a restart is raised under, when a person names
   * {@code named}: the named control when it allows the act (HALT, or APPROVE for a restart), otherwise a
   * halt-restart control of the same target that does. Empty when none does.
   */
  public Optional<ControlSettings> haltControl(ControlSettings named, boolean lift) {
    var act = lift ? Action.APPROVE : Action.HALT;
    if (named.allowedActions().contains(act)) return Optional.of(named);
    return byId.values().stream().filter(c -> c.haltRestart() && c.haltTarget().equals(named.haltTarget())
        && c.allowedActions().contains(act)).findFirst();
  }
}
