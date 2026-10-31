package io.akka.human.engine;

import akka.javasdk.annotations.Migration;
import akka.javasdk.annotations.TypeName;
import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import java.time.Instant;
import java.util.Map;

/** What happens to an event. Each carries what it needs, so replaying the journal rebuilds the event. */
public sealed interface HumanEventEvent {

  /** The event as raised, with the settings in force then; {@link Journal} reads older documents. */
  @TypeName("human-event-raised")
  @Migration(Journal.RaisedFormat.class)
  record Raised(HumanEvent event) implements HumanEventEvent {}

  /** The same situation raised again with the event's dedupe key. */
  @TypeName("human-event-repeated")
  @Migration(Journal.Repeated.class)
  record Repeated(HumanEvent.Trigger trigger, Instant at) implements HumanEventEvent {}

  /** An active standing decision on the event's control and match key settled it as it was raised. */
  @TypeName("human-event-standing-applied")
  @Migration(Journal.StandingApplied.class)
  record StandingApplied(String standingId, Action action, Instant at) implements HumanEventEvent {}

  @TypeName("human-event-claimed")
  @Migration(Journal.Claimed.class)
  record Claimed(Reviewer reviewer, Instant at, Instant expiresAt) implements HumanEventEvent {}

  /** The holder released the event, or the claim lapsed ({@code by} is {@code system}). */
  @TypeName("human-event-released")
  @Migration(Journal.Released.class)
  record Released(String by, String note, Instant at) implements HumanEventEvent {}

  /**
   * A vote that does not settle the event: it waits for another ({@code AWAITING_SECOND}), or it
   * disagreed under CONSENSUS and a new round starts ({@code IN_REVIEW}).
   */
  @TypeName("human-event-voted")
  @Migration(Journal.Voted.class)
  record Voted(HumanEvent.Vote vote, State state, boolean newRound, String note) implements HumanEventEvent {}

  /** The vote that settles the event. */
  @TypeName("human-event-decided")
  @Migration(Journal.Decided.class)
  record Decided(HumanEvent.Vote vote, HumanEvent.Outcome outcome, String note) implements HumanEventEvent {}

  @TypeName("human-event-info-requested")
  @Migration(Journal.InfoRequested.class)
  record InfoRequested(HumanEvent.InfoRequest request) implements HumanEventEvent {}

  @TypeName("human-event-recused")
  @Migration(Journal.Recused.class)
  record Recused(Reviewer reviewer, String reason, Instant at) implements HumanEventEvent {}

  /**
   * The event moves to the next queue of the chain, with a new window there.
   *
   * @param vote the vote that split the event, when a split vote escalates it
   */
  @TypeName("human-event-escalated")
  @Migration(Journal.Escalated.class)
  record Escalated(String by, String reason, int level, Instant at, Instant dueAt, HumanEvent.Vote vote)
      implements HumanEventEvent {}

  /** Back to the proposer; {@code paused} when the control stops the clock while sent back. */
  @TypeName("human-event-sent-back")
  @Migration(Journal.SentBack.class)
  record SentBack(Reviewer reviewer, String requested, boolean paused, Instant at) implements HumanEventEvent {}

  @TypeName("human-event-resubmitted")
  @Migration(Journal.Resubmitted.class)
  record Resubmitted(Reviewer reviewer, String note, Instant at) implements HumanEventEvent {}

  @TypeName("human-event-extended")
  @Migration(Journal.Extended.class)
  record Extended(Reviewer reviewer, String extra, String reason, Instant dueAt, Instant at) implements HumanEventEvent {}

  @TypeName("human-event-at-risk")
  @Migration(Journal.AtRisk.class)
  record AtRisk(Instant at) implements HumanEventEvent {}

  /** The safe-state checks of a halt's target as they stood when a person decided. */
  @TypeName("human-event-safe-state-checked")
  @Migration(Journal.SafeStateChecked.class)
  record SafeStateChecked(Map<String, Boolean> checks, Instant at) implements HumanEventEvent {}

  /** The event closed without a settling vote: timed out, abandoned, withdrawn, superseded or killed. */
  @TypeName("human-event-closed")
  @Migration(Journal.Closed.class)
  record Closed(State state, String by, String note, HumanEvent.Outcome outcome, Instant at) implements HumanEventEvent {}

  /** The accountable owner reopened a closed event; {@code newEventId} is the event that takes it up. */
  @TypeName("human-event-reopened")
  @Migration(Journal.Reopened.class)
  record Reopened(Reviewer reviewer, String reason, String newEventId, Instant at) implements HumanEventEvent {}

  /** What resumed or carried out the outcome reported back. */
  @TypeName("human-event-applied")
  @Migration(Journal.Applied.class)
  record Applied(String note, Instant at) implements HumanEventEvent {}

  @TypeName("human-event-apply-failed")
  @Migration(Journal.ApplyFailed.class)
  record ApplyFailed(String error, Instant at) implements HumanEventEvent {}
}
