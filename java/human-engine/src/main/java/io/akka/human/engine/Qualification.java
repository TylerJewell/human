package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.Overseer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

/**
 * Whether a person may act on a control, by the overseer registry: they hold a role the queue needs
 * (as they sign in and as the registry records), are qualified, hold every attestation the control
 * requires on the day, belong to a group it names, and have authority over the control and the
 * decision.
 */
public final class Qualification {

  /**
   * The person acting and their registry entry.
   *
   * @param overseer the reviewer's entry in the overseer registry; null when they have none
   * @param registry whether the service keeps a registry at all; without one the roles alone decide
   */
  public record Actor(Reviewer reviewer, Overseer overseer, boolean registry) {

    public static Actor unregistered(Reviewer reviewer) {
      return new Actor(reviewer, null, false);
    }
  }

  private Qualification() {}

  /**
   * Why the actor may not act in a queue that takes {@code roles}, or null when they may.
   *
   * @param action the decision, or null for acts that are not one (claim, escalate)
   */
  public static String refusal(ControlSettings settings, List<String> roles, Actor actor, Action action, Instant now) {
    var reviewer = actor.reviewer();
    var held = reviewer.roles().stream().filter(roles::contains).toList();
    if (held.isEmpty()) return reviewer.subject() + " acts as " + reviewer.roles() + "; this needs one of " + roles;
    if (!actor.registry()) return null;
    var overseer = actor.overseer();
    if (overseer == null) return reviewer.subject() + " is not in the overseer registry";
    if (held.stream().noneMatch(overseer.roles()::contains)) {
      return reviewer.subject() + " does not hold " + held + " in the overseer registry";
    }
    if (!overseer.qualified()) {
      return reviewer.subject() + " is not qualified"
          + (overseer.qualifiedReason() == null ? "" : ": " + overseer.qualifiedReason());
    }
    var day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    for (var required : settings.routing().attestations()) {
      var completed = overseer.attestations().stream().filter(a -> a.id().equals(required)).toList();
      if (completed.isEmpty()) return reviewer.subject() + " has not completed " + required;
      if (completed.stream().noneMatch(a -> a.validOn(day))) return reviewer.subject() + "'s " + required + " has expired";
    }
    var groups = settings.routing().groups();
    if (!groups.isEmpty() && overseer.groups().stream().noneMatch(groups::contains)) {
      return reviewer.subject() + " is in none of the groups " + groups;
    }
    if (!overseer.authority().isEmpty()) {
      var control = settings.control().rowId();
      boolean allowed = overseer.authority().stream().anyMatch(a ->
          (a.controls().contains("*") || a.controls().contains(control))
              && (action == null || a.actions() == null || a.actions().isEmpty() || a.actions().contains(action)));
      if (!allowed) {
        return reviewer.subject() + " has no authority to " + (action == null ? "act on" : action + " on") + " " + control;
      }
    }
    return null;
  }
}
