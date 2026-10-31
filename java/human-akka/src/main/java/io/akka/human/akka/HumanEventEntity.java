package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.engine.Checked;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.HumanEventEvent;
import io.akka.human.engine.Qualification.Actor;
import io.akka.human.engine.Refusal;
import io.akka.human.engine.Rules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One oversight event; the id is the event's. Every rule is in {@link Rules}: this entity persists
 * what the rules accept and answers what they refuse, so a refusal is a reply the caller can tell
 * apart, not an error. Each command carries the time it was made, so the same rules decide the same
 * way on replay and tests control the clock.
 */
@Component(id = "human-event")
public class HumanEventEntity extends EventSourcedEntity<HumanEvent, HumanEventEvent> {

  /** The event as it now stands (null when there is none), or why the command was not accepted. */
  public record Answer(HumanEvent event, Refusal refusal) {
    public boolean refused() {
      return refusal != null;
    }
  }

  /**
   * Raises the event. A standing decision that matches settles it at once.
   *
   * @param standingId the matching standing decision, or null
   */
  public record Raise(HumanEvent event, String standingId, Action standingAction) {}

  public record Act(Actor actor, Instant now) {}

  public record Release(Reviewer reviewer, Instant now) {}

  /** @param blockedBy the oversight-quality event that blocks the event's control, or null ({@code Blocks}) */
  public record Decide(Actor actor, Rules.DecisionRequest request, Map<String, Boolean> safeState, String blockedBy,
      Instant now) {

    public Decide(Actor actor, Rules.DecisionRequest request, Map<String, Boolean> safeState, Instant now) {
      this(actor, request, safeState, null, now);
    }
  }

  /** Escalate (with a reason) or send back (with what is requested). */
  public record Move(Actor actor, String text, Instant now) {}

  /** Resubmit (with a note) or withdraw (with a reason), by the proposer. */
  public record ByProposer(Reviewer reviewer, String text, Instant now) {}

  public record Extend(Actor actor, String extra, String reason, Instant now) {}

  public record Reopen(Reviewer reviewer, String reason, String newEventId, Instant now) {}

  /** Supersede or kill. */
  public record Close(String by, String reason, Instant now) {}

  /** What whoever waited reported: applied, or failed. */
  public record Report(String note, Instant now) {}

  public record Tick(Instant now) {}

  /**
   * @param changed whether anything was due
   * @param expired windows that ran out: escalations and timeouts
   * @param atRisk windows that turned at risk
   */
  public record Ticked(HumanEvent event, boolean changed, int expired, int atRisk) {}

  /**
   * Raising with the dedupe key of a pending event adds an occurrence to it. Raising an id that is
   * closed is refused, so the caller raises under the next id.
   */
  public Effect<Answer> raise(Raise command) {
    var current = currentState();
    var raised = command.event();
    if (current == null) {
      var events = new ArrayList<HumanEventEvent>();
      events.add(new HumanEventEvent.Raised(raised));
      if (command.standingId() != null) {
        events.add(new HumanEventEvent.StandingApplied(command.standingId(), command.standingAction(), raised.detectedAt()));
      }
      return effects().persistAll(events).thenReply(state -> new Answer(state, null));
    }
    if (current.pending() && raised.dedupeKey() != null && Objects.equals(current.dedupeKey(), raised.dedupeKey())) {
      return handle(Rules.repeat(current, raised.trigger(), raised.detectedAt()));
    }
    return effects().reply(new Answer(current,
        Refusal.conflict("event " + current.id() + " is " + current.state(), current.state().name())));
  }

  public Effect<Answer> claim(Act command) {
    return exists() ? handle(Rules.claim(currentState(), command.actor(), command.now())) : missing();
  }

  public Effect<Answer> release(Release command) {
    return exists() ? handle(Rules.release(currentState(), command.reviewer(), command.now())) : missing();
  }

  public Effect<Answer> decide(Decide command) {
    if (!exists()) return missing();
    var safeState = command.safeState() == null ? Map.<String, Boolean>of() : command.safeState();
    return handle(Rules.decide(currentState(), command.actor(), command.request(), safeState, command.blockedBy(),
        command.now()));
  }

  public Effect<Answer> escalate(Move command) {
    return exists() ? handle(Rules.escalate(currentState(), command.actor(), command.text(), command.now())) : missing();
  }

  public Effect<Answer> sendBack(Move command) {
    return exists() ? handle(Rules.sendBack(currentState(), command.actor(), command.text(), command.now())) : missing();
  }

  public Effect<Answer> resubmit(ByProposer command) {
    return exists() ? handle(Rules.resubmit(currentState(), command.reviewer(), command.text(), command.now())) : missing();
  }

  public Effect<Answer> withdraw(ByProposer command) {
    return exists() ? handle(Rules.withdraw(currentState(), command.reviewer(), command.text(), command.now())) : missing();
  }

  public Effect<Answer> extend(Extend command) {
    return exists()
        ? handle(Rules.extend(currentState(), command.actor(), command.extra(), command.reason(), command.now()))
        : missing();
  }

  public Effect<Answer> reopen(Reopen command) {
    return exists()
        ? handle(Rules.reopen(currentState(), command.reviewer(), command.reason(), command.newEventId(), command.now()))
        : missing();
  }

  public Effect<Answer> supersede(Close command) {
    return exists() ? handle(Rules.supersede(currentState(), command.reason(), command.now())) : missing();
  }

  public Effect<Answer> kill(Close command) {
    return exists() ? handle(Rules.kill(currentState(), command.by(), command.reason(), command.now())) : missing();
  }

  public Effect<Answer> markApplied(Report command) {
    return exists() ? handle(Rules.applied(currentState(), command.note(), command.now())) : missing();
  }

  public Effect<Answer> markApplyFailed(Report command) {
    return exists() ? handle(Rules.applyFailed(currentState(), command.note(), command.now())) : missing();
  }

  /** Does what the event's clock says is due at {@code now}; nothing when nothing is. */
  public Effect<Ticked> tick(Tick command) {
    if (!exists()) return effects().reply(new Ticked(null, false, 0, 0));
    var due = Rules.tick(currentState(), command.now());
    if (due.isEmpty()) return effects().reply(new Ticked(currentState(), false, 0, 0));
    int expired = (int) due.stream().filter(e -> e instanceof HumanEventEvent.Closed
        || e instanceof HumanEventEvent.Escalated).count();
    int atRisk = (int) due.stream().filter(e -> e instanceof HumanEventEvent.AtRisk).count();
    return effects().persistAll(due).thenReply(state -> new Ticked(state, true, expired, atRisk));
  }

  public ReadOnlyEffect<Answer> get() {
    if (!exists()) return effects().reply(new Answer(null, Refusal.notFound("event not found")));
    return effects().reply(new Answer(currentState(), null));
  }

  @Override
  public HumanEvent applyEvent(HumanEventEvent event) {
    return event instanceof HumanEventEvent.Raised raised ? raised.event() : currentState().apply(event);
  }

  private boolean exists() {
    return currentState() != null;
  }

  private Effect<Answer> missing() {
    return effects().reply(new Answer(null, Refusal.notFound("event not found")));
  }

  private Effect<Answer> handle(Checked<List<HumanEventEvent>> step) {
    if (step.refused()) return effects().reply(new Answer(currentState(), step.refusal()));
    if (step.value().isEmpty()) return effects().reply(new Answer(currentState(), null));
    return effects().persistAll(step.value()).thenReply(state -> new Answer(state, null));
  }
}
