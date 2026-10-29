package io.akka.human.conformance;

import java.util.List;
import java.util.Set;

/** Pattern ids and the decision vocabulary, as the contract's schema names them. */
final class Patterns {
  static final String ACTION_APPROVAL = "action-approval";
  static final String CONTEST_APPEAL = "contest-appeal";
  static final String OVERRIDE = "override";
  static final String FLAG_FEEDBACK = "flag-feedback";
  static final String HALT_RESTART = "halt-restart";

  /** Every pattern, in the order the suite runs them. */
  static final List<String> ALL = List.of(ACTION_APPROVAL, "decision-review", "change-authorization", "exception",
      "match-adjudication", "outcome-reconciliation", CONTEST_APPEAL, OVERRIDE, FLAG_FEEDBACK, "triage", "hand-off",
      HALT_RESTART, "attestation", "oversight-quality");

  static final List<String> ACTIONS = List.of("APPROVE", "DENY", "EDIT", "REDIRECT", "REPLAN", "ESCALATE", "HALT",
      "RECUSE", "REQUEST_MORE_INFO", "SEND_BACK", "DECLINE_SYSTEM", "OVERRIDE", "DISREGARD", "REVERSE");

  /** Actions that leave the event pending, or that only a halt request records. */
  static final Set<String> NOT_SETTLING = Set.of("ESCALATE", "SEND_BACK", "REQUEST_MORE_INFO", "RECUSE", "HALT");

  /** The decisions an override event records, in the order the suite prefers them. */
  static final List<String> AFTER_OUTPUT = List.of("OVERRIDE", "DISREGARD", "REVERSE");

  private Patterns() {}
}
