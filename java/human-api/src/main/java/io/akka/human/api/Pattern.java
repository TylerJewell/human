package io.akka.human.api;

/** The fourteen oversight patterns of Verify's pattern registry (human-patterns.yaml). */
public enum Pattern {
  ACTION_APPROVAL("action-approval"),
  DECISION_REVIEW("decision-review"),
  CHANGE_AUTHORIZATION("change-authorization"),
  EXCEPTION("exception"),
  MATCH_ADJUDICATION("match-adjudication"),
  OUTCOME_RECONCILIATION("outcome-reconciliation"),
  CONTEST_APPEAL("contest-appeal"),
  OVERRIDE("override"),
  FLAG_FEEDBACK("flag-feedback"),
  TRIAGE("triage"),
  HAND_OFF("hand-off"),
  HALT_RESTART("halt-restart"),
  ATTESTATION("attestation"),
  OVERSIGHT_QUALITY("oversight-quality");

  private final String id;

  Pattern(String id) {
    this.id = id;
  }

  /** The registry id, as the contract and Verify write it. */
  public String id() {
    return id;
  }

  public static Pattern of(String id) {
    for (var pattern : values()) {
      if (pattern.id.equals(id)) return pattern;
    }
    throw new IllegalArgumentException("not an oversight pattern: " + id);
  }
}
