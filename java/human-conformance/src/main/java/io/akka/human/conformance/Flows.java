package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** The moves scenarios are built from: raising, claiming, deciding, voting and checking what settled. */
final class Flows {
  static final String RATIONALE = "Checked by the conformance suite.";
  static final String CHECK = "The subject matches what was requested.";
  static final String UNLISTED_CHECK = "A check the event does not list.";
  /** Statuses accepted from routes that create an event; the contract names the body, not the status. */
  static final int[] CREATED = {200, 201};

  private Flows() {}

  /** What a raise sets beyond the control's settings and the sample subject. */
  record RaiseSpec(String proposedBy, String builtBy, String firstReviewer, String dedupeKey, String matchKey) {
    static RaiseSpec plain() {
      return new RaiseSpec(null, null, null, null, null);
    }

    RaiseSpec withProposedBy(String subject) {
      return new RaiseSpec(subject, builtBy, firstReviewer, dedupeKey, matchKey);
    }

    RaiseSpec withBuiltBy(String subject) {
      return new RaiseSpec(proposedBy, subject, firstReviewer, dedupeKey, matchKey);
    }

    RaiseSpec withFirstReviewer(String subject) {
      return new RaiseSpec(proposedBy, builtBy, subject, dedupeKey, matchKey);
    }

    RaiseSpec withDedupeKey(String key) {
      return new RaiseSpec(proposedBy, builtBy, firstReviewer, key, matchKey);
    }

    RaiseSpec withMatchKey(String key) {
      return new RaiseSpec(proposedBy, builtBy, firstReviewer, dedupeKey, key);
    }
  }

  /** A raised event and the people independence bars from deciding it. */
  record Raised(JsonNode event, Set<String> barred) {
    String id() {
      return event.path("id").asText();
    }
  }

  /** A closed event to contest: the control it belongs to, who settles it, and whether that person may hear the appeal. */
  record ContestSource(Control control, List<Person> voters, boolean firstReviewerHearsAppeals) {}

  static String eventPath(String id) {
    return "/oversight/events/" + Http.encode(id);
  }

  static String pendingPath(Control c) {
    return "/oversight/events?state=pending&limit=200&control=" + Http.encode(c.id());
  }

  /** The routable queue of one reviewer: the pending events they can act on now. */
  static String routablePath(Control c, Person p) {
    return pendingPath(c) + "&roles=" + Http.encode(String.join(",", p.roles())) + "&reviewer=" + Http.encode(p.subject());
  }

  static boolean listed(JsonNode page, String id) {
    return Json.find(page.path("events"), e -> id.equals(e.path("id").asText())).isPresent();
  }

  /** Raised through the dev route; override events and halt requests have their own routes. */
  static boolean devRaisable(Control c) {
    return !Patterns.OVERRIDE.equals(c.pattern()) && !Patterns.HALT_RESTART.equals(c.pattern());
  }

  /** People barred from a plain raise: the requester of a restart, who proposes it. */
  static Set<String> defaultBarred(Target t) {
    if (!Patterns.HALT_RESTART.equals(t.control().pattern())) return Set.of();
    return t.registry().liftRequester(t.control()).map(p -> Set.of(p.subject())).orElse(Set.of());
  }

  /**
   * The action the suite settles an event with: {@code APPROVE} when allowed, except for a restart request,
   * which is kept halted with {@code DENY} so the outcome does not depend on the safe-state checks.
   */
  static String settleAction(Control c) {
    List<String> allowed = c.allowedActions();
    if (Patterns.HALT_RESTART.equals(c.pattern()) && allowed.contains("DENY")) return "DENY";
    if (allowed.contains("APPROVE")) return "APPROVE";
    return allowed.stream().filter(a -> !Patterns.NOT_SETTLING.contains(a)).findFirst()
        .orElse(allowed.isEmpty() ? "APPROVE" : allowed.getFirst());
  }

  static String overrideAction(Control c) {
    return Patterns.AFTER_OUTPUT.stream().filter(c::allows).findFirst().orElse(settleAction(c));
  }

  /**
   * An action the event does not allow. {@code RECUSE} is never chosen: whether an event that does not list
   * it still accepts it is left open by the contract.
   */
  static String disallowedAction(JsonNode event) {
    List<String> allowed = Json.strings(event.path("allowedActions"));
    return Patterns.ACTIONS.stream().filter(a -> !allowed.contains(a) && !"RECUSE".equals(a)).findFirst()
        .orElseThrow(() -> new Run.Skip("the event allows every action in the vocabulary"));
  }

