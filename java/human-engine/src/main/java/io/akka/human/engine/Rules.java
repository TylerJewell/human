package io.akka.human.engine;

import static io.akka.human.engine.HumanEvent.SYSTEM;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Sticky;
import io.akka.human.engine.HumanEvent.Outcome;
import io.akka.human.engine.HumanEvent.Vote;
import io.akka.human.engine.Qualification.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every rule about who may do what to an event, and when. Each answers the events to record or a
 * {@link Refusal}, so a caller can tell "not yours to decide" (403) from "already decided" (409) from
 * "the decision is not valid" (422). The time is passed in.
 */
public final class Rules {

  /**
   * A decision as the contract's {@code DecisionRequest} carries it.
   *
   * @param presentedAt when a later voter was shown the event; a decision without a claim counts its
   *     review time from it
   * @param replacement OVERRIDE: the output that replaces the system's
   */
  public record DecisionRequest(
      Reviewer reviewer,
      Action action,
      String rationale,
      Map<String, Object> edits,
      Sticky sticky,
      Instant presentedAt,
      List<String> checks,
      String replacement,
      List<Map<String, Object>> conditions) {

    public DecisionRequest {
      checks = checks == null ? List.of() : List.copyOf(checks);
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** A decision that carries no conditions. */
    public DecisionRequest(Reviewer reviewer, Action action, String rationale, Map<String, Object> edits, Sticky sticky,
        Instant presentedAt, List<String> checks, String replacement) {
      this(reviewer, action, rationale, edits, sticky, presentedAt, checks, replacement, List.of());
    }
  }

  public static final Duration MAX_EXTENSION = Duration.ofDays(7);
  private static final int MAX_TEXT = 4000;
  private static final List<String> ADMIN = List.of("admin");

  private Rules() {}

  // ---- holding the event

  public static Checked<List<HumanEventEvent>> claim(HumanEvent e, Actor actor, Instant now) {
    var gate = routedAndPending(e, now);
    if (gate != null) return Checked.refuse(gate);
    var late = refusedAsLate(e, now);
    if (late != null) return Checked.refuse(late);
    var who = refusedActor(e, actor, null, now);
    if (who != null) return Checked.refuse(who);
    var subject = actor.reviewer().subject();
    var holder = e.holderAt(now);
    if (holder != null && !holder.subject().equals(subject)) {
      return Checked.refuse(Refusal.conflict("claimed by another reviewer", e.stateAt(now).name()));
    }
    if (e.votedInRound(subject)) return Checked.refuse(Refusal.conflict("you have already voted in this round", e.state().name()));
    return Checked.events(new HumanEventEvent.Claimed(actor.reviewer(), now, now.plus(e.settings().claimDuration())));
  }

  public static Checked<List<HumanEventEvent>> release(HumanEvent e, Reviewer reviewer, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    var holder = e.holderAt(now);
    if (holder == null) return Checked.refuse(Refusal.conflict("the event is not claimed", e.stateAt(now).name()));
    if (!holder.subject().equals(reviewer.subject()) && !reviewer.holdsAnyOf(ADMIN)) {
      return Checked.refuse(Refusal.forbidden("claimed by another reviewer"));
    }
    return Checked.events(new HumanEventEvent.Released(reviewer.subject(), "Released", now));
  }

  // ---- deciding

  /** A decision on an event nothing blocks. */
  public static Checked<List<HumanEventEvent>> decide(
      HumanEvent e, Actor actor, DecisionRequest r, Map<String, Boolean> safeState, Instant now) {
    return decide(e, actor, r, safeState, null, now);
  }

  /**
   * A decision. ESCALATE, SEND_BACK, REQUEST_MORE_INFO and RECUSE move the event without settling it;
   * every other allowed action is a vote under the control's review mode. While an oversight-quality
   * event blocks the control, a vote is refused; exercising a halt never is.
   *
   * @param safeState a halt target's safe-state checks as they stand now, for a restart; empty otherwise
   * @param blockedBy the oversight-quality event that blocks the event's control ({@link Blocks}), or null
   */
  public static Checked<List<HumanEventEvent>> decide(HumanEvent e, Actor actor, DecisionRequest r,
      Map<String, Boolean> safeState, String blockedBy, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    var action = r.action();
    if (action == null) return Checked.refuse(Refusal.invalid("an action is required"));
    if (action != Action.RECUSE && !e.settings().allowedActions().contains(action)) {
      return Checked.refuse(Refusal.invalid("this event does not allow " + action + "; it allows "
          + e.settings().allowedActions()));
    }
    switch (action) {
      case ESCALATE -> {
        return escalate(e, actor, r.rationale(), now);
      }
      case SEND_BACK -> {
        return sendBack(e, actor, r.rationale(), now);
      }
      case REQUEST_MORE_INFO -> {
        return requestMoreInfo(e, actor, r.rationale(), now);
      }
      case RECUSE -> {
        return recuse(e, actor.reviewer(), r.rationale(), now);
      }
      default -> {}
    }
    var haltProblem = haltProblem(e, action);
    if (haltProblem != null) return Checked.refuse(Refusal.invalid(haltProblem));
    if (blockedBy != null && action != Action.HALT) {
      var control = e.settings().control().rowId();
      return Checked.refuse(Refusal.blocked(control + " is blocked by oversight-quality event " + blockedBy
          + "; its events can be decided once that event is settled with Reset, Retrain or Reassign",
          e.stateAt(now).name(), blockedBy));
    }
    var gate = routedAndPending(e, now);
    if (gate != null) return Checked.refuse(gate);
    var lateRefusal = refusedAsLate(e, now);
    if (lateRefusal != null) return Checked.refuse(lateRefusal);
    var who = refusedActor(e, actor, action, now);
    if (who != null) return Checked.refuse(who);
    var reviewer = actor.reviewer();
    if (e.votedInRound(reviewer.subject())) {
      return Checked.refuse(Refusal.conflict("you have already voted in this round", e.state().name()));
    }

    // A decision needs a claim, because the claim is when the person is shown the event and the minimum
    // review time counts from it. A later vote says when it was shown the event, and a control that
    // measures no review time needs no claim. Exercising a halt needs neither a claim nor a review time:
    // one authorised person stops the target at once.
    boolean exercise = action == Action.HALT;
    var holder = e.holderAt(now);
    Instant shownAt;
    if (exercise) {
      shownAt = now;
    } else if (holder != null) {
      if (!holder.subject().equals(reviewer.subject())) {
        return Checked.refuse(Refusal.conflict("claimed by another reviewer", e.stateAt(now).name()));
      }
      shownAt = holder.since();
    } else if (!e.roundVotes().isEmpty() || e.settings().minimumReviewSeconds() == 0) {
      shownAt = r.presentedAt() != null && !r.presentedAt().isAfter(now) ? r.presentedAt() : now;
    } else {
      return Checked.refuse(Refusal.conflict("claim the event first", e.stateAt(now).name()));
    }

    var problems = requestProblems(e, r);
    if (!problems.isEmpty()) {
      return Checked.refuse(new Refusal(Refusal.Kind.INVALID, problems.getFirst(), null, problems));
    }
    if (e.settings().haltRestart() && e.lift() && action == Action.APPROVE) {
      var failing = safeState.entrySet().stream().filter(c -> !c.getValue()).map(Map.Entry::getKey).toList();
      if (!failing.isEmpty()) {
        return Checked.refuse(Refusal.conflict("the restart needs every safe-state check to pass; failing: " + failing,
            e.state().name()));
      }
    }
    long seconds = Math.max(0, Duration.between(shownAt, now).toSeconds());
    int minimum = exercise ? 0 : e.settings().minimumReviewSeconds();
    boolean below = minimum > 0 && seconds < minimum;
    if (below && ControlSettings.ENFORCE.equals(e.settings().minimumReview())) {
      return Checked.refuse(Refusal.invalid("the minimum review time is " + minimum + " seconds; this review took "
          + seconds));
    }
    var vote = new Vote(reviewer, action, trim(r.rationale()), r.edits(), r.sticky(), r.replacement(), shownAt, now,
        seconds, below, pastDue(e, now), r.checks(), r.conditions());
    var events = new ArrayList<HumanEventEvent>();
    if (e.settings().haltRestart() && !safeState.isEmpty()) {
      events.add(new HumanEventEvent.SafeStateChecked(safeState, now));
    }
    events.add(tally(e, vote, now));
    return Checked.ok(events);
  }

  /** Where a vote leaves the event under its review mode. */
  static HumanEventEvent tally(HumanEvent e, Vote vote, Instant now) {
    var routing = e.settings().routing();
    var action = vote.action();
    var mode = routing.reviewMode();
    if (action == Action.HALT || mode.equals("SINGLE")) return settle(e, vote, action, action.name());
    var round = e.roundVotes();
    int count = round.size() + 1;
    int required = routing.required();
    boolean agrees = round.stream().allMatch(v -> v.action() == action);
    if (!agrees) {
      if (mode.equals("CONSENSUS")) {
        return new HumanEventEvent.Voted(vote, State.IN_REVIEW, true,
            action + " disagrees with the round; a new round starts");
      }
      if ("ESCALATE".equals(routing.splitVote()) && e.hasNextLevel()) {
        int next = e.level() + 1;
        return new HumanEventEvent.Escalated(SYSTEM, "split vote", next, now, dueAt(e, next, now), vote);
      }
      return settle(e, vote, Action.DENY, "Split vote; settled DENY");
    }
    if (!action.proceeds() && !mode.equals("CONSENSUS") && "DENY".equals(routing.splitVote())) {
      return settle(e, vote, action, action + ", " + count + " of " + required);
    }
    if (count >= required) return settle(e, vote, action, action + ", " + count + " of " + required);
    return new HumanEventEvent.Voted(vote, State.AWAITING_SECOND, false, action + ", " + count + " of " + required);
  }

  private static HumanEventEvent settle(HumanEvent e, Vote vote, Action action, String note) {
    boolean own = action == vote.action();
    var sticky = own ? vote.sticky() : null;
    String standingId = createsStanding(sticky, action) ? "sd-" + e.id() : null;
    Boolean agreed = e.aiRecommendation() != null && e.aiRecommendation().get("action") != null
        ? action.name().equals(String.valueOf(e.aiRecommendation().get("action"))) : null;
    var outcome = new Outcome(action, sticky, agreed, own ? vote.edits() : null, own ? vote.replacement() : null,
        vote.rationale(), standingId, false, HumanEvent.COMPLETED, null, null, null, null,
        own ? vote.conditions() : List.of());
    return new HumanEventEvent.Decided(vote, outcome, note);
  }

  private static boolean createsStanding(Sticky sticky, Action action) {
    if (sticky == null) return false;
    return switch (sticky) {
      case APPROVE_FOR_PATTERN, APPROVE_FOR_SESSION -> action.proceeds();
      case DENY_FOR_PATTERN -> action == Action.DENY;
      case APPROVE_ONCE -> false;
    };
  }

  /** What makes a decision request invalid: rationale, ticked checks, sticky scope, edits, replacement. */
  private static List<String> requestProblems(HumanEvent e, DecisionRequest r) {
    var problems = new ArrayList<String>();
    var s = e.settings();
    if (s.rationaleRequired() && blank(r.rationale())) problems.add("a rationale is required");
    if (r.rationale() != null && r.rationale().length() > MAX_TEXT) problems.add("the rationale is too long");
    var listed = e.ask().checks().stream().map(io.akka.human.api.Ask.Check::text).toList();
    for (var check : r.checks()) {
      if (!listed.contains(check)) problems.add("not a check of this event: " + check);
    }
    var sticky = r.sticky();
    if (sticky != null && sticky != Sticky.APPROVE_ONCE) {
      if (!s.sticky().contains(sticky)) problems.add("this control does not offer " + sticky);
      boolean denial = sticky == Sticky.DENY_FOR_PATTERN;
      if (denial ? r.action() != Action.DENY : !r.action().proceeds()) {
        problems.add(sticky + " goes with " + (denial ? "DENY" : "APPROVE or EDIT"));
      }
      if (sticky == Sticky.APPROVE_FOR_SESSION ? e.subject().sessionId() == null : e.matchKey() == null) {
        problems.add(sticky + " needs the event's " + (sticky == Sticky.APPROVE_FOR_SESSION ? "session" : "match key"));
      }
    }
    if (r.action() == Action.EDIT && (r.edits() == null || r.edits().isEmpty())) problems.add("an edit needs edits");
    if (r.action() == Action.OVERRIDE && blank(r.replacement())) problems.add("an override needs the replacement output");
    if (!r.conditions().isEmpty() && !r.action().proceeds()) problems.add("conditions go with APPROVE or EDIT");
    for (var condition : r.conditions()) {
      if (!(condition.get("text") instanceof String text) || text.isBlank()) problems.add("a condition needs its text");
      if (condition.get("dueAt") != null && !instant(condition.get("dueAt"))) {
        problems.add("a condition's dueAt must be an ISO 8601 instant");
      }
    }
    return problems;
  }

  private static String haltProblem(HumanEvent e, Action action) {
    if (!e.settings().haltRestart()) return null;
    if (!e.lift() && action != Action.HALT) return "a halt event takes HALT; a restart is raised as its own event";
    if (e.lift() && action == Action.HALT) return "a restart event takes APPROVE or DENY";
    return null;
  }

  // ---- moving the event without settling it

  /**
   * Moves the event to the next queue of its escalation chain. The chain decides whether there is one, so
   * the escalate route serves events whose decision panel does not offer ESCALATE as well.
   */
  public static Checked<List<HumanEventEvent>> escalate(HumanEvent e, Actor actor, String reason, Instant now) {
    var gate = movable(e, actor, Action.ESCALATE, now);
    if (gate != null) return Checked.refuse(gate);
    if (blank(reason)) return Checked.refuse(Refusal.invalid("a reason is required"));
    if (reason.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the reason is too long"));
    if (!e.hasNextLevel()) return Checked.refuse(Refusal.conflict("the event is already with the last queue", e.state().name()));
    int next = e.level() + 1;
    return Checked.events(new HumanEventEvent.Escalated(actor.reviewer().subject(), reason.trim(), next, now,
        dueAt(e, next, now), null));
  }

  public static Checked<List<HumanEventEvent>> sendBack(HumanEvent e, Actor actor, String requested, Instant now) {
    var gate = movable(e, actor, Action.SEND_BACK, now);
    if (gate != null) return Checked.refuse(gate);
    if (blank(requested)) return Checked.refuse(Refusal.invalid("say what changes are requested"));
    if (requested.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the request is too long"));
    if (e.proposedBy() == null) return Checked.refuse(Refusal.invalid("there is no proposer to send this back to"));
    boolean paused = ControlSettings.PAUSED.equals(e.settings().clockWhileSentBack());
    return Checked.events(new HumanEventEvent.SentBack(actor.reviewer(), requested.trim(), paused, now));
  }

  /** Only the proposer takes an event back from CHANGES_REQUESTED. */
  public static Checked<List<HumanEventEvent>> resubmit(HumanEvent e, Reviewer reviewer, String note, Instant now) {
    if (e.state() != State.CHANGES_REQUESTED) {
      return Checked.refuse(Refusal.conflict("the event is not waiting for changes", e.state().name()));
    }
    if (!reviewer.subject().equals(e.proposedBy())) return Checked.refuse(Refusal.forbidden("only the proposer can resubmit"));
    return Checked.events(new HumanEventEvent.Resubmitted(reviewer, trim(note), now));
  }

  public static Checked<List<HumanEventEvent>> requestMoreInfo(HumanEvent e, Actor actor, String question, Instant now) {
    var gate = movable(e, actor, Action.REQUEST_MORE_INFO, now);
    if (gate != null) return Checked.refuse(gate);
    if (blank(question)) return Checked.refuse(Refusal.invalid("say what information is needed"));
    if (question.length() > MAX_TEXT) return Checked.refuse(Refusal.invalid("the question is too long"));
    return Checked.events(new HumanEventEvent.InfoRequested(
        new HumanEvent.InfoRequest(actor.reviewer().subject(), now, question.trim())));
  }

  /** The reviewer steps aside: the event returns to the queue and they may not claim it again. */
  public static Checked<List<HumanEventEvent>> recuse(HumanEvent e, Reviewer reviewer, String reason, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    if (e.recused().contains(reviewer.subject())) {
      return Checked.refuse(Refusal.conflict("you have already recused", e.state().name()));
    }
    if (!reviewer.holdsAnyOf(e.currentRoles())) {
      return Checked.refuse(Refusal.forbidden(reviewer.subject() + " holds none of " + e.currentRoles()));
    }
    return Checked.events(new HumanEventEvent.Recused(reviewer, trim(reason), now));
  }

  public static Checked<List<HumanEventEvent>> extend(HumanEvent e, Actor actor, String extra, String reason, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    if (e.state() == State.DETECTED) return Checked.refuse(Refusal.conflict("the event was recorded, not routed", "DETECTED"));
    if (e.dueAt() == null) return Checked.refuse(Refusal.invalid("the event has no deadline to extend"));
    if (blank(reason)) return Checked.refuse(Refusal.invalid("a reason is required"));
    Duration by;
    try {
      by = Durations.parse(extra);
    } catch (IllegalArgumentException ex) {
      return Checked.refuse(Refusal.invalid("extra must be an ISO 8601 duration such as PT4H"));
    }
    if (by.isZero() || by.isNegative() || by.compareTo(MAX_EXTENSION) > 0) {
      return Checked.refuse(Refusal.invalid("an extension is more than nothing and at most P7D"));
    }
    if (!e.slaExtensions().isEmpty()) {
      return Checked.refuse(Refusal.conflict("the deadline has already been extended once", e.state().name()));
    }
    var owner = e.settings().accountableOwner();
    boolean isOwner = owner != null && actor.reviewer().roles().contains(owner);
    var who = refusedActor(e, actor, null, now);
    if (who != null && !isOwner) return Checked.refuse(who);
    return Checked.events(new HumanEventEvent.Extended(actor.reviewer(), extra.trim(), reason.trim(), e.dueAt().plus(by), now));
  }

  // ---- closing without a settling vote

  public static Checked<List<HumanEventEvent>> withdraw(HumanEvent e, Reviewer reviewer, String reason, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    if (!reviewer.subject().equals(e.proposedBy()) && !reviewer.holdsAnyOf(ADMIN)) {
      return Checked.refuse(Refusal.forbidden("only the proposer or an admin can withdraw"));
    }
    return Checked.events(new HumanEventEvent.Closed(State.WITHDRAWN, reviewer.subject(),
        "Withdrawn" + (blank(reason) ? "" : ": " + reason.trim()), Outcome.closedWithout(HumanEvent.ABANDONED), now));
  }

  /** The question no longer exists (the records it concerned changed); closes the event without a person. */
  public static Checked<List<HumanEventEvent>> supersede(HumanEvent e, String reason, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    return Checked.events(new HumanEventEvent.Closed(State.WITHDRAWN, "system:superseded",
        "Superseded" + (blank(reason) ? "" : ": " + reason.trim()), Outcome.closedWithout(HumanEvent.ABANDONED), now));
  }

  public static Checked<List<HumanEventEvent>> kill(HumanEvent e, String by, String reason, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    return Checked.events(new HumanEventEvent.Closed(State.KILLED, by, "Killed" + (blank(reason) ? "" : ": " + reason),
        Outcome.closedWithout(HumanEvent.KILLED), now));
  }

  /** The accountable owner reopens a closed event; {@code newEventId} takes the question up again. */
  public static Checked<List<HumanEventEvent>> reopen(HumanEvent e, Reviewer reviewer, String reason, String newEventId,
      Instant now) {
    if (e.pending()) return Checked.refuse(Refusal.conflict("the event is still pending", e.state().name()));
    if (e.state() == State.REOPENED) return Checked.refuse(Refusal.conflict("the event has already been reopened", "REOPENED"));
    var owner = e.settings().accountableOwner();
    if (owner == null || !reviewer.roles().contains(owner)) {
      return Checked.refuse(Refusal.forbidden("only the accountable owner (" + owner + ") can reopen"));
    }
    if (blank(reason)) return Checked.refuse(Refusal.invalid("a reason is required"));
    return Checked.events(new HumanEventEvent.Reopened(reviewer, reason.trim(), newEventId, now));
  }

  // ---- raising again, and standing decisions

  public static Checked<List<HumanEventEvent>> repeat(HumanEvent e, HumanEvent.Trigger trigger, Instant now) {
    if (!e.pending()) return Checked.refuse(notPending(e));
    return Checked.events(new HumanEventEvent.Repeated(trigger, now));
  }

  public static Checked<List<HumanEventEvent>> settleByStanding(HumanEvent e, String standingId, Action action, Instant now) {
    if (!e.pending() || !e.decisions().isEmpty()) return Checked.refuse(notPending(e));
    return Checked.events(new HumanEventEvent.StandingApplied(standingId, action, now));
  }

  // ---- the clock

  /**
   * What is due at {@code now}: a lapsed claim returns to the queue, the window turns at risk, and a
   * spent window applies the control's timeout behaviour. Nothing when nothing is due.
   */
  public static List<HumanEventEvent> tick(HumanEvent e, Instant now) {
    if (!e.pending() || e.state() == State.DETECTED) return List.of();
    var due = new ArrayList<HumanEventEvent>();
    var current = e;
    if (current.holder() != null && !now.isBefore(current.holder().expiresAt())) {
      var released = new HumanEventEvent.Released(SYSTEM, "The claim of " + current.holder().subject() + " lapsed", now);
      due.add(released);
      current = current.apply(released);
    }
    if (current.dueAt() != null && !current.paused()) {
      if (!current.atRiskNoted() && !now.isBefore(current.atRiskAt())) {
        var atRisk = new HumanEventEvent.AtRisk(now);
        due.add(atRisk);
        current = current.apply(atRisk);
      }
      if (!now.isBefore(current.dueAt())) due.add(timeout(current, now));
    }
    return due;
  }

  private static HumanEventEvent timeout(HumanEvent e, Instant now) {
    var behaviour = e.settings().timeoutBehaviour() == null ? "RAISE_TIMEOUT" : e.settings().timeoutBehaviour();
    return switch (behaviour) {
      case "ESCALATE" -> e.hasNextLevel()
          ? new HumanEventEvent.Escalated(SYSTEM, "the deadline passed", e.level() + 1, now, dueAt(e, e.level() + 1, now), null)
          : new HumanEventEvent.Closed(State.TIMED_OUT, SYSTEM, "The deadline passed at the last queue",
              Outcome.timedOut(null, behaviour), now);
      case "RESUME_WITH_DEFAULT" -> new HumanEventEvent.Closed(State.TIMED_OUT, SYSTEM,
          "Timed out; the default applies: " + e.settings().timeoutDefault(),
          Outcome.timedOut(e.settings().timeoutDefault(), behaviour), now);
      case "ABANDON" -> new HumanEventEvent.Closed(State.ABANDONED, SYSTEM, "Timed out; abandoned",
          new Outcome(null, null, null, null, null, "", null, false, HumanEvent.ABANDONED, behaviour, null, null, null,
              List.of()), now);
      default -> new HumanEventEvent.Closed(State.TIMED_OUT, SYSTEM, "Timed out; whoever waits is told",
          Outcome.timedOut(null, behaviour), now);
    };
  }

  // ---- after the outcome

  /** Recorded once; a repeated report changes nothing. */
  public static Checked<List<HumanEventEvent>> applied(HumanEvent e, String note, Instant now) {
    if (e.outcome() == null) return Checked.refuse(Refusal.conflict("the event has no outcome to apply", e.state().name()));
    if (e.outcome().delivered()) return Checked.ok(List.of());
    return Checked.events(new HumanEventEvent.Applied(note == null ? "applied" : note, now));
  }

  public static Checked<List<HumanEventEvent>> applyFailed(HumanEvent e, String error, Instant now) {
    if (e.outcome() == null) return Checked.refuse(Refusal.conflict("the event has no outcome to apply", e.state().name()));
    if (e.outcome().delivered()) return Checked.ok(List.of());
    return Checked.events(new HumanEventEvent.ApplyFailed(error == null ? "failed" : error, now));
  }

  // ---- who may act

  /**
   * Whether the actor is barred from the event, or may not act in its queue by the overseer registry.
   *
   * @param action the decision, or null for acts that are not one
   */
  public static Refusal refusedActor(HumanEvent e, Actor actor, Action action, Instant now) {
    var subject = actor.reviewer().subject();
    if (e.recused().contains(subject)) return Refusal.forbidden(subject + " has recused from this event");
    if (e.independentOf().contains(subject)) {
      return Refusal.forbidden("independence bars " + subject + " from deciding this event");
    }
    var roles = action == Action.HALT ? e.settings().exercisers() : e.currentRoles();
    var reason = Qualification.refusal(e.settings(), roles, actor, action, now);
    return reason == null ? null : Refusal.forbidden(reason);
  }

  /**
   * Whether a reviewer could act on the event now: the contract's "routable". The reviewer holds a
   * role the queue needs, is qualified, is not barred or recused, the event is not with its proposer,
   * not held by someone else, and the reviewer has not voted in this round.
   */
  public static boolean routable(HumanEvent e, Actor actor, Instant now) {
    if (!e.pending() || e.state() == State.DETECTED || e.state() == State.CHANGES_REQUESTED) return false;
    if (refusedActor(e, actor, null, now) != null) return false;
    var holder = e.holderAt(now);
    var subject = actor.reviewer().subject();
    if (holder != null && !holder.subject().equals(subject)) return false;
    return !e.votedInRound(subject);
  }

  // ---- internals

  private static Refusal movable(HumanEvent e, Actor actor, Action action, Instant now) {
    if (!e.pending()) return notPending(e);
    if (action != Action.ESCALATE && !e.settings().allowedActions().contains(action)) {
      return Refusal.invalid("this event does not allow " + action + "; it allows " + e.settings().allowedActions());
    }
    var gate = routedAndPending(e, now);
    if (gate != null) return gate;
    var who = refusedActor(e, actor, action, now);
    if (who != null) return who;
    var holder = e.holderAt(now);
    if (holder != null && !holder.subject().equals(actor.reviewer().subject())) {
      return Refusal.conflict("claimed by another reviewer", e.stateAt(now).name());
    }
    return null;
  }

  private static Refusal routedAndPending(HumanEvent e, Instant now) {
    if (!e.pending()) return notPending(e);
    if (e.state() == State.DETECTED) return Refusal.conflict("the event was recorded without routing (shadow)", "DETECTED");
    if (e.state() == State.CHANGES_REQUESTED) {
      return Refusal.conflict("the event is with its proposer for changes", State.CHANGES_REQUESTED.name());
    }
    return null;
  }

  private static Refusal refusedAsLate(HumanEvent e, Instant now) {
    if (pastDue(e, now) && ControlSettings.REFUSE.equals(e.settings().lateDecision())) {
      return Refusal.conflict("the event is past its deadline", e.stateAt(now).name());
    }
    return null;
  }

  private static boolean pastDue(HumanEvent e, Instant now) {
    return e.dueAt() != null && !e.paused() && !now.isBefore(e.dueAt());
  }

  private static Instant dueAt(HumanEvent e, int level, Instant now) {
    var window = e.settings().windowAt(level);
    return window == null ? null : now.plus(window);
  }

  private static Refusal notPending(HumanEvent e) {
    return Refusal.conflict("the event is " + e.state() + ", not pending", e.state().name());
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean instant(Object text) {
    try {
      Instant.parse(String.valueOf(text));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String trim(String s) {
    return s == null ? "" : s.trim();
  }
}
