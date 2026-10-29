package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One {@code ControlSettings} from {@code GET /oversight/controls}. The service answers settings already
 * resolved; the fallbacks here are Verify's shared defaults, used only when a field is absent.
 */
record Control(JsonNode json) {
  /** One queue of the escalation chain. */
  record Level(String queue, List<String> roles) {}

  String id() {
    return json.path("control").path("rowId").asText();
  }

  String name() {
    return Json.text(json.path("control").path("name"), id());
  }

  String pattern() {
    return json.path("pattern").asText();
  }

  String subjectKind() {
    return json.path("subjectKind").asText();
  }

  List<String> allowedActions() {
    return Json.strings(json.path("allowedActions"));
  }

  boolean allows(String action) {
    return allowedActions().contains(action);
  }

  Map<String, String> actionLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    json.path("actionLabels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
    return labels;
  }

  String queue() {
    return json.path("routing").path("queue").asText();
  }

  List<String> roles() {
    return Json.strings(json.path("routing").path("roles"));
  }

  List<String> attestations() {
    return Json.strings(json.path("routing").path("attestations"));
  }

  String reviewMode() {
    return Json.text(json.path("routing").path("reviewMode"), "SINGLE");
  }

  int required() {
    return json.path("routing").path("required").asInt(1);
  }

  /** The votes that settle an event: one under SINGLE, otherwise {@code required} (at least two). */
  int votes() {
    return "SINGLE".equals(reviewMode()) ? 1 : Math.max(2, required());
  }

  String splitVote() {
    return Json.text(json.path("routing").path("splitVote"), "DENY");
  }

  List<String> independence() {
    return Json.strings(json.path("routing").path("independence"));
  }

  String accountableOwner() {
    return Json.text(json.path("accountableOwner"));
  }

  String slaTarget() {
    return Json.text(json.path("slaTarget"));
  }

  boolean hasDeadline() {
    return slaTarget() != null;
  }

  String timeoutBehaviour() {
    return Json.text(json.path("timeoutBehaviour"));
  }

  String timeoutDefault() {
    return Json.text(json.path("timeoutDefault"));
  }

  /** The queues in escalation order; a control without a chain has one level, its routing queue. */
  List<Level> escalationChain() {
    List<Level> chain = new ArrayList<>();
    json.path("escalationChain").forEach(l -> chain.add(new Level(l.path("queue").asText(), Json.strings(l.path("roles")))));
    return chain.isEmpty() ? List.of(new Level(queue(), roles())) : chain;
  }

  List<String> sticky() {
    return Json.strings(json.path("sticky"));
  }

  boolean rationaleRequired() {
    return json.path("rationaleRequired").asBoolean(true);
  }

  int minimumReviewSeconds() {
    return json.path("minimumReviewSeconds").asInt(0);
  }

  String minimumReview() {
    return Json.text(json.path("minimumReview"), "ENFORCE");
  }

  String lateDecision() {
    return Json.text(json.path("lateDecision"), "ACCEPT_BREACHED");
  }

  /**
   * The roles that may exercise a halt: {@code halt_config.exerciser_personae} when the control carries it
   * (in either spelling), otherwise the routing roles.
   */
  List<String> exerciserRoles() {
    JsonNode halt = json.path("halt");
    for (String key : List.of("exerciser_personae", "exerciserPersonae", "exercisers")) {
      List<String> roles = Json.strings(halt.path(key));
      if (!roles.isEmpty()) return roles;
    }
    return roles();
  }

  /** The service or capability a halt-restart control stops and restarts ({@code halt_config.target}). */
  String haltTarget() {
    return Json.text(json.path("halt").path("target"));
  }

  /**
   * Whether two halt-restart controls act on one target. A service may exercise a halt under one control
   * (allowing HALT) and restart it under another (allowing APPROVE).
   */
  boolean sameHaltTarget(Control other) {
    return Patterns.HALT_RESTART.equals(pattern()) && Patterns.HALT_RESTART.equals(other.pattern())
        && Objects.equals(haltTarget(), other.haltTarget());
  }

  /** Every role the control names: routing, each escalation queue and the halt exercisers. */
  Set<String> allRoles() {
    Set<String> all = new LinkedHashSet<>(roles());
    escalationChain().forEach(l -> all.addAll(l.roles()));
    all.addAll(exerciserRoles());
    return all;
  }
}
