package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Ask;
import io.akka.human.api.Relations;
import io.akka.human.api.Resume;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Sticky;
import io.akka.human.api.Subject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One situation a human control says a person must judge, from being raised until it closes.
 *
 * <p>The rules that change it are in {@link Rules}; this record holds the event, answers questions
 * about it at a given time, and applies what the rules accepted. Nothing here reads a clock.
 *
 * @param settings the control's settings when the event was raised
 * @param strength prevent, warn or record, for the environment the event was raised in
 * @param independentOf the people independence bars from deciding
 * @param level the queue of the escalation chain the event is at; 0 is the first
 * @param startedAt when the current window began: the raise, or the latest escalation
 * @param pausedAt set while the event is with its proposer and the clock is paused
 * @param atRiskNoted the event has been marked at risk in its current window
 * @param roundStart the first decision of the current round of votes
 * @param requested what the reviewer asked the proposer to change, while sent back
 */
public record HumanEvent(
    String id,
    String service,
    String environment,
    String catalogDigest,
    ControlSettings settings,
    State state,
    String strength,
    boolean blocking,
    Subject subject,
    Trigger trigger,
    Ask ask,
    String proposedBy,
    String builtBy,
    String firstReviewer,
    List<String> independentOf,
    String dedupeKey,
    String matchKey,
    Map<String, Object> aiRecommendation,
    Map<Action, String> effects,
    List<Map<String, Object>> evidence,
    List<Related> related,
    Resume resumes,
    boolean simulated,
    int level,
    Holder holder,
    Instant presentedAt,
    Instant claimedAt,
    Instant detectedAt,
    Instant startedAt,
    Instant dueAt,
    Instant pausedAt,
    boolean atRiskNoted,
    List<Extension> slaExtensions,
    List<Vote> decisions,
    int roundStart,
    List<InfoRequest> infoRequests,
    List<String> recused,
    String requested,
    Outcome outcome,
    Instant closedAt,
    List<Step> history) {

  public static final String SYSTEM = "system";
  public static final String COMPLETED = "COMPLETED";
  public static final String TIMED_OUT = "TIMED_OUT";
  public static final String ABANDONED = "ABANDONED";
  public static final String KILLED = "KILLED";

  /** Why the event was raised. {@code values} and {@code conditions} are the contract's shapes. */
  public record Trigger(
      String detector,
      String rule,
      String reason,
      List<Map<String, Object>> values,
      List<Map<String, Object>> conditions,
      Instant detectedAt,
      int occurrences) {

    public Trigger {
      values = values == null ? List.of() : List.copyOf(values);
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    Trigger repeated(Trigger latest) {
      var source = latest == null ? this : latest;
      return new Trigger(source.detector, source.rule, source.reason, source.values, source.conditions, detectedAt,
          occurrences + 1);
    }
  }

  /** A claim: who holds the event and until when. */
  public record Holder(String subject, List<String> roles, Instant since, Instant expiresAt) {}

  /**
   * A person's decision on the event: a vote under the review mode, or the one decision that settles a
   * SINGLE event. Every recorded vote passed the qualification and independence checks.
   *
   * @param presentedAt when the reviewer was shown the event: the claim, or what a later voter sent
   * @param reviewSeconds from {@code presentedAt} to the decision
   */
  public record Vote(
      Reviewer reviewer,
      Action action,
      String rationale,
      Map<String, Object> edits,
      Sticky sticky,
      String replacement,
      Instant presentedAt,
      Instant submittedAt,
      long reviewSeconds,
      boolean belowMinimum,
      boolean late,
      List<String> checks,
      List<Map<String, Object>> conditions) {

    public Vote {
      rationale = rationale == null ? "" : rationale;
      checks = checks == null ? List.of() : List.copyOf(checks);
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
  }

  /**
   * How the event ended and what was done with it.
   *
   * @param action the settling decision, the control's default on a timeout, or null when nothing was decided
   * @param byStanding a standing decision settled the event; {@code standingDecisionId} names it
   * @param standingDecisionId the standing decision that settled the event, or the one this decision creates
   * @param timeoutBehaviour the behaviour applied when the event timed out
   * @param applied what resumed or carried out the outcome said it did
   */
  public record Outcome(
      Action action,
      Sticky sticky,
      Boolean agreedWithAi,
      Map<String, Object> edits,
      String replacement,
      String rationale,
      String standingDecisionId,
      boolean byStanding,
      String slaOutcome,
      String timeoutBehaviour,
      Instant appliedAt,
      String applied,
      String applyError,
      List<Map<String, Object>> conditions) {

    public Outcome {
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static Outcome closedWithout(String slaOutcome) {
      return new Outcome(null, null, null, null, null, "", null, false, slaOutcome, null, null, null, null, List.of());
    }

    public static Outcome timedOut(Action action, String timeoutBehaviour) {
      return new Outcome(action, null, null, null, null, "", null, false, TIMED_OUT, timeoutBehaviour, null, null,
          null, List.of());
    }

    Outcome withApplied(String note, Instant at) {
      return new Outcome(action, sticky, agreedWithAi, edits, replacement, rationale, standingDecisionId, byStanding,
          slaOutcome, timeoutBehaviour, at, note, applyError, conditions);
    }

    Outcome withApplyError(String error) {
      return new Outcome(action, sticky, agreedWithAi, edits, replacement, rationale, standingDecisionId, byStanding,
          slaOutcome, timeoutBehaviour, appliedAt, applied, error, conditions);
    }

    /** Whether whoever waits has been told, successfully or not. */
    public boolean delivered() {
      return appliedAt != null || applyError != null;
    }
  }

  public record InfoRequest(String by, Instant at, String question) {}

  public record Related(String eventId, String relation) {}

  public record Extension(String by, Instant at, String extra, String reason) {}

  public record Step(Instant at, State state, String actor, String note) {}

  // ---- questions about the event

  public boolean pending() {
    return state.pending();
  }

  /** Recorded without being routed: the control is in shadow mode, or of strength {@code record} here. */
  public boolean shadow() {
    return settings.shadow() || "record".equals(strength);
  }

  public String queue() {
    return settings.queueAt(level);
  }

  /** The roles that decide at the current queue. */
  public List<String> currentRoles() {
    return settings.rolesAt(level);
  }

  /** The votes of the current round. */
  public List<Vote> roundVotes() {
    return decisions.subList(Math.min(roundStart, decisions.size()), decisions.size());
  }

  public boolean votedInRound(String subject) {
    return roundVotes().stream().anyMatch(v -> v.reviewer().subject().equals(subject));
  }

  /** The state with nobody holding the event: waiting for another vote, escalated, or open. */
  public State openState() {
    if (!roundVotes().isEmpty()) return State.AWAITING_SECOND;
    return level > 0 ? State.ESCALATED : State.OPEN;
  }

  /** The state at {@code now}: a lapsed claim leaves the event as if released. */
  public State stateAt(Instant now) {
    if (state == State.CLAIMED && holder != null && !now.isBefore(holder.expiresAt())) return openState();
    return state;
  }

  /** Who holds the event at {@code now}, or null. */
  public Holder holderAt(Instant now) {
    return holder != null && now.isBefore(holder.expiresAt()) ? holder : null;
  }

  public boolean lift() {
    return Boolean.TRUE.equals(subject.detail().get("lift"));
  }

  /** The service or capability a halt-restart event stops or restarts: the subject's, else the control's. */
  public String haltTarget() {
    return subject.detail().get("target") instanceof String target && !target.isBlank() ? target : settings.haltTarget();
  }

  public boolean paused() {
    return pausedAt != null;
  }

  /** When the event shows as at risk in its current window. */
  public Instant atRiskAt() {
    if (dueAt == null) return null;
    long window = Duration.between(startedAt, dueAt).toMillis();
    return startedAt.plusMillis((long) (window * settings.atRiskAt()));
  }

  public boolean overdue(Instant now) {
    return pending() && dueAt != null && !paused() && !now.isBefore(dueAt);
  }

  /** running, at-risk, overdue, paused, met, missed or none. */
  public String slaState(Instant now) {
    if (!pending()) {
      if (outcome == null || dueAt == null) return "none";
      return switch (outcome.slaOutcome()) {
        case COMPLETED -> settledLate() ? "missed" : "met";
        case TIMED_OUT -> "missed";
        default -> "none";
      };
    }
    if (dueAt == null || state == State.DETECTED) return "none";
    if (paused()) return "paused";
    if (!now.isBefore(dueAt)) return "overdue";
    return now.isBefore(atRiskAt()) ? "running" : "at-risk";
  }

  /** When the event's clock next needs looking at: a claim lapsing, the window turning at risk, or ending. */
  public Instant nextCheckAt() {
    if (!pending() || state == State.DETECTED) return null;
    var holderCheck = holder == null ? null : holder.expiresAt();
    Instant slaCheck = null;
    if (dueAt != null && !paused()) slaCheck = atRiskNoted ? dueAt : atRiskAt();
    return Stream.of(holderCheck, slaCheck).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
  }

  public boolean hasNextLevel() {
    return level + 1 < settings.levels();
  }

  /** The vote that settled the event, if a person settled it. */
  public Vote settlingVote() {
    if (outcome == null || outcome.byStanding() || !COMPLETED.equals(outcome.slaOutcome()) || decisions.isEmpty()) {
      return null;
    }
    return decisions.getLast();
  }

  /** Who decided: the votes of the settling round, or the standing decision that settled the event. */
  public List<String> deciders() {
    if (outcome != null && outcome.byStanding()) return List.of("standing:" + outcome.standingDecisionId());
    if (settlingVote() == null) return List.of();
    return roundVotes().stream().map(v -> v.reviewer().subject()).toList();
  }

  private boolean settledLate() {
    return roundVotes().stream().anyMatch(Vote::late);
  }

  /** The record the subject is about, for proof records: the subject's ref, a halt's target, or the event. */
  public String subjectRef() {
    if (subject.ref() != null) return subject.ref();
    var detail = subject.detail();
    for (var field : List.of("target", "ref")) {
      if (detail.get(field) instanceof String s && !s.isBlank()) return s;
    }
    return id;
  }

  // ---- applying what the rules accepted

  public HumanEvent apply(HumanEventEvent event) {
    var d = new Draft(this);
    switch (event) {
      case HumanEventEvent.Raised e -> {
        return e.event();
      }
      case HumanEventEvent.Repeated e -> {
        d.trigger = trigger.repeated(e.trigger());
        d.step(e.at(), state, SYSTEM, "Raised again (" + d.trigger.occurrences() + " occurrences)");
      }
      case HumanEventEvent.StandingApplied e -> {
        d.state = State.DECIDED;
        d.outcome = new Outcome(e.action(), null, null, null, null, "", e.standingId(), true, COMPLETED, null, null,
            null, null, List.of());
        d.closedAt = e.at();
        d.step(e.at(), State.DECIDED, "standing:" + e.standingId(),
            "Settled by standing decision " + e.standingId() + ": " + e.action());
      }
      case HumanEventEvent.Claimed e -> {
        d.state = State.CLAIMED;
        d.holder = new Holder(e.reviewer().subject(), e.reviewer().roles(), e.at(), e.expiresAt());
        d.claimedAt = e.at();
        if (presentedAt == null) d.presentedAt = e.at();
        d.step(e.at(), State.CLAIMED, e.reviewer().subject(), "Claimed until " + e.expiresAt());
      }
      case HumanEventEvent.Released e -> {
        d.holder = null;
        d.state = openState();
        d.step(e.at(), d.state, e.by(), e.note());
      }
      case HumanEventEvent.Voted e -> {
        d.decisions.add(e.vote());
        d.holder = null;
        if (e.newRound()) d.roundStart = d.decisions.size();
        d.state = e.state();
        d.step(e.vote().submittedAt(), e.state(), e.vote().reviewer().subject(), e.note());
      }
      case HumanEventEvent.Decided e -> {
        d.decisions.add(e.vote());
        d.holder = null;
        d.state = State.DECIDED;
        d.outcome = e.outcome();
        d.closedAt = e.vote().submittedAt();
        d.step(e.vote().submittedAt(), State.DECIDED, e.vote().reviewer().subject(), e.note());
      }
      case HumanEventEvent.InfoRequested e -> {
        d.infoRequests.add(e.request());
        d.step(e.request().at(), state, e.request().by(), "Asked for more: " + e.request().question());
      }
      case HumanEventEvent.Recused e -> {
        d.recused.add(e.reviewer().subject());
        if (holder != null && holder.subject().equals(e.reviewer().subject())) {
          d.holder = null;
          d.state = openState();
        }
        d.step(e.at(), d.state, e.reviewer().subject(), "Recused" + (blank(e.reason()) ? "" : ": " + e.reason()));
      }
      case HumanEventEvent.Escalated e -> {
        if (e.vote() != null) d.decisions.add(e.vote());
        d.level = e.level();
        d.state = State.ESCALATED;
        d.holder = null;
        d.roundStart = d.decisions.size();
        d.startedAt = e.at();
        d.dueAt = e.dueAt();
        d.atRiskNoted = false;
        d.step(e.at(), State.ESCALATED, e.by(), "Escalated to " + settings.queueAt(e.level()) + ": " + e.reason());
      }
      case HumanEventEvent.SentBack e -> {
        d.state = State.CHANGES_REQUESTED;
        d.holder = null;
        d.requested = e.requested();
        d.pausedAt = e.paused() ? e.at() : null;
        d.step(e.at(), State.CHANGES_REQUESTED, e.reviewer().subject(), "Sent back: " + e.requested());
      }
      case HumanEventEvent.Resubmitted e -> {
        d.roundStart = d.decisions.size();
        d.state = level > 0 ? State.ESCALATED : State.OPEN;
        if (pausedAt != null && dueAt != null) d.dueAt = dueAt.plus(Duration.between(pausedAt, e.at()));
        d.pausedAt = null;
        d.requested = null;
        d.step(e.at(), d.state, e.reviewer().subject(), "Resubmitted" + (blank(e.note()) ? "" : ": " + e.note()));
      }
      case HumanEventEvent.Extended e -> {
        d.dueAt = e.dueAt();
        d.slaExtensions.add(new Extension(e.reviewer().subject(), e.at(), e.extra(), e.reason()));
        d.step(e.at(), state, e.reviewer().subject(), "Deadline extended by " + e.extra() + ": " + e.reason());
      }
      case HumanEventEvent.AtRisk e -> {
        d.atRiskNoted = true;
        d.step(e.at(), state, SYSTEM, "At risk: due " + dueAt);
      }
      case HumanEventEvent.SafeStateChecked e -> {
        var checks = e.checks().entrySet().stream()
            .map(c -> (Object) Subject.fields("name", c.getKey(), "passed", c.getValue())).toList();
        d.subject = subject.with("safeStateChecks", checks);
      }
      case HumanEventEvent.Closed e -> {
        d.state = e.state();
        d.holder = null;
        d.outcome = e.outcome();
        d.closedAt = e.at();
        d.step(e.at(), e.state(), e.by(), e.note());
      }
      case HumanEventEvent.Reopened e -> {
        d.state = State.REOPENED;
        d.related.add(new Related(e.newEventId(), Relations.REOPENED_AS));
        d.step(e.at(), State.REOPENED, e.reviewer().subject(), "Reopened as " + e.newEventId() + ": " + e.reason());
      }
      case HumanEventEvent.Applied e -> {
        d.outcome = outcome.withApplied(e.note(), e.at());
        if (state == State.DECIDED) d.state = State.APPLIED;
        d.step(e.at(), d.state, SYSTEM, e.note());
      }
      case HumanEventEvent.ApplyFailed e -> {
        d.outcome = outcome.withApplyError(e.error());
        d.step(e.at(), state, SYSTEM, "Not applied: " + e.error());
      }
    }
    return d.build();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** A mutable copy, so each event changes only what it names. */
  static final class Draft {
    private final HumanEvent from;
    State state;
    Subject subject;
    Trigger trigger;
    List<Related> related;
    int level;
    Holder holder;
    Instant presentedAt;
    Instant claimedAt;
    Instant startedAt;
    Instant dueAt;
    Instant pausedAt;
    boolean atRiskNoted;
    List<Extension> slaExtensions;
    List<Vote> decisions;
    int roundStart;
    List<InfoRequest> infoRequests;
    List<String> recused;
    String requested;
    Outcome outcome;
    Instant closedAt;
    List<Step> history;

    Draft(HumanEvent e) {
      from = e;
      state = e.state;
      subject = e.subject;
      trigger = e.trigger;
      related = new ArrayList<>(e.related);
      level = e.level;
      holder = e.holder;
      presentedAt = e.presentedAt;
      claimedAt = e.claimedAt;
      startedAt = e.startedAt;
      dueAt = e.dueAt;
      pausedAt = e.pausedAt;
      atRiskNoted = e.atRiskNoted;
      slaExtensions = new ArrayList<>(e.slaExtensions);
      decisions = new ArrayList<>(e.decisions);
      roundStart = e.roundStart;
      infoRequests = new ArrayList<>(e.infoRequests);
      recused = new ArrayList<>(e.recused);
      requested = e.requested;
      outcome = e.outcome;
      closedAt = e.closedAt;
      history = new ArrayList<>(e.history);
    }

    void step(Instant at, State in, String actor, String note) {
      history.add(new Step(at, in, actor, note));
    }

    HumanEvent build() {
      var e = from;
      return new HumanEvent(e.id, e.service, e.environment, e.catalogDigest, e.settings, state, e.strength, e.blocking,
          subject, trigger, e.ask, e.proposedBy, e.builtBy, e.firstReviewer, e.independentOf, e.dedupeKey, e.matchKey,
          e.aiRecommendation, e.effects, e.evidence, List.copyOf(related), e.resumes, e.simulated, level, holder,
          presentedAt, claimedAt, e.detectedAt, startedAt, dueAt, pausedAt, atRiskNoted, List.copyOf(slaExtensions),
          List.copyOf(decisions), roundStart, List.copyOf(infoRequests), List.copyOf(recused), requested, outcome,
          closedAt, List.copyOf(history));
    }
  }
}
