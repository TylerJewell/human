import type { Flavor, Hook, PatternTour, StepText } from "./model";

// The tour's words: each pattern's story and the sentence for each step. Each
// story runs against the patterns sample (samples/patterns), which declares one
// control per pattern in spec/fixtures/controls-patterns.yaml.

export const FLAVORS: Flavor[] = [
  {
    id: "human-system-authorization",
    name: "System authorization",
    text: "A person authorizes what the system may do: an action, a change, an exception or a correction."
  },
  {
    id: "human-domain-decision",
    name: "Domain decision",
    text: "A person makes or reviews a decision about someone, or takes over from the system."
  },
  { id: "human-feedback", name: "Feedback", text: "Anyone can say an output is wrong, and a person decides what to do with it." },
  {
    id: "human-monitoring",
    name: "Monitoring",
    text: "A person answers a signal or a schedule while the work carries on."
  },
  { id: "human-halt", name: "Halt", text: "A person stops a capability at once, and restarting it takes a second decision." }
];

export const HOOKS: Hook[] = [
  {
    id: "workflow",
    name: "A workflow pauses",
    text: "A workflow step raises the event and pauses. The library resumes the workflow with the decision."
  },
  {
    id: "handler",
    name: "An apply handler acts",
    text: "The service raises the event and registers an apply handler, which carries the decision out."
  },
  {
    id: "person",
    name: "A person starts it",
    text: "A person files an appeal, an override or a flag. The library raises the event on their behalf."
  },
  {
    id: "monitor",
    name: "A monitor raises it",
    text: "A timer or a monitor raises the event, and the work it watches carries on."
  },
  {
    id: "halt",
    name: "A halt check guards it",
    text: "The capability checks its halt before every call. A person halts it, and two people restart it."
  }
];

/** The record step is the same for every pattern. */
const RECORD: StepText = {
  label: "It is on record",
  title: "The verdict and the proof record keep who decided, when, how long they looked and why.",
  text: "Every decision is written to the proof ledger as HumanOversightDecisionRecordedV2 and judged against its control. Decisions made in this local tour are rehearsals: they take effect, and they never count as evidence.",
  code: []
};

