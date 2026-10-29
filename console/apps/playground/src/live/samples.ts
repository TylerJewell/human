import type { ControlSettings, RaiseRequest, Subject } from "@akka-human/kit";
import { exampleSubject } from "../examples";

// What the raise panel asks a service to raise for each of its controls: a
// question and checks that fit the pattern, and the spec example's subject for
// the control's subject kind, so every subject view and decision panel can be
// tried against a running service.

interface Sample {
  question: string;
  checks?: { text: string; section?: "trigger" | "subject" | "evidence" | "blast" | "judge" }[];
  summary: string;
  trigger: { detector: string; reason: string; rule?: string };
  /** Changes to the example subject's detail. */
  detail?: Record<string, unknown>;
  extra?: Partial<RaiseRequest>;
}

const SAMPLES: Record<string, Sample> = {
  "action-approval": {
    question: "Should the agent pay supplier S-77 4,200 EUR?",
    checks: [{ text: "The invoice matches the purchase order.", section: "subject" }],
    summary: "Pay S-77 4,200 EUR",
    trigger: { detector: "tool-policy", rule: "pay.amount > 1000", reason: "The agent asked to pay 4,200 EUR." },
    extra: { proposedBy: "agent:payments", matchKey: "pay:S-77" }
  },
  "decision-review": {
    question: "Should the adverse notice to applicant A-77 be sent?",
    checks: [{ text: "The notice gives the reason in plain words.", section: "subject" }],
    summary: "Decline notice for A-77",
    trigger: { detector: "workflow", reason: "An adverse notice is held for review before it is sent." }
  },
  "change-authorization": {
    question: "Should model m-5 roll out to 10% of notice-writing traffic?",
    checks: [
      { text: "The offline evaluation shows no regression that matters.", section: "subject" },
      { text: "The rollback is ready.", section: "subject" }
    ],
    summary: "Roll out m-5 to 10% of notice writing",
    trigger: { detector: "change-request", reason: "A model rollout was proposed." },
    extra: { proposedBy: "a.devos@example.com", builtBy: "trainer" }
  },
  exception: {
    question: "Should rule R-12 be waived for the billing security fix until tomorrow?",
    summary: "Waive R-12 for billing",
    trigger: { detector: "person", reason: "r.chen@example.com asked for a waiver." },
    detail: { from: new Date().toISOString(), until: new Date(Date.now() + 86_400_000).toISOString() },
    extra: { proposedBy: "r.chen@example.com" }
  },
  "match-adjudication": {
    question: "Are CRM customer c-1 and claims customer k-9 the same person?",
    summary: "Possible duplicate: Ana Ruiz",
    trigger: { detector: "workflow", reason: "The match score is between the thresholds." },
    extra: { builtBy: "loader:crm" }
  },
  "outcome-reconciliation": {
    question: "Did the payment of 4,200 EUR to supplier S-77 go through?",
    summary: "Payment to S-77 with no answer",
    trigger: { detector: "workflow", reason: "The bank did not answer within 30 seconds." },
    detail: { sentAt: new Date(Date.now() - 300_000).toISOString(), requestedBy: "agent:payments", lastKnown: "timed out after 30 s" },
    extra: { proposedBy: "agent:payments" }
  },
  "contest-appeal": {
    question: "Should the decline of application app-77 stand?",
    summary: "Appeal against the decline of app-77",
    trigger: { detector: "person", reason: "Applicant A-77 asked for a human review." },
    extra: { firstReviewer: "system:underwriter" }
  },
  override: {
    question: "Should this reply be overridden before it is sent?",
    summary: "Reply to applicant A-77",
    trigger: { detector: "person", reason: "An oversight officer chose to override." }
  },
  "flag-feedback": {
    question: "Is this flagged reply a case for the evaluation set?",
    summary: "Flagged reply in flow f-2",
    trigger: { detector: "person", reason: "A user flagged the reply as wrong." },
    detail: { flag: { by: "j.lind@example.com", note: "It says declined, but the application is still open." } }
  },
  triage: {
    question: "Referral share fell to 18% against a floor of 20%. Resolve, dismiss or report it?",
    summary: "Referral share below its floor",
    trigger: { detector: "monitor", rule: "referral-share < 20 over PT24H", reason: "Referral share is 18%, against a baseline of 35%." },
    detail: {
      series: Array.from({ length: 12 }, (_, i) => ({
        at: new Date(Date.now() - (11 - i) * 2 * 3_600_000).toISOString(),
        value: [36, 35, 34, 33, 31, 29, 27, 25, 23, 21, 19, 18][i]
      }))
    }
  },
  "hand-off": {
    question: "The user asked for a person. Will you take the conversation?",
    summary: "Chat with applicant A-77",
    trigger: { detector: "workflow", reason: "The user asked for a person." }
  },
  "halt-restart": {
    question: "Should automated eligibility assessment restart?",
    summary: "Restart eligibility assessment",
    trigger: { detector: "person", reason: "The backlog is drained." },
    detail: { safeStateChecks: [{ name: "backlog drained", passed: true }] }
  },
  attestation: {
    question: "Is the eligibility model still fit for use this quarter?",
    summary: "Quarterly attestation: eligibility model",
    trigger: { detector: "schedule", reason: "The quarter ends in two days." },
    detail: { periodEnd: new Date(Date.now() + 2 * 86_400_000).toISOString() }
  },
  "oversight-quality": {
    question: "31% of payment approvals took less than the minimum review time. Reset, retrain or reassign?",
    summary: "Rushed payment approvals",
    trigger: { detector: "monitor", rule: "below-minimum-share > 0.2 over P7D", reason: "31% of reviews were under 10 seconds." },
    detail: {
      metric: "below-minimum-share",
      unit: "%",
      window: "P7D",
      comparison: ">",
      threshold: 20,
      baseline: 6,
      value: 31,
      series: [6, 7, 9, 12, 18, 24, 31].map((value, i) => ({ at: new Date(Date.now() - (6 - i) * 86_400_000).toISOString(), value })),
      contributors: [{ kind: "reviewer", ref: "tyler.jewell@akka.io", weight: 0.6 }]
    }
  }
};

const GENERIC: Sample = {
  question: "Does this need a person's decision?",
  summary: "A sample subject",
  trigger: { detector: "person", reason: "Raised from the playground." }
};

/** The raise the playground sends for `control`, on `service`. */
export function sampleFor(control: ControlSettings, service: string): RaiseRequest {
  const sample = SAMPLES[control.pattern] ?? GENERIC;
  let base: Subject;
  try {
    base = exampleSubject(control.subjectKind, sample.summary, service);
  } catch {
    base = { kind: control.subjectKind, summary: sample.summary, service, detail: {} };
  }
  return {
    control: control.control.rowId,
    ask: { question: sample.question, checks: sample.checks ?? [] },
    subject: { ...base, detail: { ...base.detail, ...sample.detail } },
    trigger: sample.trigger,
    ...sample.extra
  };
}

export const SAMPLE_PATTERNS = Object.keys(SAMPLES);
