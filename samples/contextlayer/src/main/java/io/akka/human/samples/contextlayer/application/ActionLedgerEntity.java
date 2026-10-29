package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * One action a playbook asked for, from request to outcome; the id is the action key. An outcome left
 * unknown is resolved by a person (custom-cl0044), who records whether it took effect; the action is
 * never retried.
 *
 * <p>{@code status}: REQUESTED, AWAITING_APPROVAL, APPROVED, REJECTED, DENIED, SUCCEEDED, FAILED or
 * OUTCOME_UNKNOWN; {@code resolution}, for an unknown outcome: TOOK_EFFECT or DID_NOT_TAKE_EFFECT.
 */
@Component(id = "context-action-ledger")
public class ActionLedgerEntity extends KeyValueEntity<ActionLedgerEntity.Action> {

  public record Action(
      String key,
      String assistantId,
      String actionType,
      String payload,
      String risk,
      String requestedBy,
      String status,
      String detail,
      String decidedBy,
      String approvalEventId,
      String unknownEventId,
      String resolution,
      String resolvedBy) {

    Action with(String nextStatus, String nextDetail) {
      return new Action(key, assistantId, actionType, payload, risk, requestedBy, nextStatus, nextDetail, decidedBy,
          approvalEventId, unknownEventId, resolution, resolvedBy);
    }
  }

  public record Update(String status, String detail, String decidedBy, String approvalEventId, String unknownEventId) {}

  public record Resolve(boolean tookEffect, String by) {}

  public Effect<Action> request(Action action) {
    if (currentState() != null) return effects().reply(currentState());
    return effects().updateState(action).thenReply(action);
  }

  /** Records a step of the run; a null field keeps what is there. */
  public Effect<Action> update(Update u) {
    if (currentState() == null) return effects().error("no such action");
    var a = currentState();
    var next = new Action(a.key(), a.assistantId(), a.actionType(), a.payload(), a.risk(), a.requestedBy(),
        u.status() == null ? a.status() : u.status(), u.detail() == null ? a.detail() : u.detail(),
        u.decidedBy() == null ? a.decidedBy() : u.decidedBy(),
        u.approvalEventId() == null ? a.approvalEventId() : u.approvalEventId(),
        u.unknownEventId() == null ? a.unknownEventId() : u.unknownEventId(), a.resolution(), a.resolvedBy());
    return effects().updateState(next).thenReply(next);
  }

  /** What a person found: whether the action whose outcome was unknown took effect. */
  public Effect<Action> resolveUnknown(Resolve resolve) {
    var a = currentState();
    if (a == null || !a.status().equals("OUTCOME_UNKNOWN")) return effects().error("the action's outcome is not unknown");
    var next = new Action(a.key(), a.assistantId(), a.actionType(), a.payload(), a.risk(), a.requestedBy(), a.status(),
        a.detail(), a.decidedBy(), a.approvalEventId(), a.unknownEventId(),
        resolve.tookEffect() ? "TOOK_EFFECT" : "DID_NOT_TAKE_EFFECT", resolve.by());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Action> get() {
    if (currentState() == null) return effects().error("no such action");
    return effects().reply(currentState());
  }
}