export const PATTERNS: PatternTour[] = [
  {
    id: "action-approval",
    name: "Action approval",
    control: "pt-00001",
    flavor: "human-system-authorization",
    hook: "workflow",
    summary: "A person approves an action before the system takes it.",
    story: "The payments agent wants to pay supplier S-77 4,200 EUR. Payments above 1,000 EUR wait for an AI system owner.",
    steps: {
      trigger: {
        label: "The agent asks",
        title: "The payments workflow raises an action approval and pauses.",
        text: "The step names the control, describes the call the agent wants to make, says who proposed it, and tells the library which workflow to resume. An agent's task and a guarded tool call reach the same control.",
        code: [
          {
            label: "Workflow step",
            key: "payment.ask",
            focus: ["Patterns.actionApproval", '.control("pt-00001")', ".proposedBy(", ".resumes(Resume.workflow", ".thenPause("]
          },
          { label: "Agent task", key: "agent.task", focus: ["client.forTask", "Resume.task(taskId)"] },
          { label: "Tool call", key: "agent.guardrail", focus: ["ApprovalGuardrail", "control =", "tools ="] }
        ]
      },
      decide: {
        label: "An owner decides",
        title: "An AI system owner reviews the payment for at least ten seconds, then decides.",
        text: "The control routes the event to the system owners, bars the agent that proposed it, and escalates to the head of operations after thirty minutes.",
        code: [
          {
            label: "Control",
            key: "control.pt-00001",
            focus: ["routing:", "sla_target", "escalation_chain", "minimum_review_seconds"]
          }
        ]
      },
      effect: {
        label: "The payment goes out",
        title: "The decision reaches whatever was waiting, and the payment goes out or is cancelled.",
        text: "The library resumes the workflow exactly once with the final outcome, and an edited amount arrives as a patch on the decision. An agent's approval task completes or fails with it, and a guarded tool call passes when the agent retries it.",
        code: [
          {
            label: "Workflow resumes",
            key: "payment.decided",
            focus: ['status().equals("AWAITING_APPROVAL")', "transitionTo(PaymentWorkflow::pay)", '"DENIED"']
          },
          { label: "Resumer", key: "payment.resumer", focus: [".resumer(PaymentWorkflow.RESUMER"] },
          { label: "Agent task", key: "agent.settle", focus: ["task.complete(", "task.fail("] },
          { label: "Tool call", key: "agent.toolCall", focus: ["new ApprovalGuardrail(", ".decide("] }
        ]
      },
      record: RECORD
    }
  },
  {
    id: "decision-review",
    name: "Decision review",
    control: "pt-00002",
    flavor: "human-domain-decision",
    hook: "workflow",
    summary: "A person reviews a decision about someone before it takes effect.",
    story: "The notices agent has drafted an adverse notice to Mara Lindqvist. A case reviewer reads it before it is sent.",
    steps: {
      trigger: {
        label: "The notice is held",
        title: "The notice workflow holds the letter and asks for a review.",
        text: "The event carries the letter, the decision it reports, and two checks the reviewer confirms: the notice gives the reason, and it says how to appeal.",
        code: [
          {
            label: "Workflow step",
            key: "notice.ask",
            focus: ["Patterns.decisionReview", ".check(", ".effect(", ".resumes(Resume.workflow", ".thenPause("]
          }
        ]
      },
      decide: {
        label: "A reviewer reads it",
        title: "A case reviewer confirms, amends or stops the notice after reading it for at least twenty seconds.",
        text: "The minimum review time is enforced: the decision panel opens its actions only once the reviewer has held the event for twenty seconds.",
        code: [{ label: "Control", key: "control.pt-00002", focus: ["routing:", "minimum_review_seconds", "sla_target"] }]
      },
      effect: {
        label: "The letter goes out",
        title: "The workflow sends the notice as confirmed or amended, or withholds it.",
        text: "An amendment arrives as a patch on the decision, and the workflow sends the amended text.",
        code: [
          { label: "Workflow resumes", key: "notice.decided", focus: ["Action.APPROVE", "Action.EDIT", '"WITHHELD"'] },
          { label: "Resumer", key: "notice.resumer", focus: [".resumer(NoticeWorkflow.RESUMER"] }
        ]
      },
      record: RECORD
    }
  },
  {
    id: "change-authorization",
    name: "Change authorization",
    control: "pt-00003",
    flavor: "human-system-authorization",
    hook: "workflow",
    summary: "Two people who neither proposed nor built a change approve it before it reaches production.",
    story: "K. Osei proposes rolling out model m-5 to 10% of notice-writing traffic. The trainer built it, and two model risk owners must approve.",
    steps: {
      trigger: {
        label: "A rollout is proposed",
        title: "The rollout workflow stops at the gate and asks for two approvals.",
        text: "The event shows the change as a before and after, the offline evaluation behind it, and its blast radius.",
        code: [
          {
            label: "Workflow step",
            key: "rollout.ask",
            focus: ["Patterns.changeAuthorization", "Subject.change(", "blastRadius", ".proposedBy(", ".builtBy(", ".resumes(Resume.workflow"]
          }
        ]
      },
      decide: {
        label: "Two people approve",
        title: "Two model risk owners approve in turn, and neither the proposer nor the builder may.",
        text: "The first approval moves the event to awaiting a second. The tour switches to the next eligible person after each vote.",
        code: [{ label: "Control", key: "control.pt-00003", focus: ["review_mode: TWO_PERSON", "routing:", "escalation_chain"] }]
      },
      effect: {
        label: "The rollout starts",
        title: "The workflow starts the rollout, or returns it to the proposer.",
        text: "The decision names both approvers, and the workflow records them with the rollout.",
        code: [{ label: "Workflow resumes", key: "rollout.decided", focus: ['"ROLLED_OUT"', "decision.deciders()"] }]
      },
      record: RECORD
    }
  },
  {
    id: "exception",
    name: "Exception",
    control: "pt-00004",
    flavor: "human-system-authorization",
    hook: "handler",
    summary: "A person grants a time-limited exception to a rule, with a reason.",
    story: "J. Lind asks to set aside the Friday deployment freeze, rule R-12, for a billing security fix until tomorrow evening. The compliance lead decides.",
    steps: {
      trigger: {
        label: "A waiver is requested",
        title: "The request becomes an exception event that names the rule, the scope and the expiry.",
        text: "The service raises the event itself and names an apply handler to carry the decision out. The person who asked is recorded as the proposer.",
        code: [
          {
            label: "Raise",
            key: "waiver.raise",
            focus: ["Patterns.exception", "Subject.exception(", ".proposedBy(", ".resumes(Resume.handler"]
          }
        ]
      },
      decide: {
        label: "The compliance lead decides",
        title: "The compliance lead grants the waiver until it expires, or refuses it.",
        text: "The proposer can never decide their own exception; the control bars them.",
        code: [{ label: "Control", key: "control.pt-00004", focus: ["routing:", "sla_target"] }]
      },
      effect: {
        label: "The waiver is recorded",
        title: "The apply handler writes the waiver, its expiry and who granted it to the service's records.",
        text: "The handler runs once, after the event closes, with the same decision a workflow would receive.",
        code: [
          { label: "Apply handler", key: "waiver.handler", focus: ['.handler("waiver"'] },
          { label: "Records", key: "records.handler", focus: ["RecordsEntity::write"] }
        ]
      },
      record: RECORD
    }
  },
  {
    id: "match-adjudication",
    name: "Match and adjudication",
    control: "pt-00005",
    flavor: "human-domain-decision",
    hook: "handler",
    summary: "A person decides whether two records describe the same thing.",
    story: "The matcher scored a CRM customer and a claims customer at 0.78, too close to keep apart and too far to merge. A data steward decides.",
    steps: {
      trigger: {
        label: "The matcher is unsure",
        title: "The matcher raises the two records side by side with its score.",
        text: "Whoever loaded the records is recorded as the builder, so the person who decides must be someone else.",
        code: [
          { label: "Raise", key: "match.raise", focus: ["Patterns.matchAdjudication", "Subject.records(", ".builtBy(", ".resumes(Resume.handler"] }
        ]
      },
      decide: {
        label: "A steward compares them",
        title: "A data steward says whether they are the same, different, or need relabelling.",
        text: "The decision panel shows the two records field by field, with the fields that differ marked.",
        code: [{ label: "Control", key: "control.pt-00005", focus: ["routing:", "subject_kind: records"] }]
      },
      effect: {
        label: "The match is recorded",
        title: "The apply handler records the steward's answer against the match.",
        text: "A merge, a split or a relabel follows from that record.",
        code: [{ label: "Apply handler", key: "match.handler", focus: ['.handler("match"', '"same"', '"different"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "outcome-reconciliation",
    name: "Outcome reconciliation",
    control: "pt-00006",
    flavor: "human-system-authorization",
    hook: "workflow",
    summary: "A person establishes what happened when the system cannot tell.",
    story: "The agent paid supplier S-31 860 EUR, and the payment provider timed out after accepting the request. Nothing retries it until a person says what happened.",
    steps: {
      trigger: {
        label: "The result is unknown",
        title: "The payment workflow pauses instead of retrying, and asks what happened.",
        text: "A blind retry could pay twice. The event records when the request was sent and the last thing the provider said.",
        code: [
          {
            label: "Workflow step",
            key: "payment.pay",
            focus: ["Patterns.outcomeReconciliation", '"lastKnown"', "Action.REPLAN", ".resumes(Resume.workflow", ".thenPause("]
          }
        ]
      },
      decide: {
        label: "Operations checks",
        title: "An operator checks with the provider and says it happened, it did not, or retry.",
        text: "System owners and the head of operations may answer; the agent that made the payment may not.",
        code: [{ label: "Control", key: "control.pt-00006", focus: ["routing:", "sla_target", "escalation_chain"] }]
      },
      effect: {
        label: "The workflow settles",
        title: "The workflow marks the payment paid or not paid, or sends it again.",
        text: "A retry starts a new attempt, which raises a new event if its result is unknown too.",
        code: [{ label: "Workflow resumes", key: "payment.decided", focus: ['"AWAITING_RECONCILIATION"', "Action.REPLAN", '"NOT_PAID"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "contest-appeal",
    name: "Contest and appeal",
    control: "pt-00007",
    flavor: "human-domain-decision",
    hook: "person",
    summary: "A person affected by a decision appeals it to someone independent of it.",
    story: "Mara Lindqvist received the adverse notice and disagrees with it. Her appeal goes to an appeals officer who had no part in the first decision.",
    steps: {
      trigger: {
        label: "The applicant appeals",
        title: "The applicant files an appeal against a decision that was already made.",
        text: "The library reads the contested decision, records who made it, and raises the appeal with that person as the first reviewer.",
        code: [
          { label: "Route", key: "contest.route", focus: ["human.contest("] },
          { label: "Library", key: "contest.library", focus: ["firstReviewer", "Subject.appeal(", ".firstReviewer("] }
        ]
      },
      decide: {
        label: "An independent officer hears it",
        title: "An appeals officer upholds, modifies or overturns the decision after at least thirty seconds.",
        text: "Whoever made the first decision, and their line, cannot hear the appeal.",
        code: [{ label: "Control", key: "control.pt-00007", focus: ["routing:", "minimum_review_seconds", "escalation_chain"] }]
      },
      effect: {
        label: "The applicant hears back",
        title: "The apply handler records whether the decision stands, changes or is overturned.",
        text: "The service tells the applicant the outcome from that record.",
        code: [{ label: "Apply handler", key: "contest.handler", focus: ['.handler("contest-appeal"', '"upheld"', '"overturned"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "override",
    name: "Override",
    control: "pt-00008",
    flavor: "human-system-authorization",
    hook: "person",
    summary: "A person sets aside or replaces an output the system produced, and says why.",
    story: "The eligibility assessor found Ana Ruiz not eligible. An oversight officer knows her income includes a one-off payment and reverses the output.",
    steps: {
      trigger: {
        label: "The system decides",
        title: "The eligibility assessor answers on its own, with no person in the loop.",
        text: "Oversight persons may still intervene afterwards. That right is the override control.",
        code: [{ label: "Assessor", key: "eligibility.assess", focus: ["var eligible", "new Assessment("] }]
      },
      decide: {
        label: "An officer overrides it",
        title: "An oversight officer reverses, replaces or disregards the output, with a reason.",
        text: "An override is decided the moment it is made. The library checks the person's role and qualification, raises the event and records the decision in one call.",
        code: [
          { label: "Route", key: "override.route", focus: ["human.override("] },
          { label: "Library", key: "override.library", focus: ["Qualification.refusal", "HumanEventEntity::decide"] },
          { label: "Control", key: "control.pt-00008", focus: ["routing:"] }
        ]
      },
      effect: {
        label: "The output is replaced",
        title: "The apply handler records the override against the output.",
        text: "Anything that reads the output reads the override from that record.",
        code: [{ label: "Apply handler", key: "override.handler", focus: ['.handler("override"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "flag-feedback",
    name: "Flag and feedback",
    control: "pt-00009",
    flavor: "human-feedback",
    hook: "person",
    summary: "Anyone can flag an output as wrong, and a triager decides whether it becomes an evaluation case.",
    story: "A customer asks the assistant when a refund arrives and gets a wrong answer. They flag the reply.",
    steps: {
      trigger: {
        label: "A reply is flagged",
        title: "The customer flags the reply, and the library raises a flag event for it.",
        text: "The flag keeps the output and the conversation it came from, so the triager sees exactly what the customer saw.",
        code: [
          { label: "Route", key: "flag.route", focus: ["human.flag("] },
          { label: "Library", key: "flag.library", focus: ["Subject.output(", "Pattern.FLAG_FEEDBACK"] }
        ]
      },
      decide: {
        label: "A triager decides",
        title: "A feedback triager accepts the flag as an evaluation case, or dismisses it.",
        text: "A flag that nobody answers is abandoned at its deadline, so the queue does not grow without end.",
        code: [{ label: "Control", key: "control.pt-00009", focus: ["routing:", "sla_target"] }]
      },
      effect: {
        label: "An eval case is created",
        title: "The apply handler turns an accepted flag into an evaluation case.",
        text: "The case joins the evaluation set that the next model version is tested against.",
        code: [{ label: "Apply handler", key: "flag.handler", focus: ['.handler("flag-feedback"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "triage",
    name: "Triage",
    control: "pt-00010",
    flavor: "human-monitoring",
    hook: "monitor",
    summary: "A monitor raises a signal, and a person decides whether it is a problem while the work carries on.",
    story: "The referral share of eligibility assessments usually sits near 35%. A reading of 12% crosses the 20% threshold.",
    steps: {
      trigger: {
        label: "The monitor fires",
        title: "The monitor raises a triage event when the referral share drops below its threshold.",
        text: "The event is not blocking: assessments keep running. Another low reading adds an occurrence to the open event instead of raising a new one.",
        code: [{ label: "Monitor", key: "triage.monitor", focus: ["Patterns.triage", "Subject.behaviour(", ".dedupeKey("] }]
      },
      decide: {
        label: "The risk owner triages",
        title: "A model risk owner resolves, dismisses or reports the drift.",
        text: "Reporting escalates it to compliance.",
        code: [{ label: "Control", key: "control.pt-00010", focus: ["trigger:", "routing:", "escalation_chain"] }]
      },
      effect: {
        label: "The finding is recorded",
        title: "The apply handler records whether the drift was resolved, dismissed or reported.",
        text: "Nothing waited for the answer, so nothing resumes.",
        code: [{ label: "Apply handler", key: "triage.handler", focus: ['.handler("triage"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "hand-off",
    name: "Hand-off",
    control: "pt-00011",
    flavor: "human-domain-decision",
    hook: "workflow",
    summary: "The agent hands a conversation to a person, who takes it over.",
    story: "A customer in web chat asks to speak to a person. The agent says it will bring someone in, and waits.",
    steps: {
      trigger: {
        label: "The customer asks for a person",
        title: "The conversation workflow raises a hand-off and pauses the agent.",
        text: "The event carries the transcript, so whoever takes over starts where the agent stopped.",
        code: [{ label: "Workflow step", key: "handoff.ask", focus: ["Patterns.handOff", "Subject.conversation(", ".resumes(Resume.workflow", ".thenPause("] }]
      },
      decide: {
        label: "The duty officer takes over",
        title: "The duty officer takes the conversation over, returns it to the agent, or closes it.",
        text: "The duty officer has five minutes before the hand-off escalates to the head of operations.",
        code: [{ label: "Control", key: "control.pt-00011", focus: ["sla_target: PT5M", "escalation_chain"] }]
      },
      effect: {
        label: "A person joins",
        title: "The workflow hands the conversation to the person, or back to the agent.",
        text: "Taking over stops the agent in that conversation.",
        code: [{ label: "Workflow resumes", key: "handoff.decided", focus: ["DECLINE_SYSTEM", "REDIRECT"] }]
      },
      record: RECORD
    }
  },
  {
    id: "halt-restart",
    name: "Halt and restart",
    control: "pt-00012",
    flavor: "human-halt",
    hook: "halt",
    summary: "An authorized person stops a capability at once, and restarting it takes two people and a safe state.",
    story: "S. Patel, a safety officer, sees eligibility assessments behaving oddly and halts them.",
    steps: {
      trigger: {
        label: "A safety officer halts it",
        title: "The halt takes effect immediately, and every call to the capability is refused.",
        text: "The endpoint checks the halt before each assessment, and guardrails deny the agents' tool and model calls while it holds.",
        code: [
          { label: "Guarded call", key: "eligibility.assess", focus: ["human.halt(", "halt.halted()"] },
          { label: "Guardrails", key: "halt.guardrails", focus: ["HaltGuardrails", "target ="] },
          { label: "Library", key: "halt.library", focus: ["current.halted()", "safeStateChecks"] }
        ]
      },
      decide: {
        label: "Two people restart it",
        title: "Whoever asks for the restart cannot approve it, and approval waits until every safe-state check passes.",
        text: "A restart takes two approvals, the pattern's default. The service supplies the safe-state checks; here the restart needs the assessment backlog drained.",
        code: [
          { label: "Safe-state checks", key: "halt.scope", focus: ["safeStateChecks", '"backlog drained"'] },
          { label: "Control", key: "control.pt-00012", focus: ["halt_config:", "target:", "emits_on_exercise", "emits_on_lift"] }
        ]
      },
      effect: {
        label: "Calls flow again",
        title: "Once the restart is approved, assessments are answered again.",
        text: "The halt registry lifts the halt, and the guardrails let the agents' calls through.",
        code: [{ label: "Guarded call", key: "eligibility.assess", focus: ["human.halt(", "HttpResponses.ok("] }]
      },
      record: {
        ...RECORD,
        title: "The ledger keeps the halt and the restart as their own records, beside the decision.",
        text: "A halt writes HaltExercised and an approved restart writes HaltLifted, each naming who acted. Decisions made in this local tour are rehearsals: they take effect, and they never count as evidence."
      }
    }
  },
  {
    id: "attestation",
    name: "Attestation",
    control: "pt-00013",
    flavor: "human-monitoring",
    hook: "monitor",
    summary: "A person confirms on a schedule that a system is still fit for use.",
    story: "Every quarter, a model risk owner attests that the eligibility model is still fit for use, against its latest validation report.",
    steps: {
      trigger: {
        label: "The schedule comes due",
        title: "A quarterly timer raises the attestation and sets the next one.",
        text: "The model team that built the model is recorded as the builder, so it cannot attest its own work.",
        code: [{ label: "Schedule", key: "attestation.schedule", focus: ["Patterns.attestation", "Subject.attestation(", ".builtBy(", "schedule(timers"] }]
      },
      decide: {
        label: "The risk owner attests",
        title: "A model risk owner attests, attests with findings, or refuses to attest.",
        text: "The evidence list shows each document the attestation rests on and whether it is stale.",
        code: [{ label: "Control", key: "control.pt-00013", focus: ["trigger:", "routing:", "sla_target: P14D"] }]
      },
      effect: {
        label: "The attestation is recorded",
        title: "The apply handler records the attestation for the quarter.",
        text: "A refusal is recorded the same way, and the model owner answers it.",
        code: [{ label: "Apply handler", key: "attestation.handler", focus: ['.handler("attestation"'] }]
      },
      record: RECORD
    }
  },
  {
    id: "oversight-quality",
    name: "Oversight quality",
    control: "pt-00014",
    flavor: "human-monitoring",
    hook: "monitor",
    summary: "The oversight itself is watched, and its accountable owner acts when people review too quickly.",
    story: "The hourly monitor reads how long people take to approve payments. When more than 20% of approvals in a week are made under the minimum review time, the finding goes to the accountable owner.",
    steps: {
      trigger: {
        label: "The monitor finds a breach",
        title: "The quality monitor raises a finding when a control's review times cross their limit.",
        text: "The monitor reads the library's own figures for every control it watches.",
        code: [{ label: "Monitor", key: "quality.monitor", focus: ["Figures.signals", "Patterns.oversightQuality", ".dedupeKey("] }]
      },
      decide: {
        label: "The owner acts",
        title: "The accountable owner resets the reviewers, retrains them, or reassigns the work.",
        text: "The finding names the control, the figure and the limit it crossed.",
        code: [{ label: "Control", key: "control.pt-00014", focus: ["trigger:", "metric:", "routing:"] }]
      },
      effect: {
        label: "The response is recorded",
        title: "The apply handler records what the owner chose to do.",
        text: "The quality figures show whether the response worked over the following weeks.",
        code: [{ label: "Apply handler", key: "quality.handler", focus: ['.handler("oversight-quality"'] }]
      },
      record: RECORD
    }
  }
];

export const patternById = (id: string | undefined) => PATTERNS.find((p) => p.id === id);
