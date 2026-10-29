package io.akka.human.engine;

import java.time.Instant;

/**
 * Whether a halt stops its target. Set when a person exercises the halt; cleared when a restart is
 * approved.
 *
 * @param eventId the halt-restart event that exercised the halt
 * @param liftEventId the pending restart event, if one is raised
 * @param haltRecordId the HaltExercised proof record, which HaltLifted refers to
 */
public record HaltState(
    String scope,
    String target,
    ControlSettings.ControlRef control,
    String affectedCalls,
    boolean halted,
    Instant since,
    String by,
    String reason,
    String eventId,
    String liftEventId,
    String haltRecordId) {

  public static HaltState notHalted(ControlSettings settings) {
    return notHalted(settings, settings.haltTarget());
  }

  /** A target the control halts that the call names, rather than its halt_config. */
  public static HaltState notHalted(ControlSettings settings, String target) {
    return new HaltState(settings.haltScope(), target, settings.control(), settings.haltAffectedCalls(), false, null, null,
        null, null, null, null);
  }

  /** The same halt, listed under another control that halts or restarts the target. */
  public HaltState listedUnder(ControlSettings.ControlRef other) {
    return new HaltState(scope, target, other, affectedCalls, halted, since, by, reason, eventId, liftEventId, haltRecordId);
  }

  public HaltState exercised(String byWhom, String why, String event, String recordId, Instant at) {
    return new HaltState(scope, target, control, affectedCalls, true, at, byWhom, why, event, null, recordId);
  }

  public HaltState liftRaised(String event) {
    return new HaltState(scope, target, control, affectedCalls, halted, since, by, reason, eventId, event, haltRecordId);
  }

  public HaltState lifted() {
    return new HaltState(scope, target, control, affectedCalls, false, null, null, null, null, null, null);
  }
}
