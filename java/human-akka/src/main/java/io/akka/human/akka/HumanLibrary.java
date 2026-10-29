package io.akka.human.akka;

import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HaltScope;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.api.spi.Overseer;
import io.akka.human.engine.ControlSettings;
import io.akka.human.engine.Controls;
import io.akka.human.engine.Qualification.Actor;
import io.akka.human.engine.Raise;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the library derives from a service's plug-ins, once per {@link HumanPlugins} instance: the
 * controls with their settings resolved and checked, and the overseer registry by subject. Components
 * are created per call, so they look this up rather than rebuilding it.
 */
public final class HumanLibrary {

  public static final String NAME = "akka-human 0.1.0";

  private static final Map<HumanPlugins, HumanLibrary> BY_PLUGINS = Collections.synchronizedMap(new IdentityHashMap<>());

  private final HumanPlugins plugins;
  private final Controls controls;
  private final Map<String, Overseer> overseers;

  private HumanLibrary(HumanPlugins plugins) {
    this.plugins = plugins;
    this.controls = Controls.of(plugins.catalog());
    this.overseers = plugins.overseers().overseers().stream()
        .collect(Collectors.toUnmodifiableMap(Overseer::subject, Function.identity(), (a, b) -> a));
  }

  public static HumanLibrary of(HumanPlugins plugins) {
    return BY_PLUGINS.computeIfAbsent(plugins, HumanLibrary::new);
  }

  public HumanPlugins plugins() {
    return plugins;
  }

  public Controls controls() {
    return controls;
  }

  public Instant now() {
    return plugins.clock().now();
  }

  public Overseer overseer(String subject) {
    return overseers.get(subject);
  }

  /** The reviewer with their registry entry; a service without a registry lets roles alone decide. */
  public Actor actor(Reviewer reviewer) {
    return new Actor(reviewer, overseers.get(reviewer.subject()), !overseers.isEmpty());
  }

  /**
   * The service's halt scope for a target: the one registered for it, else the one registered for the
   * control's halt_config target (a control that halts targets named per call, such as each playbook),
   * else one with no safe-state checks.
   */
  public HaltScope haltScope(String target, ControlSettings settings) {
    var scopes = plugins.haltScopes();
    return scopes.getOrDefault(target, scopes.getOrDefault(settings.haltTarget(), HaltScope.ALWAYS_SAFE));
  }

  public Raise.Context context() {
    return new Raise.Context(plugins.service(), plugins.environment(), controls.digest(), subject -> {
      var overseer = overseers.get(subject);
      return overseer == null ? null : overseer.reportsTo();
    });
  }
}