  static List<String> checks(JsonNode event) {
    List<String> texts = new ArrayList<>();
    event.path("ask").path("checks").forEach(c -> texts.add(c.path("text").asText()));
    return texts;
  }

  static int minimumSeconds(JsonNode event, Control c) {
    return event.path("minimumReviewSeconds").asInt(c.minimumReviewSeconds());
  }

  static ObjectNode reviewer(Person p) {
    ObjectNode body = Json.object();
    body.set("reviewer", p.stamp());
    return body;
  }

  static ObjectNode decision(Person p, String action, String rationale, List<String> checks, String sticky,
      Instant presentedAt) {
    ObjectNode body = reviewer(p);
    body.put("action", action);
    if (rationale != null) body.put("rationale", rationale);
    if (checks != null) body.set("checks", Json.array(checks));
    if (sticky != null) body.put("sticky", sticky);
    if (presentedAt != null) body.put("presentedAt", presentedAt.toString());
    return body;
  }

  /** Raises an event of the target's control; a halt-restart control is halted and a restart requested. */
  static Raised raise(Run run, Target t, RaiseSpec spec) {
    Control c = t.control();
    if (Patterns.HALT_RESTART.equals(c.pattern())) return requestRestart(run, t, spec.proposedBy());
    ObjectNode body = Json.object();
    body.put("control", c.id());
    ObjectNode ask = body.putObject("ask");
    ask.put("question", "Conformance: " + c.name() + "?");
    ask.putArray("checks").addObject().put("text", CHECK).put("section", "subject");
    body.set("subject", Subjects.sample(c.subjectKind()));
    body.put("proposedBy", spec.proposedBy() == null ? Person.AGENT.subject() : spec.proposedBy());
    if (spec.builtBy() != null) body.put("builtBy", spec.builtBy());
    if (spec.firstReviewer() != null) body.put("firstReviewer", spec.firstReviewer());
    if (spec.dedupeKey() != null) body.put("dedupeKey", spec.dedupeKey());
    if (spec.matchKey() != null) body.put("matchKey", spec.matchKey());
    JsonNode event = run.post("raise an event of " + c.id(), "/oversight/dev/events", body, "HumanEvent", CREATED);
    Set<String> barred = new HashSet<>();
    Stream.of(spec.proposedBy(), spec.builtBy(), spec.firstReviewer()).filter(Objects::nonNull).forEach(barred::add);
    return new Raised(event, Set.copyOf(barred));
  }

  /**
   * Halts the control's target and asks to restart it under the control; the restart request is the
   * pending event. The halt is exercised under the control that allows HALT for the same target.
   */
  static Raised requestRestart(Run run, Target t, String requesterSubject) {
    Control c = t.control();
    Registry registry = t.registry();
    Person requester = requesterSubject == null
        ? registry.liftRequester(c).orElseThrow(() -> new Run.Skip("no person may exercise the halt"))
        : registry.find(requesterSubject).orElseThrow(
            () -> new IllegalArgumentException(requesterSubject + " is not in the registry"));
    Control exercise = exerciseControl(t);
    List<Person> halters = registry.exercisers(exercise);
    Person halter = halters.contains(requester) || halters.isEmpty() ? requester : halters.getFirst();
    halt(run, exercise, halter, false, CREATED);
    JsonNode lift = halt(run, c, requester, true, CREATED);
    return new Raised(lift, Set.of(requester.subject()));
  }

  /**
   * The control a halt of the target control's target is exercised under: the control itself when it
   * allows HALT, otherwise a halt-restart control of the same target that does, otherwise the control
   * itself, which the service then raises under its pair.
   */
  static Control exerciseControl(Target t) {
    Control c = t.control();
    if (c.allows("HALT")) return c;
    return t.controls().stream().filter(k -> k.allows("HALT") && c.sameHaltTarget(k)).findFirst().orElse(c);
  }

  /** The ids of the halt-restart controls a halt or restart of the target control's target may be raised under. */
  static Set<String> pairedIds(Target t) {
    Set<String> ids = new HashSet<>();
    ids.add(t.control().id());
    t.controls().stream().filter(k -> t.control().sameHaltTarget(k)).forEach(k -> ids.add(k.id()));
    return Set.copyOf(ids);
  }

