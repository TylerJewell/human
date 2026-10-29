package io.akka.human.conformance;

import static io.akka.human.conformance.Flows.CREATED;
import static io.akka.human.conformance.Flows.RATIONALE;
import static io.akka.human.conformance.Flows.UNLISTED_CHECK;
import static io.akka.human.conformance.Flows.advancePastMinimum;
import static io.akka.human.conformance.Flows.awaitClosed;
import static io.akka.human.conformance.Flows.awaitListedPending;
import static io.akka.human.conformance.Flows.awaitVerdictListed;
import static io.akka.human.conformance.Flows.checks;
import static io.akka.human.conformance.Flows.completeVotes;
import static io.akka.human.conformance.Flows.decide;
import static io.akka.human.conformance.Flows.decision;
import static io.akka.human.conformance.Flows.defaultBarred;
import static io.akka.human.conformance.Flows.devRaisable;
import static io.akka.human.conformance.Flows.disallowedAction;
import static io.akka.human.conformance.Flows.eventPath;
import static io.akka.human.conformance.Flows.haltOf;
import static io.akka.human.conformance.Flows.halted;
import static io.akka.human.conformance.Flows.listed;
import static io.akka.human.conformance.Flows.minimumSeconds;
import static io.akka.human.conformance.Flows.overrideAction;
import static io.akka.human.conformance.Flows.pairedIds;
import static io.akka.human.conformance.Flows.pendingPath;
import static io.akka.human.conformance.Flows.reviewer;
import static io.akka.human.conformance.Flows.routablePath;
import static io.akka.human.conformance.Flows.settle;
import static io.akka.human.conformance.Flows.settleAction;
import static io.akka.human.conformance.Flows.verifySettled;
import static io.akka.human.conformance.Flows.verifyVerdict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.human.conformance.Flows.ContestSource;
import io.akka.human.conformance.Flows.RaiseSpec;
import io.akka.human.conformance.Flows.Raised;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The scenarios run for each pattern, against the control the service declares for it. Each scenario's
 * skip reason says why the control's settings or the registry make it inapplicable.
 */
final class PatternScenarios {
  private PatternScenarios() {}

  static List<Scenario> all() {
    return List.of(
        new Scenario("raise", null, PatternScenarios::raiseSkip, PatternScenarios::raising),
        new Scenario("claim", null, t -> firstOf(raiseSkip(t), needDeciders(t, 1, settleAction(t.control()))),
            PatternScenarios::claiming),
        new Scenario("decide", null,
            t -> firstOf(raiseSkip(t), needDeciders(t, t.control().votes(), settleAction(t.control()))),
            PatternScenarios::deciding),
        new Scenario("independence", null, PatternScenarios::independenceSkip, PatternScenarios::independence),
        new Scenario("multi-vote", null, PatternScenarios::multiVoteSkip, PatternScenarios::multiVote),
        new Scenario("timeout", null, PatternScenarios::timeoutSkip, PatternScenarios::timeout),
        new Scenario("late-decision", null, PatternScenarios::lateSkip, PatternScenarios::lateDecision),
        new Scenario("escalate", null, PatternScenarios::escalateSkip, PatternScenarios::escalating),
        new Scenario("send-back", null, PatternScenarios::sendBackSkip, PatternScenarios::sendingBack),
        new Scenario("request-more-info", null, PatternScenarios::moreInfoSkip, PatternScenarios::moreInfo),
        new Scenario("recuse", null, t -> firstOf(raiseSkip(t), needDeciders(t, 1, settleAction(t.control()))),
            PatternScenarios::recusing),
        new Scenario("standing-decision", null, PatternScenarios::standingSkip, PatternScenarios::standing),
        new Scenario("dedupe", null, t -> devRaisable(t.control()) ? null : notDevRaisable(t.control()),
            PatternScenarios::dedupe),
        new Scenario("extend", null, PatternScenarios::extendSkip, PatternScenarios::extending),
        new Scenario("withdraw", null, PatternScenarios::raiseSkip, PatternScenarios::withdrawing),
        new Scenario("reopen", null, PatternScenarios::reopenSkip, PatternScenarios::reopening),
        new Scenario("flag", Patterns.FLAG_FEEDBACK, t -> needDeciders(t, 1, settleAction(t.control())),
            PatternScenarios::flagging),
        new Scenario("contest", Patterns.CONTEST_APPEAL, PatternScenarios::contestSkip, PatternScenarios::contesting),
        new Scenario("override", Patterns.OVERRIDE, t -> needDeciders(t, 1, overrideAction(t.control())),
            PatternScenarios::overriding),
        new Scenario("halt", Patterns.HALT_RESTART, PatternScenarios::haltSkip, PatternScenarios::halting),
        new Scenario("restart", Patterns.HALT_RESTART, PatternScenarios::restartSkip, PatternScenarios::restarting));
  }

  // ---- applicability

  static String firstOf(String... reasons) {
    for (String reason : reasons) {
      if (reason != null) return reason;
    }
    return null;
  }

  static String raiseSkip(Target t) {
    Control c = t.control();
    if (Patterns.OVERRIDE.equals(c.pattern())) {
      return "override events are decided at once through POST /oversight/overrides; the override scenario covers them";
    }
    if (Patterns.HALT_RESTART.equals(c.pattern())) {
      if (!c.allows("APPROVE")) {
        return "the halt-restart control allows no APPROVE: it only exercises halts, so it raises no restart request";
      }
      if (t.registry().exercisers(c).isEmpty()) {
        return "no qualified person in the registry holds a role that may exercise the halt " + c.exerciserRoles();
      }
    }
    return null;
  }

  static String notDevRaisable(Control c) {
    return Patterns.OVERRIDE.equals(c.pattern())
        ? "override events are decided at once through POST /oversight/overrides"
        : "halt-restart events are raised through POST /oversight/halts, which takes no dedupe or match key";
  }

  static String needDeciders(Target t, int needed, String action) {
    Control c = t.control();
    int have = t.registry().deciders(c, defaultBarred(t), action).size();
    if (have >= needed) return null;
    return "the registry has " + have + " qualified reviewer(s) for " + c.roles() + " who may " + action + "; "
        + needed + " needed";
  }

