package io.akka.human.api;

/** One entry point per pattern, so a call site reads as the situation it raises. */
public final class Patterns {

  private Patterns() {}

  public static RaiseRequest.Builder actionApproval(String question) {
    return RaiseRequest.of(Pattern.ACTION_APPROVAL, question);
  }

  public static RaiseRequest.Builder decisionReview(String question) {
    return RaiseRequest.of(Pattern.DECISION_REVIEW, question);
  }

  public static RaiseRequest.Builder changeAuthorization(String question) {
    return RaiseRequest.of(Pattern.CHANGE_AUTHORIZATION, question);
  }

  public static RaiseRequest.Builder exception(String question) {
    return RaiseRequest.of(Pattern.EXCEPTION, question);
  }

  public static RaiseRequest.Builder matchAdjudication(String question) {
    return RaiseRequest.of(Pattern.MATCH_ADJUDICATION, question);
  }

  public static RaiseRequest.Builder outcomeReconciliation(String question) {
    return RaiseRequest.of(Pattern.OUTCOME_RECONCILIATION, question);
  }

  public static RaiseRequest.Builder contestAppeal(String question) {
    return RaiseRequest.of(Pattern.CONTEST_APPEAL, question);
  }

  public static RaiseRequest.Builder override(String question) {
    return RaiseRequest.of(Pattern.OVERRIDE, question);
  }

  public static RaiseRequest.Builder flagFeedback(String question) {
    return RaiseRequest.of(Pattern.FLAG_FEEDBACK, question);
  }

  public static RaiseRequest.Builder triage(String question) {
    return RaiseRequest.of(Pattern.TRIAGE, question);
  }

  public static RaiseRequest.Builder handOff(String question) {
    return RaiseRequest.of(Pattern.HAND_OFF, question);
  }

  public static RaiseRequest.Builder haltRestart(String question) {
    return RaiseRequest.of(Pattern.HALT_RESTART, question);
  }

  public static RaiseRequest.Builder attestation(String question) {
    return RaiseRequest.of(Pattern.ATTESTATION, question);
  }

  public static RaiseRequest.Builder oversightQuality(String question) {
    return RaiseRequest.of(Pattern.OVERSIGHT_QUALITY, question);
  }
}
