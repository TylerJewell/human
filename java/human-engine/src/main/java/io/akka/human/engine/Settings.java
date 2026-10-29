package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Flavor;
import io.akka.human.api.Pattern;
import io.akka.human.api.Sticky;
import io.akka.human.api.SubjectKind;
import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a control's settings (the control's {@code human_config}, then its pattern's defaults,
 * then the shared defaults) and checks a catalog against the Verify registries.
 */
public final class Settings {

  private static final Set<String> REVIEW_MODES = Set.of("SINGLE", "TWO_PERSON", "N_OF_M", "CONSENSUS");
  private static final Set<String> TIMEOUTS = Set.of("RAISE_TIMEOUT", "RESUME_WITH_DEFAULT", "ESCALATE", "ABANDON");
  private static final Set<String> INDEPENDENCE = Set.of("proposer", "builder", "first-reviewer", "first-reviewer-line");
  private static final Map<Action, String> DEFAULT_LABELS = defaultLabels();

  private Settings() {}

  /** The label an action shows when neither the control nor its pattern names one. */
  public static String defaultLabel(Action action) {
    return DEFAULT_LABELS.get(action);
  }

  /** Every problem with the controls of a catalog; empty when the catalog can run. */
  public static List<String> problems(ControlCatalog catalog) {
    var problems = new ArrayList<String>();
    var seen = new java.util.HashSet<String>();
    for (var control : catalog.controls()) {
      if (!seen.add(control.id())) problems.add(control.id() + ": the id is used twice");
      problems.addAll(problems(control));
    }
    return problems;
  }

  /** Every problem with one control, each prefixed with its id. */
  @SuppressWarnings("unchecked")
  public static List<String> problems(HumanControl control) {
    var out = new ArrayList<String>();
    var id = control.id();
    var hc = control.humanConfig();
    var patternId = hc.get("pattern");
    Pattern pattern;
    try {
      pattern = Pattern.of(String.valueOf(patternId));
    } catch (IllegalArgumentException e) {
      out.add(id + ": human_config.pattern " + patternId + " is not one of the fourteen patterns");
      return out;
    }
    var def = Verify.registries().pattern(pattern);
    if (!def.flavors().contains(control.flavor())) {
      out.add(id + ": flavor " + control.flavor() + " is not one " + pattern.id() + " runs under " + def.flavors());
    }
    try {
      Flavor.of(control.flavor());
    } catch (IllegalArgumentException e) {
      out.add(id + ": " + e.getMessage());
    }
    try {
      SubjectKind.of(String.valueOf(hc.get("subject_kind")));
    } catch (IllegalArgumentException e) {
      out.add(id + ": human_config.subject_kind " + hc.get("subject_kind") + " is not a subject kind");
    }
    var known = Verify.registries().decisions();
    if (hc.get("allowed_actions") instanceof List<?> actions) {
      if (actions.isEmpty()) out.add(id + ": allowed_actions is empty");
      for (var action : actions) {
        if (!isAction(action, known)) out.add(id + ": allowed action " + action + " is not in the decision registry");
      }
    }
    if (hc.get("timeout_default") != null && !isAction(hc.get("timeout_default"), known)) {
      out.add(id + ": timeout_default " + hc.get("timeout_default") + " is not in the decision registry");
    }
    if (hc.get("timeout_behaviour") != null && !TIMEOUTS.contains(String.valueOf(hc.get("timeout_behaviour")))) {
      out.add(id + ": timeout_behaviour " + hc.get("timeout_behaviour") + " is not one of " + TIMEOUTS);
    }
    if (hc.get("routing") instanceof Map<?, ?> routing) {
      var mode = routing.get("review_mode");
      if (mode != null && !REVIEW_MODES.contains(String.valueOf(mode))) {
        out.add(id + ": review_mode " + mode + " is not one of " + REVIEW_MODES);
      }
      if (routing.get("independence") instanceof List<?> rules) {
        for (var rule : rules) {
          if (!INDEPENDENCE.contains(String.valueOf(rule))) out.add(id + ": independence " + rule + " is not known");
        }
      }
    }
    if (hc.get("sticky_modes") instanceof List<?> modes) {
      for (var mode : modes) {
        try {
          Sticky.valueOf(String.valueOf(mode));
        } catch (IllegalArgumentException e) {
          out.add(id + ": sticky mode " + mode + " is not known");
        }
      }
    }
    for (var field : List.of("sla_target", "claim_ttl")) {
      if (hc.get(field) != null && !Durations.valid(String.valueOf(hc.get(field)))) {
        out.add(id + ": " + field + " " + hc.get(field) + " is not an ISO 8601 duration");
      }
    }
    if (hc.get("escalation_chain") instanceof List<?> chain) {
      for (var queue : chain) {
        var q = (Map<String, Object>) queue;
        if (q.get("queue") == null) out.add(id + ": an escalation queue has no name");
        if (q.get("after") != null && !Durations.valid(String.valueOf(q.get("after")))) {
          out.add(id + ": escalation after " + q.get("after") + " is not an ISO 8601 duration");
        }
      }
    }
    if (pattern == Pattern.HALT_RESTART && control.haltConfig().isEmpty()) {
      out.add(id + ": a halt-restart control needs halt_config");
    }
    if (out.isEmpty()) {
      var settings = resolve(control);
      if (settings.routing().roles().isEmpty() && settings.escalationChain().stream().allMatch(q -> q.roles().isEmpty())) {
        out.add(id + ": no roles decide this control (routing.roles or escalation_chain roles)");
      }
    }
    return out;
  }

