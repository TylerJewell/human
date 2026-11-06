package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * The applicant support assistant's evaluation set: each flagged reply a triager accepted under
 * custom-4c293fe7fd2e, as a failing case. One instance, {@link #ID}.
 */
@Component(id = "eval-set")
public class EvalSetEntity extends KeyValueEntity<EvalSetEntity.EvalSet> {

  public static final String ID = "applicant-support";

  /** @param eventId the flag that became the case */
  public record Case(String eventId, String sessionId, String reply, String flaggedBy, String note, String acceptedBy) {}

  public record EvalSet(List<Case> cases) {}

  @Override
  public EvalSet emptyState() {
    return new EvalSet(List.of());
  }

  public Effect<EvalSet> add(Case c) {
    if (currentState().cases().stream().anyMatch(x -> x.eventId().equals(c.eventId()))) {
      return effects().reply(currentState());
    }
    var cases = new ArrayList<>(currentState().cases());
    cases.add(c);
    var next = new EvalSet(List.copyOf(cases));
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<EvalSet> get() {
    return effects().reply(currentState());
  }
}
