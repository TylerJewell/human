import { exception, matchAdjudication } from "./cases";
import { haltRestart } from "./halt";
import { attestation, oversightQuality, triage } from "./monitors";
import { contestAppeal, flagFeedback, override } from "./person";
import type { Scene } from "./types";
import { actionApproval, changeAuthorization, decisionReview, handOff, outcomeReconciliation } from "./workflows";

export const SCENES: Record<string, Scene> = {
  "action-approval": actionApproval,
  "decision-review": decisionReview,
  "change-authorization": changeAuthorization,
  exception,
  "match-adjudication": matchAdjudication,
  "outcome-reconciliation": outcomeReconciliation,
  "contest-appeal": contestAppeal,
  override,
  "flag-feedback": flagFeedback,
  triage,
  "hand-off": handOff,
  "halt-restart": haltRestart,
  attestation,
  "oversight-quality": oversightQuality
};