  /** The control's settings, resolved. The control is assumed valid ({@link #problems(HumanControl)}). */
  @SuppressWarnings("unchecked")
  public static ControlSettings resolve(HumanControl control) {
    var verify = Verify.registries();
    var hc = control.humanConfig();
    var pattern = Pattern.of(String.valueOf(hc.get("pattern")));
    var def = verify.pattern(pattern);
    var merged = merge(merge(verify.sharedDefaults(), def.defaults()), hc);
    var routing = (Map<String, Object>) merged.getOrDefault("routing", Map.of());

    var allowed = hc.get("allowed_actions") instanceof List<?> listed
        ? listed.stream().map(a -> Action.valueOf(String.valueOf(a))).toList()
        : List.copyOf(def.actions().keySet());
    var labels = new LinkedHashMap<Action, String>();
    for (var action : allowed) labels.put(action, def.actions().getOrDefault(action, defaultLabel(action)));

    var mode = String.valueOf(routing.getOrDefault("review_mode", "SINGLE"));
    int required = intOf(routing.get("required"), 1);
    if (mode.equals("SINGLE")) required = 1;
    if (mode.equals("TWO_PERSON")) required = Math.max(2, required);
    var roles = strings(routing.get("roles"));
    var chain = new ArrayList<ControlSettings.Queue>();
    if (merged.get("escalation_chain") instanceof List<?> listed) {
      for (var item : listed) {
        var q = (Map<String, Object>) item;
        chain.add(new ControlSettings.Queue(String.valueOf(q.get("queue")), strings(q.get("roles")),
            q.get("after") == null ? null : String.valueOf(q.get("after"))));
      }
    }
    if (chain.isEmpty()) {
      chain.add(new ControlSettings.Queue(roles.isEmpty() ? control.id() : roles.getFirst(), roles, null));
    }
    var queue = chain.getFirst().queue();
    if (roles.isEmpty()) roles = chain.getFirst().roles();

    var stickyModes = strings(merged.get("sticky_modes")).stream().map(Sticky::valueOf).toList();
    var strength = new LinkedHashMap<String, String>();
    if (merged.get("strength") instanceof Map<?, ?> named) named.forEach((k, v) -> strength.put(String.valueOf(k), String.valueOf(v)));

    return new ControlSettings(
        new ControlSettings.ControlRef(control.id(), control.name(), control.corpusRef(),
            control.revision() > 0 ? control.revision() : 1),
        pattern,
        def.name(),
        control.flavor(),
        SubjectKind.of(String.valueOf(hc.get("subject_kind"))),
        def.blocking(),
        allowed,
        labels,
        new ControlSettings.Routing(queue, roles, strings(routing.get("groups")), strings(routing.get("attestations")),
            mode, required, routing.get("of") == null ? null : intOf(routing.get("of"), required),
            String.valueOf(routing.getOrDefault("split_vote", "DENY")), strings(routing.get("independence"))),
        (String) merged.get("accountable_owner"),
        merged.get("sla_target") == null ? null : String.valueOf(merged.get("sla_target")),
        merged.get("at_risk_at") instanceof Number n ? n.doubleValue() : 0.75,
        String.valueOf(merged.getOrDefault("claim_ttl", "PT30M")),
        merged.get("timeout_behaviour") == null ? null : String.valueOf(merged.get("timeout_behaviour")),
        merged.get("timeout_default") == null ? null : Action.valueOf(String.valueOf(merged.get("timeout_default"))),
        List.copyOf(chain),
        stickyModes,
        !Boolean.FALSE.equals(merged.get("rationale_required")),
        intOf(merged.get("minimum_review_seconds"), 0),
        String.valueOf(merged.getOrDefault("minimum_review", ControlSettings.ENFORCE)),
        String.valueOf(merged.getOrDefault("clock_while_sent_back", ControlSettings.RUNS)),
        String.valueOf(merged.getOrDefault("late_decision", "ACCEPT_BREACHED")),
        (String) merged.get("ai_recommendation"),
        String.valueOf(merged.getOrDefault("proof_event", "HumanOversightDecisionRecordedV2")),
        Boolean.TRUE.equals(merged.get("shadow")),
        strength,
        hc.get("trigger") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of(),
        control.haltConfig());
  }

  /** {@code over} laid over {@code base}; nested maps merge, anything else replaces. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
    var out = new LinkedHashMap<>(base);
    for (var entry : over.entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> nested && out.get(entry.getKey()) instanceof Map<?, ?> existing) {
        out.put(entry.getKey(), merge((Map<String, Object>) existing, (Map<String, Object>) nested));
      } else {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  private static boolean isAction(Object name, Set<Action> known) {
    try {
      return known.contains(Action.valueOf(String.valueOf(name)));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  private static int intOf(Object value, int otherwise) {
    return value instanceof Number n ? n.intValue() : otherwise;
  }

  private static Map<Action, String> defaultLabels() {
    var labels = new java.util.EnumMap<Action, String>(Action.class);
    labels.put(Action.APPROVE, "Approve");
    labels.put(Action.DENY, "Deny");
    labels.put(Action.EDIT, "Edit, then approve");
    labels.put(Action.REDIRECT, "Redirect");
    labels.put(Action.REPLAN, "Replan");
    labels.put(Action.ESCALATE, "Escalate");
    labels.put(Action.HALT, "Halt");
    labels.put(Action.RECUSE, "Recuse");
    labels.put(Action.REQUEST_MORE_INFO, "Ask for more");
    labels.put(Action.SEND_BACK, "Send back");
    labels.put(Action.DECLINE_SYSTEM, "Take over");
    labels.put(Action.OVERRIDE, "Override");
    labels.put(Action.DISREGARD, "Disregard");
    labels.put(Action.REVERSE, "Reverse");
    return labels;
  }
}
