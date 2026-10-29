package io.akka.human.samples.patterns.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * The safe-state check of the eligibility-assessment capability, which a restart needs to pass: whether
 * the backlog of assessments made before the halt has been drained. Set by hand in the sample.
 */
@Component(id = "sample-safe-state")
public class SafeStateEntity extends KeyValueEntity<SafeStateEntity.SafeState> {

  public record SafeState(boolean backlogDrained) {}

  @Override
  public SafeState emptyState() {
    return new SafeState(false);
  }

  public Effect<SafeState> set(SafeState state) {
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<SafeState> get() {
    return effects().reply(currentState());
  }
}