  static String independenceSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t), c.independence().isEmpty() ? "the control sets no independence rule" : null,
        needDeciders(t, 1, settleAction(c)));
  }

  static String multiVoteSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t),
        "SINGLE".equals(c.reviewMode()) ? "the review mode is SINGLE" : null,
        Patterns.HALT_RESTART.equals(c.pattern())
            ? "restart votes are exercised by the restart scenario, where approval depends on the safe-state checks"
            : null,
        c.allows("APPROVE") ? null : "the control does not allow APPROVE",
        needDeciders(t, c.votes(), "APPROVE"));
  }

  static String timeoutSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t), c.timeoutBehaviour() == null ? "the control has no timeout behaviour" : null,
        c.hasDeadline() ? null : "the control sets no deadline (slaTarget)");
  }

  static String lateSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t), c.hasDeadline() ? null : "the control sets no deadline (slaTarget)",
        needDeciders(t, c.votes(), settleAction(c)));
  }

  static String escalateSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t),
        c.escalationChain().size() < 2 ? "the control's escalation chain has no next queue" : null,
        needDeciders(t, 1, "ESCALATE"));
  }

  static String sendBackSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t), c.allows("SEND_BACK") ? null : "the control does not allow SEND_BACK",
        needDeciders(t, 1, "SEND_BACK"));
  }

  static String moreInfoSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t),
        c.allows("REQUEST_MORE_INFO") ? null : "the control does not allow REQUEST_MORE_INFO",
        needDeciders(t, 1, "REQUEST_MORE_INFO"));
  }

  static String standingSkip(Target t) {
    Control c = t.control();
    return firstOf(devRaisable(c) ? null : notDevRaisable(c),
        c.sticky().contains("APPROVE_FOR_PATTERN") ? null : "the control does not offer APPROVE_FOR_PATTERN",
        c.allows("APPROVE") ? null : "the control does not allow APPROVE",
        needDeciders(t, c.votes(), "APPROVE"));
  }

  static String extendSkip(Target t) {
    Control c = t.control();
    return firstOf(raiseSkip(t), c.hasDeadline() ? null : "the control sets no deadline (slaTarget)",
        needDeciders(t, 1, settleAction(c)));
  }

  static String reopenSkip(Target t) {
    Control c = t.control();
    String closing = Patterns.OVERRIDE.equals(c.pattern())
        ? needDeciders(t, 1, overrideAction(c))
        : firstOf(raiseSkip(t), needDeciders(t, c.votes(), settleAction(c)));
    return firstOf(closing, t.registry().accountable(c).isPresent() ? null
        : "the control's accountable owner (" + t.registry().accountableName(c) + ") is not a person or role in the registry");
  }

  static String contestSkip(Target t) {
    return firstOf(needDeciders(t, 1, settleAction(t.control())),
        Flows.contestSource(t).isPresent() ? null : "the service declares no other control whose decision could be contested");
  }

  static String haltSkip(Target t) {
    Control c = t.control();
    if (!c.allows("HALT")) return "the halt-restart control allows no HALT: it only restarts halts";
    return t.registry().exercisers(c).isEmpty()
        ? "no qualified person in the registry holds a role that may exercise the halt " + c.exerciserRoles()
        : null;
  }

  static String restartSkip(Target t) {
    return firstOf(raiseSkip(t), needDeciders(t, t.control().votes(), "APPROVE"));
  }

  // ---- scenarios

  /** Raise; the event carries the control's pattern and actions, is listed as pending, and routes to the right people. */
  static void raising(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    run.check("the event is pending", event.path("pending").asBoolean(false), "state " + event.path("state"));
    run.check("the pattern is " + c.pattern(), c.pattern().equals(event.path("pattern").asText()),
        "pattern " + event.path("pattern"));
    run.check("the control is " + c.id(), c.id().equals(event.path("control").path("rowId").asText()),
        "control " + event.path("control"));
    List<String> allowed = Json.strings(event.path("allowedActions"));
    if (Patterns.HALT_RESTART.equals(c.pattern())) {
      run.check("allowedActions are among the control's", c.allowedActions().containsAll(allowed),
          "event " + allowed + ", control " + c.allowedActions());
    } else {
      run.check("allowedActions match the control's", Set.copyOf(allowed).equals(Set.copyOf(c.allowedActions())),
          "event " + allowed + ", control " + c.allowedActions());
    }
    Map<String, String> labels = c.actionLabels();
    JsonNode eventLabels = event.path("actionLabels");
    List<String> differing = allowed.stream()
        .filter(a -> !Objects.equals(labels.get(a), Json.text(eventLabels.path(a)))).toList();
    run.check("actionLabels match the control's", differing.isEmpty(),
        "labels differ for " + differing + ": event " + eventLabels + ", control " + labels);
    awaitListedPending(run, c, id);
    JsonNode byPattern = run.get("pending events of the pattern",
        "/oversight/events?state=pending&limit=200&pattern=" + Http.encode(c.pattern()), "EventsPage");
    run.check("the pattern filter includes it", listed(byPattern, id), "not among " + byPattern.path("total") + " events");
    JsonNode read = run.get("read the event", eventPath(id), "HumanEvent");
    run.check("the read answers the event", id.equals(read.path("id").asText()), "id " + read.path("id"));

    Person qualified = registry.deciders(c, raised.barred(), settleAction(c)).getFirst();
    JsonNode mine = run.get("the routable queue of a qualified reviewer (" + qualified.subject() + ")",
        routablePath(c, qualified), "EventsPage");
    run.check("routable to a qualified reviewer", listed(mine, id), "not routable to " + qualified.subject());
    Optional<Person> withoutRole = registry.withoutRoles(c);
    if (withoutRole.isPresent()) {
      Person p = withoutRole.get();
      JsonNode theirs = run.get("the routable queue of a reviewer without the role (" + p.subject() + ")",
          routablePath(c, p), "EventsPage");
      run.check("not routable without the role", !listed(theirs, id), "routable to " + p.subject());
    } else {
      run.skipStep("not routable without the role", "everyone in the registry holds one of " + c.roles());
    }
    Optional<Person> unqualified = registry.unqualified(c);
    if (unqualified.isPresent()) {
      Person p = unqualified.get();
      JsonNode theirs = run.get("the routable queue of an unqualified reviewer (" + p.subject() + ")",
          routablePath(c, p), "EventsPage");
      run.check("not routable to an unqualified reviewer", !listed(theirs, id), "routable to " + p.subject());
    } else {
      run.skipStep("not routable to an unqualified reviewer", "the registry marks everyone qualified");
    }
  }

  /** Claims: refused without qualification or role, held by one reviewer at a time, released. */
  static void claiming(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    String id = raised.id();
    List<Person> deciders = registry.deciders(c, raised.barred(), settleAction(c));
    Person a = deciders.getFirst();
    Optional<Person> unqualified = registry.unqualified(c);
    if (unqualified.isPresent()) {
      Person u = unqualified.get();
      claimAs(run, u.holdsAny(c.roles()) ? "an unqualified holder of the role claims" : "an unqualified reviewer claims",
          id, u, 403);
    } else {
      run.skipStep("an unqualified reviewer claims", "the registry marks everyone qualified");
    }
    Optional<Person> withoutRole = registry.withoutRoles(c);
    if (withoutRole.isPresent()) {
      claimAs(run, "a reviewer without the role claims", id, withoutRole.get(), 403);
    } else {
      run.skipStep("a reviewer without the role claims", "everyone in the registry holds one of " + c.roles());
    }
    JsonNode held = claimAs(run, "a qualified reviewer claims", id, a, 200);
    run.check("the claimer holds the event", a.subject().equals(held.path("holder").path("subject").asText()),
        "holder " + held.path("holder"));
    run.check("a claimed event stays pending", held.path("pending").asBoolean(false), "state " + held.path("state"));
    if (deciders.size() > 1) {
      claimAs(run, "a second qualified reviewer claims", id, deciders.get(1), 409);
    } else {
      run.skipStep("a second qualified reviewer claims", "the registry has one qualified reviewer for " + c.roles());
    }
    JsonNode released = Flows.release(run, id, a, 200);
    run.check("release clears the holder", released.path("holder").isNull() || released.path("holder").isMissingNode(),
        "holder " + released.path("holder"));
    run.check("a released event stays pending", released.path("pending").asBoolean(false),
        "state " + released.path("state"));
    if (deciders.size() > 1) {
      Person b = deciders.get(1);
      JsonNode again = claimAs(run, "the second reviewer claims after the release", id, b, 200);
      run.check("the second reviewer holds the event", b.subject().equals(again.path("holder").path("subject").asText()),
          "holder " + again.path("holder"));
    }
  }

  /** Invalid decisions are refused with 422; a valid one settles with an upheld rehearsal verdict and a proof record. */
  static void deciding(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    String action = settleAction(c);
    List<Person> voters = t.registry().deciders(c, raised.barred(), action);
    Person a = voters.getFirst();
    List<String> checks = checks(event);
    Flows.claim(run, id, a, 200);
    int minimum = minimumSeconds(event, c);
    String mode = Json.text(event.path("minimumReview"), c.minimumReview());
    if (minimum > 0 && "ENFORCE".equals(mode)) {
      decide(run, "decide under the minimum review time of " + minimum + "s", id,
          decision(a, action, RATIONALE, checks, null, null), 422);
    } else {
      run.skipStep("decide under the minimum review time", minimum == 0
          ? "the event sets no minimum review time" : "minimum review is " + mode + ", not ENFORCE");
    }
    advancePastMinimum(run, event, c);
    String disallowed = disallowedAction(event);
    decide(run, "decide " + disallowed + ", which the event does not allow", id,
        decision(a, disallowed, RATIONALE, checks, null, null), 422);
    if (event.path("rationaleRequired").asBoolean(c.rationaleRequired())) {
      decide(run, "decide without the required rationale", id, decision(a, action, null, checks, null, null), 422);
    } else {
      run.skipStep("decide without the required rationale", "the event does not require a rationale");
    }
    decide(run, "decide confirming a check the event does not list", id,
        decision(a, action, RATIONALE, List.of(UNLISTED_CHECK), null, null), 422);
    JsonNode answer = decide(run, "decide " + action + " (" + a.subject() + ")", id,
        decision(a, action, RATIONALE, checks, null, null), 200);
    completeVotes(run, c, event, answer, action, voters, 1, null);
    verifySettled(run, id, action, "upheld", null);
  }

  /** Each independence rule the control sets bars the person it names from deciding. */
  static void independence(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    String action = settleAction(c);
    boolean halt = Patterns.HALT_RESTART.equals(c.pattern());
    List<Person> deciders = registry.deciders(c, List.of(), action);
    if (halt) deciders = deciders.stream().filter(registry.exercisers(c)::contains).toList();
    if (deciders.isEmpty()) throw new Run.Skip("no qualified reviewer may also request a restart");
    Person a = deciders.getFirst();
    int exercised = 0;
    for (String rule : c.independence()) {
      switch (rule) {
        case "proposer" -> {
          barred(run, t, rule, RaiseSpec.plain().withProposedBy(a.subject()), a, action);
          exercised++;
        }
        case "builder" -> {
          if (halt) {
            run.skipStep("builder", "a restart request names no builder");
          } else {
            barred(run, t, rule, RaiseSpec.plain().withBuiltBy(a.subject()), a, action);
            exercised++;
          }
        }
        case "first-reviewer" -> {
          if (halt) {
            run.skipStep("first-reviewer", "a restart request names no first reviewer");
          } else {
            barred(run, t, rule, RaiseSpec.plain().withFirstReviewer(a.subject()), a, action);
            exercised++;
          }
        }
        case "first-reviewer-line" -> {
          Optional<Registry.Line> line = halt ? Optional.empty() : registry.reportingLine(c, action);
          if (line.isPresent()) {
            barred(run, t, rule, RaiseSpec.plain().withFirstReviewer(line.get().first().subject()), line.get().line(),
                action);
            exercised++;
          } else {
            run.skipStep("first-reviewer-line",
                "no qualified reviewer for " + c.roles() + " is in a reporting line with another person");
          }
        }
        default -> run.skipStep(rule, "the suite cannot set this independence rule through a raise");
      }
    }
    if (exercised == 0) {
      throw new Run.Skip("no independence rule of " + c.independence() + " can be set through a raise");
    }
  }

  /** Raises with {@code barred} named by the rule; they are not routed the event and may not decide it. */
  private static void barred(Run run, Target t, String rule, RaiseSpec spec, Person barred, String action) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, spec);
    String id = raised.id();
    JsonNode independentOf = raised.event().path("routing").path("independentOf");
    if (independentOf.isArray()) {
      run.check("routing.independentOf names the " + rule, Json.strings(independentOf).contains(barred.subject()),
          "independentOf " + independentOf);
    }
    awaitListedPending(run, c, id);
    JsonNode theirs = run.get("the routable queue of the " + rule + " (" + barred.subject() + ")",
        routablePath(c, barred), "EventsPage");
    run.check("not routable to the " + rule, !listed(theirs, id), "routable to " + barred.subject());
    Http.Response claimed = run.send("the " + rule + " claims (" + barred.subject() + ")", "POST",
        eventPath(id) + "/claim", reviewer(barred), "HumanEvent", 200, 403);
    if (claimed.status() == 403) {
      run.note("the " + rule + " is refused", "refused at the claim");
      return;
    }
    advancePastMinimum(run, raised.event(), c);
    decide(run, "the " + rule + " decides (" + barred.subject() + ")", id,
        decision(barred, action, RATIONALE, checks(raised.event()), null, null), 403);
  }

  /** Under a multi-person review mode: a first vote awaits a second, one person votes once, a split vote settles by rule. */
  static void multiVote(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    List<Person> voters = t.registry().deciders(c, raised.barred(), "APPROVE");
    Person a = voters.getFirst();
    Flows.claim(run, id, a, 200);
    advancePastMinimum(run, event, c);
    JsonNode first = decide(run, "first vote APPROVE (" + a.subject() + ")", id,
        decision(a, "APPROVE", RATIONALE, checks(event), null, null), 200);
    run.check("the event awaits a second vote",
        "AWAITING_SECOND".equals(first.path("state").asText()) && first.path("pending").asBoolean(false),
        "state " + first.path("state"));
    Instant shown = run.advance(Duration.ofSeconds(1));
    advancePastMinimum(run, event, c);
    decide(run, "the same reviewer votes again (" + a.subject() + ")", id,
        decision(a, "APPROVE", RATIONALE, checks(event), null, shown), 409);
    completeVotes(run, c, event, first, "APPROVE", voters, 1, null);
    verifySettled(run, id, "APPROVE", "upheld", null);

    if (!c.allows("DENY")) {
      run.skipStep("a split vote", "the control does not allow DENY");
      return;
    }
    Raised split = Flows.raise(run, t, RaiseSpec.plain());
    String splitId = split.id();
    Flows.claim(run, splitId, a, 200);
    advancePastMinimum(run, split.event(), c);
    JsonNode opened = decide(run, "split: first vote APPROVE (" + a.subject() + ")", splitId,
        decision(a, "APPROVE", RATIONALE, checks(split.event()), null, null), 200);
    run.check("the split event awaits a second vote", "AWAITING_SECOND".equals(opened.path("state").asText()),
        "state " + opened.path("state"));
    Person b = voters.get(1);
    Instant shownToB = run.advance(Duration.ofSeconds(1));
    advancePastMinimum(run, split.event(), c);
    JsonNode answer = decide(run, "split: second vote DENY (" + b.subject() + ")", splitId,
        decision(b, "DENY", RATIONALE, checks(split.event()), null, shownToB), 200);
    String rule = Json.text(split.event().path("routing").path("splitVote"), c.splitVote());
    if ("ESCALATE".equals(rule)) {
      run.check("the split vote moves the event to the next queue",
          answer.path("pending").asBoolean(false) && answer.path("escalation").path("level").asInt(0) == 1,
          "state " + answer.path("state") + ", escalation " + answer.path("escalation"));
    } else {
      verifySettled(run, splitId, "DENY", null, null);
    }
  }

  /** Past the deadline, a tick applies the control's timeout behaviour and the verdict the contract gives it. */
  static void timeout(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    String behaviour = Json.text(event.path("sla").path("timeoutBehaviour"), c.timeoutBehaviour());
    run.check("sla.timeoutBehaviour is the control's " + c.timeoutBehaviour(), c.timeoutBehaviour().equals(behaviour),
        "sla " + event.path("sla"));
    if ("ESCALATE".equals(behaviour)) {
      escalateUntilTimedOut(run, c, event);
    } else {
      run.advancePast(dueAt(run, event));
      run.tick();
      JsonNode closed = awaitClosed(run, id);
      String state = closed.path("state").asText();
      String slaOutcome = closed.path("outcome").path("slaOutcome").asText();
      switch (behaviour) {
        case "RESUME_WITH_DEFAULT" -> {
          String fallback = Json.text(event.path("sla").path("default"), c.timeoutDefault());
          run.check("timed out", "TIMED_OUT".equals(state), "state " + state);
          run.check("resumed with the default " + fallback,
              Objects.equals(fallback, Json.text(closed.path("outcome").path("action"))), "outcome " + closed.path("outcome"));
          run.check("slaOutcome is TIMED_OUT", "TIMED_OUT".equals(slaOutcome), "outcome " + closed.path("outcome"));
          if ("APPROVE".equals(fallback)) {
            verifyVerdict(run, closed, "breached", "fail-open", false);
          } else {
            verifyVerdict(run, closed, "inconclusive", null, false);
          }
        }
        case "RAISE_TIMEOUT" -> {
          run.check("timed out", "TIMED_OUT".equals(state), "state " + state);
          run.check("slaOutcome is TIMED_OUT", "TIMED_OUT".equals(slaOutcome), "outcome " + closed.path("outcome"));
          verifyVerdict(run, closed, "inconclusive", null, false);
        }
        case "ABANDON" -> {
          run.check("abandoned", "ABANDONED".equals(state), "state " + state);
          run.check("slaOutcome is ABANDONED", "ABANDONED".equals(slaOutcome), "outcome " + closed.path("outcome"));
          verifyVerdict(run, closed, "inconclusive", null, false);
        }
        default -> throw run.fail("timeout behaviour", null, null, "unknown timeout behaviour " + behaviour);
      }
    }
    awaitVerdictListed(run, awaitClosed(run, id));
    if (Patterns.HALT_RESTART.equals(c.pattern())) {
      run.await("the halt stays in place", "/oversight/halts", "halts[]:Halt", page -> halted(page, c),
          c.id() + " still halted");
    }
  }

  private static Instant dueAt(Run run, JsonNode event) {
    Instant due = Json.instant(event.path("sla").path("dueAt"));
    run.check("the event has a deadline", due != null, "sla " + event.path("sla"));
    return due;
  }

  /** ESCALATE: each missed deadline moves the event one queue on; at the last queue it times out, breached. */
  private static void escalateUntilTimedOut(Run run, Control c, JsonNode raised) {
    String id = raised.path("id").asText();
    List<Control.Level> chain = c.escalationChain();
    JsonNode event = raised;
    int level = event.path("escalation").path("level").asInt(0);
    for (int round = 0; round <= chain.size(); round++) {
      run.advancePast(dueAt(run, event));
      run.tick();
      int from = level;
      event = run.await("the deadline at level " + from + " acts", eventPath(id), "HumanEvent",
          e -> !e.path("pending").asBoolean(true) || e.path("escalation").path("level").asInt(0) > from,
          "an escalation past level " + from + " or a timeout");
      if (!event.path("pending").asBoolean(true)) break;
      level = event.path("escalation").path("level").asInt(0);
      run.check("escalated one level", level == from + 1, "from level " + from + " to " + level);
      if (level < chain.size()) {
        String queue = chain.get(level).queue();
        run.check("routed to the next queue " + queue, queue.equals(event.path("routing").path("queue").asText()),
            "queue " + event.path("routing").path("queue"));
      }
    }
    run.check("timed out at the last queue", "TIMED_OUT".equals(event.path("state").asText()),
        "state " + event.path("state") + " at level " + level);
    run.check("escalated through every queue first", level == chain.size() - 1,
        "ended at level " + level + " of a " + chain.size() + "-queue chain");
    JsonNode closed = awaitClosed(run, id);
    run.check("slaOutcome is TIMED_OUT", "TIMED_OUT".equals(closed.path("outcome").path("slaOutcome").asText()),
        "outcome " + closed.path("outcome"));
    verifyVerdict(run, closed, "breached", "missed-deadline", false);
  }

  /** A decision after the deadline (no tick) is accepted and breached as late, or refused under REFUSE. */
  static void lateDecision(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    String action = settleAction(c);
    List<Person> voters = t.registry().deciders(c, raised.barred(), action);
    run.advancePast(dueAt(run, event));
    String policy = Json.text(event.path("sla").path("lateDecision"), c.lateDecision());
    if ("REFUSE".equals(policy)) {
      Person a = voters.getFirst();
      Http.Response claimed = run.send("claim past the deadline (" + a.subject() + ")", "POST",
          eventPath(id) + "/claim", reviewer(a), "HumanEvent", 200, 409);
      if (claimed.status() == 200) {
        advancePastMinimum(run, event, c);
        decide(run, "decide past the deadline under REFUSE", id,
            decision(a, action, RATIONALE, checks(event), null, null), 409);
      }
      return;
    }
    settle(run, c, event, action, voters, null);
    JsonNode settled = verifySettled(run, id, action, "breached", "late");
    run.check("slaOutcome is COMPLETED", "COMPLETED".equals(settled.path("outcome").path("slaOutcome").asText()),
        "outcome " + settled.path("outcome"));
  }

  /** The escalate route and the ESCALATE decision move the event to the next queue of the chain. */
  static void escalating(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Control.Level next = c.escalationChain().get(1);
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    String id = raised.id();
    Person a = registry.deciders(c, raised.barred(), "ESCALATE").getFirst();
    Flows.claim(run, id, a, 200);
    ObjectNode body = reviewer(a);
    body.put("reason", "This needs the next queue.");
    JsonNode escalated = run.post("escalate through the route (" + a.subject() + ")", eventPath(id) + "/escalate",
        body, "HumanEvent", 200);
    checkEscalated(run, escalated, next);
    List<Person> nextPeople = registry.qualifiedAt(c, next.roles(), raised.barred());
    if (nextPeople.isEmpty()) {
      run.skipStep("routable at the next queue", "no qualified person holds " + next.roles());
    } else {
      Person n = nextPeople.getFirst();
      run.await("routable at the next queue (" + n.subject() + ")", routablePath(c, n), "EventsPage",
          page -> listed(page, id), id + " in the routable queue of " + n.subject());
    }
    if (!c.allows("ESCALATE")) {
      run.skipStep("decide ESCALATE", "the control does not allow ESCALATE");
      return;
    }
    // A fresh start for the decision form: a halt-restart raise halts its target, which is still halted.
    Flows.reset(run);
    Raised second = Flows.raise(run, t, RaiseSpec.plain());
    Flows.claim(run, second.id(), a, 200);
    advancePastMinimum(run, second.event(), c);
    JsonNode decided = decide(run, "decide ESCALATE (" + a.subject() + ")", second.id(),
        decision(a, "ESCALATE", RATIONALE, checks(second.event()), null, null), 200);
    checkEscalated(run, decided, next);
  }

  private static void checkEscalated(Run run, JsonNode event, Control.Level next) {
    run.check("escalation leaves the event pending", event.path("pending").asBoolean(false),
        "state " + event.path("state"));
    run.check("at escalation level 1", event.path("escalation").path("level").asInt(-1) == 1,
        "escalation " + event.path("escalation"));
    run.check("routed to " + next.queue(), next.queue().equals(event.path("routing").path("queue").asText()),
        "queue " + event.path("routing").path("queue"));
  }

  /** Send-back puts the event with its proposer; only the proposer resubmits; SEND_BACK as a decision does the same. */
  static void sendingBack(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Person proposer = registry.proposer(c);
    Raised raised = Flows.raise(run, t, RaiseSpec.plain().withProposedBy(proposer.subject()));
    JsonNode event = raised.event();
    String id = raised.id();
    Person a = registry.deciders(c, raised.barred(), "SEND_BACK").getFirst();
    Flows.claim(run, id, a, 200);
    ObjectNode body = reviewer(a);
    body.put("requested", "Attach the evaluation report.");
    JsonNode sent = run.post("send back through the route (" + a.subject() + ")", eventPath(id) + "/send-back",
        body, "HumanEvent", 200);
    run.check("changes are requested",
        "CHANGES_REQUESTED".equals(sent.path("state").asText()) && sent.path("pending").asBoolean(false),
        "state " + sent.path("state"));
    run.await("the pending list shows it with the proposer", pendingPath(c), "EventsPage",
        page -> Json.find(page.path("events"), e -> id.equals(e.path("id").asText())
            && "CHANGES_REQUESTED".equals(e.path("state").asText())).isPresent(),
        id + " pending as CHANGES_REQUESTED");
    JsonNode theirs = run.get("the routable queue of the reviewer while it is with the proposer",
        routablePath(c, a), "EventsPage");
    run.check("not routable while with the proposer", !listed(theirs, id), "routable to " + a.subject());
    ObjectNode byReviewer = reviewer(a);
    byReviewer.put("note", "Resubmitting on their behalf.");
    run.post("resubmit by someone other than the proposer (" + a.subject() + ")", eventPath(id) + "/resubmit",
        byReviewer, null, 403);
    ObjectNode byProposer = reviewer(proposer);
    byProposer.put("note", "The report is attached.");
    JsonNode back = run.post("resubmit by the proposer (" + proposer.subject() + ")", eventPath(id) + "/resubmit",
        byProposer, "HumanEvent", 200);
    run.check("back in review",
        back.path("pending").asBoolean(false) && !"CHANGES_REQUESTED".equals(back.path("state").asText()),
        "state " + back.path("state"));
    Flows.claim(run, id, a, 200);
    advancePastMinimum(run, event, c);
    JsonNode decided = decide(run, "decide SEND_BACK (" + a.subject() + ")", id,
        decision(a, "SEND_BACK", "Add the rollback plan.", checks(event), null, null), 200);
    run.check("the SEND_BACK decision requests changes", "CHANGES_REQUESTED".equals(decided.path("state").asText()),
        "state " + decided.path("state"));
  }

  /** REQUEST_MORE_INFO keeps the event pending and records the question. */
  static void moreInfo(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    Person a = t.registry().deciders(c, raised.barred(), "REQUEST_MORE_INFO").getFirst();
    Flows.claim(run, id, a, 200);
    advancePastMinimum(run, event, c);
    JsonNode asked = decide(run, "decide REQUEST_MORE_INFO (" + a.subject() + ")", id,
        decision(a, "REQUEST_MORE_INFO", "Which purchase order does this match?", checks(event), null, null), 200);
    run.check("the event stays pending", asked.path("pending").asBoolean(false), "state " + asked.path("state"));
    JsonNode requests = asked.path("infoRequests");
    run.check("infoRequests records the request", requests.isArray() && !requests.isEmpty(), "infoRequests " + requests);
    JsonNode last = requests.get(requests.size() - 1);
    run.check("the request names the reviewer", a.subject().equals(last.path("by").asText()), "request " + last);
    run.check("the request carries the question", !last.path("question").asText("").isBlank(), "request " + last);
  }

  /** RECUSE releases the event and bars the reviewer from it. */
  static void recusing(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    String id = raised.id();
    List<Person> deciders = t.registry().deciders(c, raised.barred(), settleAction(c));
    Person a = deciders.getFirst();
    Flows.claim(run, id, a, 200);
    boolean listedRecuse = Json.strings(raised.event().path("allowedActions")).contains("RECUSE");
    Http.Response answer = run.send("decide RECUSE (" + a.subject() + ")", "POST", eventPath(id) + "/decision",
        decision(a, "RECUSE", "I approved a related payment.", null, null, null), "HumanEvent", 200, 422);
    if (answer.status() == 422) {
      if (listedRecuse) throw run.fail("RECUSE", null, 422, "the event lists RECUSE in allowedActions but refused it");
      throw new Run.Skip("the event's allowedActions omit RECUSE and the service refused it with 422;"
          + " the contract describes RECUSE but no pattern lists it");
    }
    JsonNode recused = answer.json();
    run.check("the event stays pending", recused.path("pending").asBoolean(false), "state " + recused.path("state"));
    run.check("RECUSE releases the event", recused.path("holder").isNull() || recused.path("holder").isMissingNode(),
        "holder " + recused.path("holder"));
    run.check("recused names the reviewer", Json.strings(recused.path("recused")).contains(a.subject()),
        "recused " + recused.path("recused"));
    claimAs(run, "the recused reviewer claims again", id, a, 403);
    awaitListedPending(run, c, id);
    JsonNode theirs = run.get("the routable queue of the recused reviewer", routablePath(c, a), "EventsPage");
    run.check("not routable to the recused reviewer", !listed(theirs, id), "routable to " + a.subject());
    if (deciders.size() > 1) {
      claimAs(run, "another reviewer claims", id, deciders.get(1), 200);
    } else {
      run.skipStep("another reviewer claims", "the registry has one qualified reviewer for " + c.roles());
    }
  }

  /** APPROVE_FOR_PATTERN creates a standing decision that settles matching raises until it is revoked. */
  static void standing(Run run, Target t) {
    Control c = t.control();
    String key = "conformance:" + UUID.randomUUID();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain().withMatchKey(key));
    String id = raised.id();
    List<Person> voters = t.registry().deciders(c, raised.barred(), "APPROVE");
    settle(run, c, raised.event(), "APPROVE", voters, "APPROVE_FOR_PATTERN");
    verifySettled(run, id, "APPROVE", "upheld", null);
    JsonNode list = run.await("GET /oversight/standing lists a standing decision on the match key",
        "/oversight/standing", "standing[]:StandingDecision", page -> standingOn(page, key).isPresent(),
        "a standing decision on " + key);
    JsonNode granted = standingOn(list, key).orElseThrow();
    String sd = granted.path("id").asText();
    run.check("the standing decision is ACTIVE", "ACTIVE".equals(granted.path("state").asText()),
        "state " + granted.path("state"));
    run.check("the standing decision names the event", id.equals(granted.path("eventId").asText()),
        "eventId " + granted.path("eventId"));

    Raised again = Flows.raise(run, t, RaiseSpec.plain().withMatchKey(key));
    JsonNode settled = run.await("a matching raise settles at once", eventPath(again.id()), "HumanEvent",
        e -> !e.path("pending").asBoolean(true), again.id() + " settled by " + sd);
    run.check("settled as DECIDED or APPLIED", Set.of("DECIDED", "APPLIED").contains(settled.path("state").asText()),
        "state " + settled.path("state"));
    run.check("the outcome is APPROVE", "APPROVE".equals(settled.path("outcome").path("action").asText()),
        "outcome " + settled.path("outcome"));
    run.check("settled by the standing decision", citesStanding(settled, sd),
        "outcome " + settled.path("outcome") + ", history " + Json.abbreviate(settled.path("history").toString()));

    ObjectNode revoke = reviewer(voters.getFirst());
    revoke.put("reason", "No longer needed.");
    JsonNode revoked = run.post("revoke the standing decision", "/oversight/standing/" + Http.encode(sd) + "/revoke",
        revoke, "StandingDecision", 200);
    run.check("the standing decision is REVOKED", "REVOKED".equals(revoked.path("state").asText()),
        "state " + revoked.path("state"));
    Raised after = Flows.raise(run, t, RaiseSpec.plain().withMatchKey(key));
    run.check("a matching raise after the revocation is pending", after.event().path("pending").asBoolean(false),
        "state " + after.event().path("state"));
    awaitListedPending(run, c, after.id());
  }

  private static Optional<JsonNode> standingOn(JsonNode page, String key) {
    return Json.find(page.path("standing"), s -> key.equals(s.path("matchKey").asText()));
  }

  private static boolean citesStanding(JsonNode event, String sd) {
    String actor = "standing:" + sd;
    return sd.equals(event.path("outcome").path("standingDecisionId").asText())
        || Json.find(event.path("history"), h -> actor.equals(h.path("actor").asText())).isPresent()
        || Json.find(event.path("decisions"), d -> actor.equals(d.path("reviewer").path("subject").asText())).isPresent();
  }

  /** Two raises with one dedupe key leave one pending event with two occurrences. */
  static void dedupe(Run run, Target t) {
    Control c = t.control();
    String key = "conformance:" + UUID.randomUUID();
    Raised first = Flows.raise(run, t, RaiseSpec.plain().withDedupeKey(key));
    Raised second = Flows.raise(run, t, RaiseSpec.plain().withDedupeKey(key));
    run.check("the second raise answers the pending event", first.id().equals(second.id()),
        "first " + first.id() + ", second " + second.id());
    JsonNode page = run.await("one pending event with two occurrences", pendingPath(c), "EventsPage",
        p -> Json.find(p.path("events"), e -> first.id().equals(e.path("id").asText())
            && e.path("trigger").path("occurrences").asInt(0) == 2).isPresent(),
        first.id() + " with trigger.occurrences 2");
    run.check("only one pending event", page.path("total").asInt(-1) == 1, "total " + page.path("total"));
  }

  /** A deadline extends once; a second extension is refused. */
  static void extending(Run run, Target t) {
    Control c = t.control();
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    String id = raised.id();
    Instant due = dueAt(run, raised.event());
    Person a = t.registry().deciders(c, raised.barred(), settleAction(c)).getFirst();
    ObjectNode body = reviewer(a);
    body.put("extra", "PT1H");
    body.put("reason", "Waiting on the supplier.");
    JsonNode extended = run.post("extend by PT1H (" + a.subject() + ")", eventPath(id) + "/extend", body,
        "HumanEvent", 200);
    Instant moved = Json.instant(extended.path("sla").path("dueAt"));
    run.check("the deadline moves by PT1H", due.plus(Duration.ofHours(1)).equals(moved), "dueAt " + due + " to " + moved);
    run.check("slaExtensions records the extension", extended.path("slaExtensions").size() == 1,
        "slaExtensions " + extended.path("slaExtensions"));
    run.post("extend a second time", eventPath(id) + "/extend", body, null, 409);
  }

  /** Only the proposer withdraws; a withdrawn event is closed, inconclusive. */
  static void withdrawing(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Person proposer = Patterns.HALT_RESTART.equals(c.pattern())
        ? registry.liftRequester(c).orElseThrow()
        : registry.proposer(c);
    Raised raised = Flows.raise(run, t, RaiseSpec.plain().withProposedBy(proposer.subject()));
    String id = raised.id();
    Optional<Person> outsider = registry.outsider(c, Set.of(proposer.subject()));
    if (outsider.isPresent()) {
      run.post("withdraw by someone with no part in the event (" + outsider.get().subject() + ")",
          eventPath(id) + "/withdraw", withReason(outsider.get(), "Not needed."), null, 403);
    } else {
      run.skipStep("withdraw by someone with no part in the event", "everyone in the registry has a part in " + c.id());
    }
    JsonNode withdrawn = run.post("withdraw by the proposer (" + proposer.subject() + ")", eventPath(id) + "/withdraw",
        withReason(proposer, "The request is no longer needed."), "HumanEvent", 200);
    run.check("the event is WITHDRAWN and closed",
        "WITHDRAWN".equals(withdrawn.path("state").asText()) && !withdrawn.path("pending").asBoolean(true),
        "state " + withdrawn.path("state"));
    JsonNode closed = awaitClosed(run, id);
    verifyVerdict(run, closed, "inconclusive", null, false);
    String slaOutcome = Json.text(closed.path("outcome").path("slaOutcome"));
    if (slaOutcome != null) run.check("slaOutcome is ABANDONED", "ABANDONED".equals(slaOutcome), "outcome " + closed.path("outcome"));
  }

  /** Only the accountable owner reopens a closed event; reopening raises a new event in {@code related}. */
  static void reopening(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    String id;
    if (Patterns.OVERRIDE.equals(c.pattern())) {
      String action = overrideAction(c);
      Person o = registry.deciders(c, Set.of(), action).getFirst();
      id = run.post("override (" + o.subject() + ")", "/oversight/overrides", overrideBody(o, c, action, RATIONALE),
          "HumanEvent", CREATED).path("id").asText();
    } else {
      Raised raised = Flows.raise(run, t, RaiseSpec.plain());
      id = raised.id();
      String action = settleAction(c);
      settle(run, c, raised.event(), action, registry.deciders(c, raised.barred(), action), null);
    }
    JsonNode closed = awaitClosed(run, id);
    List<String> before = relatedIds(closed);
    Person owner = registry.accountable(c).orElseThrow();
    Optional<Person> notOwner = registry.deciders(c, Set.of(), settleAction(c)).stream()
        .filter(p -> !registry.isAccountable(p, c)).findFirst()
        .or(() -> registry.people().stream().filter(p -> !registry.isAccountable(p, c)).findFirst());
    if (notOwner.isPresent()) {
      run.post("reopen by someone other than the accountable owner (" + notOwner.get().subject() + ")",
          eventPath(id) + "/reopen", withReason(notOwner.get(), "Second look."), null, 403);
    } else {
      run.skipStep("reopen by someone other than the accountable owner", "everyone is accountable for " + c.id());
    }
    JsonNode reopened = run.post("reopen by the accountable owner (" + owner.subject() + ")", eventPath(id) + "/reopen",
        withReason(owner, "New evidence arrived."), "HumanEvent", 200);
    run.check("the closed event is REOPENED", "REOPENED".equals(reopened.path("state").asText()),
        "state " + reopened.path("state"));
    List<String> added = new ArrayList<>(relatedIds(reopened));
    added.removeAll(before);
    added.remove(id);
    run.check("related names the new event", !added.isEmpty(), "related " + reopened.path("related"));
    String newId = added.getFirst();
    JsonNode fresh = run.await("the new event is pending", eventPath(newId), "HumanEvent",
        e -> e.path("pending").asBoolean(false), newId + " pending");
    run.check("the new event is of the same control", c.id().equals(fresh.path("control").path("rowId").asText()),
        "control " + fresh.path("control"));
  }

  private static List<String> relatedIds(JsonNode event) {
    List<String> ids = new ArrayList<>();
    event.path("related").forEach(r -> ids.add(r.path("eventId").asText()));
    return ids;
  }

  private static ObjectNode withReason(Person p, String reason) {
    ObjectNode body = reviewer(p);
    body.put("reason", reason);
    return body;
  }

  /** POST /oversight/flags raises a flag-feedback event that a triager settles. */
  static void flagging(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    String action = settleAction(c);
    List<String> triagers = registry.deciders(c, Set.of(), action).stream().map(Person::subject).toList();
    Person flagger = registry.outsider(c, Set.of())
        .or(() -> registry.others(triagers).findFirst())
        .orElse(Person.AGENT);
    ObjectNode body = reviewer(flagger);
    body.put("note", "The reply cites the wrong policy.");
    body.put("flowId", "conformance-flow-" + UUID.randomUUID().toString().substring(0, 8));
    body.put("output", "Your claim is covered under policy 7.");
    body.put("control", c.id());
    JsonNode event = run.post("flag an output (" + flagger.subject() + ")", "/oversight/flags", body, "HumanEvent",
        CREATED);
    String id = event.path("id").asText();
    run.check("a flag-feedback event of " + c.id(), Patterns.FLAG_FEEDBACK.equals(event.path("pattern").asText())
        && c.id().equals(event.path("control").path("rowId").asText()),
        "pattern " + event.path("pattern") + ", control " + event.path("control"));
    run.check("pending for triage", event.path("pending").asBoolean(false), "state " + event.path("state"));
    run.check("the subject is the flagged " + c.subjectKind(), c.subjectKind().equals(event.path("subject").path("kind").asText()),
        "subject kind " + event.path("subject").path("kind"));
    awaitListedPending(run, c, id);
    List<Person> voters = registry.deciders(c, Set.of(flagger.subject()), action);
    if (voters.size() < c.votes()) {
      run.skipStep("triage the flag", "no qualified triager other than the flagger");
      return;
    }
    settle(run, c, event, action, voters, null);
    verifySettled(run, id, action, "upheld", null);
  }

  /** POST /oversight/contests raises a contest-appeal event that the first reviewer may not hear. */
  static void contesting(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    ContestSource source = Flows.contestSource(t).orElseThrow(() -> new Run.Skip("no other control to contest"));
    Raised original = Flows.raise(run, new Target(source.control(), registry, t.controls()), RaiseSpec.plain());
    settle(run, source.control(), original.event(), settleAction(source.control()), source.voters(), null);
    awaitClosed(run, original.id());
    Person first = source.voters().getFirst();
    Person filer = registry.outsider(c, Set.of(first.subject()))
        .or(() -> registry.others(Set.of(first.subject())).findFirst())
        .orElse(Person.AGENT);
    ObjectNode body = reviewer(filer);
    body.put("control", c.id());
    body.put("ref", original.id());
    body.put("grounds", "My income was misread.");
    body.putObject("appellant").put("ref", "conformance-appellant").put("name", "Conformance appellant");
    JsonNode appeal = run.post("contest the decision on " + original.id() + " (" + filer.subject() + ")",
        "/oversight/contests", body, "HumanEvent", CREATED);
    String id = appeal.path("id").asText();
    run.check("a contest-appeal event of " + c.id(), Patterns.CONTEST_APPEAL.equals(appeal.path("pattern").asText())
        && c.id().equals(appeal.path("control").path("rowId").asText()),
        "pattern " + appeal.path("pattern") + ", control " + appeal.path("control"));
    run.check("pending for review", appeal.path("pending").asBoolean(false), "state " + appeal.path("state"));
    run.check("the subject is an appeal", "appeal".equals(appeal.path("subject").path("kind").asText()),
        "subject kind " + appeal.path("subject").path("kind"));
    awaitListedPending(run, c, id);
    if (!source.firstReviewerHearsAppeals()) {
      run.skipStep("the first reviewer is barred", "no reviewer of " + source.control().id()
          + " also hears appeals for " + c.id());
      return;
    }
    claimAs(run, "the first reviewer claims the appeal", id, first, 403);
    Optional<Person> other = registry.deciders(c, Set.of(first.subject(), filer.subject()), settleAction(c)).stream()
        .findFirst();
    if (other.isPresent()) {
      claimAs(run, "another appeals reviewer claims", id, other.get(), 200);
    } else {
      run.skipStep("another appeals reviewer claims", "no other qualified reviewer for " + c.roles());
    }
  }

  /** POST /oversight/overrides records an override decided at once, with its rationale. */
  static void overriding(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    String action = overrideAction(c);
    Person o = registry.deciders(c, Set.of(), action).getFirst();
    Optional<Person> withoutRole = registry.withoutRoles(c);
    if (withoutRole.isPresent()) {
      run.post("override by someone without the role (" + withoutRole.get().subject() + ")", "/oversight/overrides",
          overrideBody(withoutRole.get(), c, action, RATIONALE), null, 403);
    } else {
      run.skipStep("override by someone without the role", "everyone in the registry holds one of " + c.roles());
    }
    if (c.rationaleRequired()) {
      run.post("override without a rationale", "/oversight/overrides", overrideBody(o, c, action, null), null, 422);
    } else {
      run.skipStep("override without a rationale", "the control does not require a rationale");
    }
    String disallowed = Patterns.ACTIONS.stream().filter(a -> !c.allows(a) && !"RECUSE".equals(a)).findFirst()
        .orElse(null);
    if (disallowed != null) {
      run.post("override with " + disallowed + ", which the control does not allow", "/oversight/overrides",
          overrideBody(o, c, disallowed, RATIONALE), null, 422);
    }
    JsonNode event = run.post(action + " (" + o.subject() + ")", "/oversight/overrides",
        overrideBody(o, c, action, RATIONALE), "HumanEvent", CREATED);
    String id = event.path("id").asText();
    run.check("an override event of " + c.id(), Patterns.OVERRIDE.equals(event.path("pattern").asText())
        && c.id().equals(event.path("control").path("rowId").asText()),
        "pattern " + event.path("pattern") + ", control " + event.path("control"));
    run.check("decided at once", !event.path("pending").asBoolean(true), "state " + event.path("state"));
    JsonNode settled = verifySettled(run, id, action, "upheld", null);
    JsonNode decisions = settled.path("decisions");
    JsonNode last = decisions.isArray() && !decisions.isEmpty() ? decisions.get(decisions.size() - 1) : null;
    run.check("the decision records the person and the rationale", last != null
        && o.subject().equals(last.path("reviewer").path("subject").asText())
        && !last.path("rationale").asText("").isBlank(), "decisions " + Json.abbreviate(decisions.toString()));
  }

  private static ObjectNode overrideBody(Person p, Control c, String action, String rationale) {
    ObjectNode body = reviewer(p);
    body.put("control", c.id());
    body.put("ref", "conformance-output-" + UUID.randomUUID().toString().substring(0, 8));
    body.put("action", action);
    if (rationale != null) body.put("rationale", rationale);
    if ("OVERRIDE".equals(action)) body.put("replacement", "Your application is approved.");
    return body;
  }

  /**
   * One authorised person halts at once, under an exercise-capable control; the halt is listed on its
   * target and HaltExercised is appended.
   */
  static void halting(Run run, Target t) {
    Control c = t.control();
    Registry registry = t.registry();
    Set<String> paired = pairedIds(t);
    Person exerciser = registry.exercisers(c).getFirst();
    run.await("no halt is in place after the reset", "/oversight/halts", "halts[]:Halt", page -> !halted(page, c),
        "no halted entry for " + haltName(c));
    Optional<Person> unauthorised = registry.people().stream().filter(p -> !p.holdsAny(c.exerciserRoles())).findFirst();
    if (unauthorised.isPresent()) {
      Flows.halt(run, "halt by someone who may not exercise it", c, unauthorised.get(), false, 403);
    } else {
      run.skipStep("halt by someone who may not exercise it", "everyone holds one of " + c.exerciserRoles());
    }
    Predicate<JsonNode> exercised = r -> "HaltExercised".equals(r.path("type").asText())
        && paired.contains(r.path("payload").path("control_id").asText())
        && exerciser.subject().equals(r.path("payload").path("exerciser_id").asText());
    long before = run.countProof("HaltExercised records before the halt", exercised);
    JsonNode event = Flows.halt(run, c, exerciser, false, CREATED);
    String id = event.path("id").asText();
    run.check("a halt-restart event of a control of " + haltName(c),
        Patterns.HALT_RESTART.equals(event.path("pattern").asText())
            && paired.contains(event.path("control").path("rowId").asText()),
        "pattern " + event.path("pattern") + ", control " + event.path("control") + ", expected one of " + paired);
    run.check("the requester's HALT is recorded at once", Json.find(event.path("decisions"),
            d -> "HALT".equals(d.path("action").asText())
                && exerciser.subject().equals(d.path("reviewer").path("subject").asText())).isPresent(),
        "decisions " + Json.abbreviate(event.path("decisions").toString()));
    JsonNode halts = run.await("GET /oversight/halts shows the halt", "/oversight/halts", "halts[]:Halt",
        page -> halted(page, c), haltName(c) + " halted");
    JsonNode entry = haltOf(halts, c).orElseThrow();
    if (entry.hasNonNull("by")) {
      run.check("the halt names who exercised it", exerciser.subject().equals(entry.path("by").asText()),
          "by " + entry.path("by"));
    }
    run.awaitProof("the proof export records HaltExercised", exercised,
        "a new HaltExercised under one of " + paired + " by " + exerciser.subject(), before);
    verifySettled(run, id, "HALT", "upheld", null);
  }

  private static String haltName(Control c) {
    return c.haltTarget() == null ? c.id() : "target " + c.haltTarget();
  }

  /**
   * A restart request, named on a restart-capable control, needs the review mode's votes and every
   * safe-state check passing; approval lifts the halt and appends HaltLifted. The service may raise the
   * request under another halt-restart control of the same target; the votes then follow that control.
   * While a check fails, approval is refused and the lift is not attempted.
   */
  static void restarting(Run run, Target t) {
    Set<String> paired = pairedIds(t);
    Raised raised = Flows.raise(run, t, RaiseSpec.plain());
    JsonNode event = raised.event();
    String id = raised.id();
    String raisedUnder = event.path("control").path("rowId").asText();
    run.check("the restart is raised under a halt-restart control of " + haltName(t.control()),
        paired.contains(raisedUnder), "control " + raisedUnder + ", expected one of " + paired);
    Control c = t.controls().stream().filter(k -> k.id().equals(raisedUnder)).findFirst().orElse(t.control());
    run.check("the restart request is pending", event.path("pending").asBoolean(false), "state " + event.path("state"));
    run.check("the request asks to lift the halt", event.path("subject").path("detail").path("lift").asBoolean(false),
        "detail " + Json.abbreviate(event.path("subject").path("detail").toString()));
    List<Person> voters = t.registry().deciders(c, raised.barred(), "APPROVE");
    Person a = voters.getFirst();
    Flows.claim(run, id, a, 200);
    advancePastMinimum(run, event, c);
    List<String> failing = failingChecks(event);
    if (!failing.isEmpty()) {
      Http.Response vote = run.send("vote APPROVE while a safe-state check fails " + failing + " (" + a.subject() + ")",
          "POST", eventPath(id) + "/decision", decision(a, "APPROVE", RATIONALE, checks(event), null, null),
          "HumanEvent", 200, 409, 422);
      if (vote.status() == 200) {
        boolean awaiting = "AWAITING_SECOND".equals(vote.json().path("state").asText());
        if (!awaiting || voters.size() < 2) {
          throw run.fail("approval refused while a safe-state check fails", null, 200,
              "the restart was approved with failing checks " + failing + "; state " + vote.json().path("state"));
        }
        Person b = voters.get(1);
        Instant shown = run.advance(Duration.ofSeconds(1));
        advancePastMinimum(run, event, c);
        decide(run, "settling vote APPROVE while a safe-state check fails (" + b.subject() + ")", id,
            decision(b, "APPROVE", RATIONALE, checks(event), null, shown), 409, 422);
      }
      run.skipStep("lift the halt", "the service reports failing safe-state checks " + failing
          + "; the restart cannot be approved through the contract");
      return;
    }
    run.skipStep("approval refused while a safe-state check fails", "the service exposes no failing safe-state check");
    Predicate<JsonNode> lifted = r -> "HaltLifted".equals(r.path("type").asText())
        && paired.contains(r.path("payload").path("control_id").asText());
    long before = run.countProof("HaltLifted records before the restart", lifted);
    JsonNode first = decide(run, "first vote APPROVE (" + a.subject() + ")", id,
        decision(a, "APPROVE", RATIONALE, checks(event), null, null), 200);
    if (c.votes() > 1) {
      run.check("the restart awaits a second vote", "AWAITING_SECOND".equals(first.path("state").asText()),
          "state " + first.path("state"));
      Instant shown = run.advance(Duration.ofSeconds(1));
      advancePastMinimum(run, event, c);
      decide(run, "the same reviewer votes again (" + a.subject() + ")", id,
          decision(a, "APPROVE", RATIONALE, checks(event), null, shown), 409);
    }
    completeVotes(run, c, event, first, "APPROVE", voters, 1, null);
    verifySettled(run, id, "APPROVE", "upheld", null);
    run.await("GET /oversight/halts shows the halt lifted", "/oversight/halts", "halts[]:Halt",
        page -> !halted(page, c), haltName(c) + " no longer halted");
    run.awaitProof("the proof export records HaltLifted", lifted, "a new HaltLifted under one of " + paired, before);
  }

  private static List<String> failingChecks(JsonNode event) {
    List<String> failing = new ArrayList<>();
    event.path("subject").path("detail").path("safeStateChecks").forEach(check -> {
      if (!check.path("passed").asBoolean(false)) failing.add(check.path("name").asText());
    });
    return failing;
  }

  private static JsonNode claimAs(Run run, String label, String id, Person p, int... expected) {
    return run.post(label + " (" + p.subject() + ")", eventPath(id) + "/claim", reviewer(p), "HumanEvent", expected);
  }
}