  /** The halt in place on the control's target; matched by target when the control names one, else by control. */
  static Optional<JsonNode> haltOf(JsonNode page, Control c) {
    String target = c.haltTarget();
    return Json.find(page.path("halts"), h -> h.path("halted").asBoolean(false) && (target != null
        ? target.equals(h.path("target").asText())
        : c.id().equals(h.path("control").path("rowId").asText())));
  }

  static boolean halted(JsonNode page, Control c) {
    return haltOf(page, c).isPresent();
  }

  static JsonNode halt(Run run, Control c, Person by, boolean lift, int... expected) {
    return halt(run, lift ? "request a restart" : "halt", c, by, lift, expected);
  }

  static JsonNode halt(Run run, String label, Control c, Person by, boolean lift, int... expected) {
    ObjectNode body = reviewer(by);
    body.put("control", c.id());
    body.put("lift", lift);
    body.put("reason", lift ? "The safe state is restored." : "Halted by the conformance suite.");
    return run.post(label + " (" + by.subject() + ")", "/oversight/halts", body, "HumanEvent", expected);
  }

  static JsonNode claim(Run run, String id, Person p, int... expected) {
    return run.post("claim (" + p.subject() + ")", eventPath(id) + "/claim", reviewer(p), "HumanEvent", expected);
  }

  static JsonNode release(Run run, String id, Person p, int... expected) {
    return run.post("release (" + p.subject() + ")", eventPath(id) + "/release", reviewer(p), "HumanEvent", expected);
  }

  static JsonNode decide(Run run, String name, String id, ObjectNode body, int... expected) {
    return run.post(name, eventPath(id) + "/decision", body, "HumanEvent", expected);
  }

  static void advancePastMinimum(Run run, JsonNode event, Control c) {
    int seconds = minimumSeconds(event, c);
    if (seconds > 0) run.advance(Duration.ofSeconds(seconds + 1));
  }

  /**
   * Settles an event: the first voter claims, waits out the minimum review time and decides; under a
   * multi-person review mode the next voters follow with {@code presentedAt} until it settles.
   */
  static JsonNode settle(Run run, Control c, JsonNode event, String action, List<Person> voters, String sticky) {
    String id = event.path("id").asText();
    Person first = voters.getFirst();
    claim(run, id, first, 200);
    advancePastMinimum(run, event, c);
    JsonNode answer = decide(run, "decide " + action + " (" + first.subject() + ")", id,
        decision(first, action, RATIONALE, checks(event), sticky, null), 200);
    return completeVotes(run, c, event, answer, action, voters, 1, sticky);
  }

  /** Casts further votes, from {@code voters[next]} on, while the event awaits them. */
  static JsonNode completeVotes(Run run, Control c, JsonNode event, JsonNode answer, String action,
      List<Person> voters, int next, String sticky) {
    String id = event.path("id").asText();
    int index = next;
    JsonNode current = answer;
    while ("AWAITING_SECOND".equals(current.path("state").asText())) {
      if (index >= voters.size()) {
        throw run.fail("another vote", null, null,
            "the event awaits another vote and the registry has no further qualified reviewer");
      }
      Person voter = voters.get(index++);
      Instant shown = run.advance(Duration.ofSeconds(1));
      advancePastMinimum(run, event, c);
      current = decide(run, "vote " + action + " (" + voter.subject() + ")", id,
          decision(voter, action, RATIONALE, checks(event), sticky, shown), 200);
    }
    return current;
  }

  /**
   * Checks a settled event: closed as DECIDED or APPLIED with the outcome, its verdict, a
   * {@code HumanOversightDecisionRecordedV2} in the proof export, and a verdict in {@code /oversight/verdicts}.
   * A null {@code conclusion} leaves the verdict's conclusion unchecked.
   */
  static JsonNode verifySettled(Run run, String id, String action, String conclusion, String reason) {
    JsonNode event = awaitClosed(run, id);
    String state = event.path("state").asText();
    run.check("settled as DECIDED or APPLIED", Set.of("DECIDED", "APPLIED").contains(state), "state " + state);
    run.check("the outcome is " + action, action.equals(event.path("outcome").path("action").asText()),
        "outcome " + event.path("outcome"));
    verifyVerdict(run, event, conclusion, reason, true);
    run.awaitProof("the proof export records the decision",
        r -> "HumanOversightDecisionRecordedV2".equals(r.path("type").asText())
            && id.equals(r.path("payload").path("event_id").asText()),
        "HumanOversightDecisionRecordedV2 for " + id);
    awaitVerdictListed(run, event);
    return event;
  }

