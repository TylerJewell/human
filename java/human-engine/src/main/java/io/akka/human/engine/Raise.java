package io.akka.human.engine;

import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Resume;
import io.akka.human.api.SubjectKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Builds a new event from what a service raises and the control's settings. */
public final class Raise {

  /**
   * Where the event is raised.
   *
   * @param reportsTo a person's manager in the overseer registry, or null; independence rule
   *     {@code first-reviewer-line} follows it
   */
  public record Context(String service, String environment, String catalogDigest, Function<String, String> reportsTo) {}

  private static final int MAX_LINE = 20;

  private Raise() {}

  /** Why the request cannot raise an event on these settings, or null. */
  public static Refusal check(ControlSettings settings, RaiseRequest request) {
    if (request.pattern() != null && request.pattern() != settings.pattern()) {
      return Refusal.invalid(settings.control().rowId() + " runs " + settings.pattern().id() + ", not "
          + request.pattern().id());
    }
    var ai = request.aiRecommendation();
    if (ai != null && !(ai.get("action") instanceof String action && isAction(action))) {
      return Refusal.invalid("an AI recommendation names the action it recommends");
    }
    if (request.subject().kind() != settings.subjectKind()) {
      return Refusal.invalid(settings.control().rowId() + " judges a " + settings.subjectKind().id() + " subject, not "
          + request.subject().kind().id());
    }
    return null;
  }

  /**
   * The event, raised at {@code now} and routed to the first queue; a control in shadow mode, or of
   * strength {@code record} where it runs, records the event without routing it.
   */
  public static HumanEvent event(String id, ControlSettings settings, RaiseRequest request, Context context, Instant now) {
    var strength = settings.strengthIn(context.environment());
    boolean shadow = settings.shadow() || "record".equals(strength);
    var subject = request.subject();
    if (subject.service() == null) subject = subject.withService(context.service());
    var independentOf = independentOf(settings, request, context);
    if (subject.kind() == SubjectKind.APPEAL && !subject.detail().containsKey("excluded")) {
      subject = subject.with("excluded", independentOf);
    }
    var state = shadow ? State.DETECTED : State.OPEN;
    var note = shadow ? "Recorded without routing (shadow)" : "Routed to " + settings.queueAt(0);
    var deadline = settings.deadline();
    var effects = new LinkedHashMap<>(request.effects());
    return new HumanEvent(
        id,
        context.service(),
        context.environment(),
        context.catalogDigest(),
        settings,
        state,
        strength,
        !shadow && settings.patternBlocks() && "prevent".equals(strength),
        subject,
        trigger(settings, request.trigger(), now),
        request.ask(),
        request.proposedBy(),
        request.builtBy(),
        request.firstReviewer(),
        independentOf,
        request.dedupeKey(),
        request.matchKey(),
        recommendation(request.aiRecommendation(), now),
        effects,
        request.evidence().stream().map(m -> (Map<String, Object>) new LinkedHashMap<String, Object>(m)).toList(),
        request.related().stream().map(r -> new HumanEvent.Related(r.get("eventId"), r.get("relation"))).toList(),
        request.resumes() == null ? Resume.none() : request.resumes(),
        request.simulated(),
        0,
        null,
        null,
        null,
        now,
        now,
        deadline == null ? null : now.plus(deadline),
        null,
        false,
        List.of(),
        List.of(),
        0,
        List.of(),
        List.of(),
        null,
        null,
        null,
        List.of(new HumanEvent.Step(now, state, HumanEvent.SYSTEM, note)));
  }

  /** A judge agent's recommendation, stamped with when it was made if it does not say. */
  static Map<String, Object> recommendation(Map<String, Object> given, Instant now) {
    if (given == null) return null;
    var stamped = new LinkedHashMap<>(given);
    stamped.putIfAbsent("at", now.toString());
    return stamped;
  }

  private static boolean isAction(String name) {
    try {
      io.akka.human.api.Action.valueOf(name);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** The trigger as fired now: the request's, filled in from the control's {@code trigger} block. */
  public static HumanEvent.Trigger trigger(ControlSettings settings, io.akka.human.api.Trigger fired, Instant now) {
    var declared = settings.trigger();
    var detector = fired != null && fired.detector() != null ? fired.detector()
        : declared.get("detector") != null ? String.valueOf(declared.get("detector")) : "workflow";
    var rule = fired != null && fired.rule() != null ? fired.rule()
        : declared.get("condition") != null ? String.valueOf(declared.get("condition")) : null;
    var values = new ArrayList<Map<String, Object>>();
    var conditions = new ArrayList<Map<String, Object>>();
    if (fired != null) {
      for (var v : fired.values()) {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", v.name());
        value.put("value", v.value());
        value.put("limit", v.limit());
        values.add(value);
      }
      for (var c : fired.conditions()) conditions.add(Map.of("name", c.name(), "met", c.met()));
    }
    return new HumanEvent.Trigger(detector, rule, fired == null ? null : fired.reason(), values, conditions, now, 1);
  }

  /** The people the control's independence rules bar from deciding this event. */
  static List<String> independentOf(ControlSettings settings, RaiseRequest request, Context context) {
    var rules = settings.routing().independence();
    var barred = new LinkedHashSet<String>();
    if (rules.contains("proposer") && request.proposedBy() != null) barred.add(request.proposedBy());
    if (rules.contains("builder") && request.builtBy() != null) barred.add(request.builtBy());
    if (rules.contains("first-reviewer") && request.firstReviewer() != null) barred.add(request.firstReviewer());
    if (rules.contains("first-reviewer-line") && request.firstReviewer() != null && context.reportsTo() != null) {
      var manager = context.reportsTo().apply(request.firstReviewer());
      for (int i = 0; manager != null && i < MAX_LINE && barred.add(manager); i++) {
        manager = context.reportsTo().apply(manager);
      }
    }
    return List.copyOf(barred);
  }
}