  static JsonNode awaitClosed(Run run, String id) {
    return run.await("the event closes with a verdict", eventPath(id), "HumanEvent",
        e -> !e.path("pending").asBoolean(true) && e.path("verdict").isObject(), id + " closed with a verdict");
  }

  static void verifyVerdict(Run run, JsonNode event, String conclusion, String reason, boolean rehearsal) {
    JsonNode verdict = event.path("verdict");
    String shown = "verdict " + Json.abbreviate(verdict.toString());
    run.check("the verdict names the event as its work item",
        event.path("id").asText().equals(verdict.path("detail").path("workItem").path("id").asText()), shown);
    run.check("the verdict's digest covers the record",
        Json.verdictDigest(verdict).equals(verdict.path("integrity").path("digest").asText()), shown);
    if (conclusion != null) {
      run.check("the verdict is " + conclusion, conclusion.equals(verdict.path("conclusion").asText()), shown);
    }
    if (reason != null) {
      run.check("the verdict gives the reason " + reason, reason.equals(verdict.path("reason").path("code").asText()),
          shown);
    }
    if (rehearsal) {
      run.check("the verdict's source is rehearsal",
          "rehearsal".equals(verdict.path("source").path("kind").asText()), shown);
    }
    String top = Json.text(event.path("conclusion"));
    if (top != null) {
      run.check("the event's conclusion matches its verdict", top.equals(verdict.path("conclusion").asText()),
          "conclusion " + top + ", " + shown);
    }
  }

  /**
   * Asks for the event's verdict by its id: verdicts are ordered by service time, so a page of the
   * newest can be filled by ones recorded while the dev clock was ahead.
   */
  static void awaitVerdictListed(Run run, JsonNode event) {
    String id = event.path("id").asText();
    String path = "/oversight/verdicts?eventId=" + Http.encode(id);
    JsonNode page = run.await("GET /oversight/verdicts lists the verdict", path, "verdicts[]:Verdict",
        p -> Json.find(p.path("verdicts"), v -> id.equals(v.path("detail").path("workItem").path("id").asText()))
            .isPresent(),
        "a verdict for " + id);
    run.check("the verdict page counts what it matches and says whether more follow",
        page.path("total").asInt(-1) >= page.path("verdicts").size() && page.path("hasMore").isBoolean()
            && page.has("nextOffset"),
        "page " + Json.abbreviate(page.toString()));
  }

  static void awaitListedPending(Run run, Control c, String id) {
    run.await("the event is listed as pending", pendingPath(c), "EventsPage", page -> listed(page, id),
        id + " among the pending events of " + c.id());
  }

  /** Clears the service: every event killed, halts and standing decisions cleared, nothing left pending. */
  static void reset(Run run) {
    JsonNode answer = run.post("reset the service", "/oversight/dev/reset", Json.object(), null, 200);
    run.check("reset answers how many it removed", answer != null && answer.path("removed").isIntegralNumber(),
        "answer " + answer);
    run.await("no event is left pending", "/oversight/events?state=pending&limit=1", "EventsPage",
        page -> page.path("total").asInt(-1) == 0, "a pending total of 0");
  }

  /**
   * A control whose decision the target's contest-appeal control can review, preferring one whose decider
   * also hears appeals, so the bar on the first reviewer can be observed.
   */
  static Optional<ContestSource> contestSource(Target t) {
    Control appeal = t.control();
    Registry registry = t.registry();
    List<Person> hearers = registry.deciders(appeal, Set.of(), settleAction(appeal));
    ContestSource fallback = null;
    for (Control k : t.controls()) {
      if (k.id().equals(appeal.id()) || !devRaisable(k) || Patterns.CONTEST_APPEAL.equals(k.pattern())) continue;
      List<Person> voters = registry.deciders(k, Set.of(), settleAction(k));
      if (voters.size() < k.votes()) continue;
      for (Person x : voters) {
        if (hearers.contains(x)) {
          List<Person> ordered = new ArrayList<>(voters);
          ordered.remove(x);
          ordered.addFirst(x);
          return Optional.of(new ContestSource(k, List.copyOf(ordered), true));
        }
      }
      if (fallback == null) fallback = new ContestSource(k, voters, false);
    }
    return Optional.ofNullable(fallback);
  }
}
