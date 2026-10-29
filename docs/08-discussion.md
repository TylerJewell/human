# For discussion

## Decided, 2026-10-29

| Item | Decision |
|---|---|
| 1. Mixed controls | Split each of the twelve into two controls: the human control keeps its id, the gate, guardrail or eval gets a new one. In the Verify follow-up |
| 2. Decisions covered only by gates | Add a human control beside each gate where the source requires a run-time decision. In the Verify follow-up |
| 3. Verify follow-up | One pull request on `akka/governance-explainability` with items 1, 2 and 6 and the rule's wording |
| 4. Defaults | Keep both: late decisions accepted and breached; the clock runs while sent back |
| 5. One decision event | `HumanOversightDecisionRecordedV2` carrying `pattern`, as merged |
| 6. Evidence names | Reviewed per control in the Verify follow-up |
| 7. Outcome reconciliation | Stays a full pattern: contextlayer (cl0044) and the patterns sample use it |
| 8. `sr11-7.s4.use` | Flag and feedback, as merged |
| 9–11 | Answered by the build (below) |
| 12. Housekeeping | Commit locally; no GitHub repository yet |
| 13. Runtime epic #5335 | Not now |
| 14. contextlayer choices | Keep as built: decisions go through `/oversight`; split halt and restart controls stay supported; the stricter independence stays |
| 15. loan-origination choices | Keep as built; the template review stays change authorization |
| 16. Rehearsal | Rehearsal decisions take effect; their verdicts never count as evidence |
| 17. Kit candidates | All four move into `human-kit`: the decision clock, `subject.ref` on queue rows, refresh after a decision, the service-time helpers |

The sections below keep the reasoning behind each item.

## 1. Human controls that carry a second mechanism

A control has one mechanism. Twelve of the 38 human controls also carry another mechanism's
configuration block, which mixes two controls into one:

| Control | Flavor | Pattern | Also carries |
|---|---|---|---|
| `aws-agentic-scoping.scope2-human-approval` | system authorization | action approval | `guardrail_config` |
| `eu-ai-act.art14.4.d` | system authorization | override | `gate_config`, `guardrail_config` |
| `colorado-sb26-189.6-1-1705.human-review` | domain decision | contest and appeal | `gate_config` |
| `gdpr-art22.art22.4` | domain decision | contest and appeal | `gate_config` |
| `uk-ico-ai.a3.humanreview` | domain decision | contest and appeal | `gate_config` |
| `peru-ai-sd-115-2025.art31.4.high-risk-human-oversight` | domain decision | decision review | `gate_config` |
| `eu-ai-act.art73.reporting-workflow` | domain decision | triage | `eval_config` |
| `eiopa-ai.p6.human-oversight` | halt | halt and restart | `gate_config` |
| `eu-ai-act.art5.3.urgent-halt-workflow` | halt | halt and restart | `gate_config` |
| `eu-ai-act.art14.4.e` | halt | halt and restart | `gate_config` |
| `nato-ai-principles.p5.governable` | halt | halt and restart | `gate_config` |
| `uk-mod-jsp936.s4.halt-provision` | halt | halt and restart | `gate_config` |

Most of the gates check that oversight personnel are designated and trained, or that an
attestation exists: a real obligation, but a gate's. Proposal: split each into two controls, the
gate keeping a new id and the human control keeping the original. Drop the block instead where it
restates the human control.

## 2. Human decisions the corpus covers only with a gate

Change authorization, exception, match and outcome reconciliation have no human control. Some
sources require a person to decide at run time, and only a gate covers them today. Examples from the
study: change approval under SOC 2 CC8.1 and MiFID II RTS 6 Art 5, judicial authorization for
remote biometric identification (EU AI Act Art 5(3)), and two people verifying a biometric match
(EU AI Act Art 14(5)), which is match and adjudication. Proposal: review the study's candidate
lists and add a human control beside the gate wherever the source requires a decision at run time.

## 3. The Verify follow-up

Wording for ADR-018, the `human_config` description and the pattern registry header: only human
controls carry a pattern; a person reviewing a gate's or an evaluation's result is a second, human
control. Ready to write once items 1 and 2 are settled, so one pull request covers all three.

## 4. The eight defaults

The table in [Specification](02-spec.md). They are settings, but every service will start from
them. Two deserve a second look:
- **Late decisions accepted (breached).** contextlayer refuses them. Accepting keeps the record of
  what the person decided; refusing keeps the outcome final at the deadline.
- **The clock keeps running while sent back.** Regulatory deadlines do not pause; trace-ledger
  paused it because the proposer, not the reviewer, holds the event.

## 5. One decision event for every pattern

The study proposed one proof event per pattern. Verify has one, `HumanOversightDecisionRecordedV2`,
carrying `pattern`: fourteen variants would share one payload. Evidence lists then match on the
event and its pattern.

## 6. Other decision-evidence names in the corpus

Human controls still name evidence such as `HumanReviewCompleted`, `HumanApprovalRecorded` and
`OutputOverrideRecorded`. Some are the decision record and should become
`HumanOversightDecisionRecordedV2`; some are domain records (an access request fulfilled). Each needs
a per-control review.

## 7. Outcome reconciliation: pattern or candidate

No corpus control names it; only contextlayer uses it. Verify lists it as a full pattern. The
study suggested keeping it a candidate until a second service needs it.

## 8. One reclassification

`sr11-7.s4.use` asks for challenge from sources independent of the business line while the model
is in use. The study classed it as attestation; Verify has it as flag and feedback.

## 9. Library endpoints and access control

Answered in phase 2. No configuration changes `@Acl` or `@JWT` on an endpoint loaded from a
library. A service that needs its own authorization subclasses `OversightEndpoint` with its own
`@HttpEndpoint`, `@Acl` and `@JWT`, and leaves the library's endpoint out of
`akka.javasdk.library.components`; the inherited routes are then served under the subclass's
settings (tested: 400 without a bearer token, 200 with one). Worth asking the SDK team whether a
configuration override is wanted, but nothing blocks.

## 10. Components that stand alone

Done: `human-kit` depends only on React and valibot, themed by `--hk-*` CSS tokens that follow the
Akka console's light and dark classes.

## 11. The tool-call stand-in

Built (`ApprovalGuardrail` in `java/human-runtime-local`): a call that needs approval is denied
with "awaiting approval: <event id>", and the retried call passes once after approval, or at once
under a standing decision. The agent still sees a failure, not a wait, and it was tested by calling
the guardrail directly, not inside a model's tool loop. Acceptable until SDK request 13 ships?

## 12. Housekeeping

- When to create `akka/human` on GitHub.
- Ports for the sample services, added to the port table in `C:\Users\tyler\CLAUDE.md`.
- Who updates the oversight contract in ai-console, and when, so the console stops writing
  `flavor: custom-human`. Version 2 ([`spec/CONTRACT.md`](../spec/CONTRACT.md)) is what to adopt;
  its last section lists what changed from version 1.

## 13. The runtime team's epic

`lightbend/akka-runtime#5335` ("Epic: Human participation", out of scope for governance phase one)
covers approval gates (#5336), oversight and interventions (#5337), human-based evaluations (#5338)
and marking HITL tasks (#5196). This library is a working build of #5336, #5337 and #5196, plus
deadlines, routing, verdicts and the console. To agree with its authors before anything moves:
the five flavors and fourteen patterns as the epic's categories; the ledger record shapes (the
stand-in writes Verify's `HumanOversightDecisionRecordedV2`, `HaltExercised`, `HaltLifted`); and
where a human evaluation ends and a human control begins (#5338's example `gdpr-art22.art22.4` is
a contest-appeal control here).

## 14. Choices made while porting contextlayer

`samples/contextlayer` keeps all seventeen row ids, names and flavors. Settings that changed:
- **Gate decisions become proposals.** contextlayer let the caller decide a gate in the same call.
  Here an eligible person decides through `/oversight`, which is what lets independence and the
  Decisions screen work.
- **Independence.** Link review (cl0004) bars both people who loaded the two records
  (`[proposer, builder]`, was `[contributors]`); domain joins (cl0014) bar whoever last changed
  either domain (`[proposer, builder]`, was `[proposer]`). Evidence assertion (cl0049) has none,
  so the administrator decides alone, as today.
- **Halt.** cl0011 allows only `HALT`; restarting is cl0012's two-person decision on the same
  target. The contract now says a service may split a halt and its restart across two controls.
- **Patterns that differ from the names.** Failed Step Hand-Off (cl0045) is triage, since the run
  does not wait; Control Acceptance (cl0048) and Evidence Assertion (cl0049) are change
  authorization; Person Runs the Question (cl0043) is action approval.
- **Acceptance staleness.** cl0048 goes stale when the catalog digest changes, worked out against
  the digest in force rather than as an expiring standing decision.

## 15. Choices made while porting loan-origination

`samples/loan-origination` runs without model keys, with rule-based stand-ins for its agents.
- **New human controls** for what were plain pauses: held adverse notices and underwriting review
  (decision review), and the applicant's contest (contest and appeal).
- **The template review is change authorization**, not decision review: a template changes every
  notice for its decline reason.
- **Settings that changed:** S1's timeout applies DENY at the first deadline instead of walking the
  escalation chain; S3's metric becomes the library's below-minimum share above 0.1, with no floor
  of five reviews; S5's independence is proposer and builder (the library has no "one approver of
  each role" rule); S6 allows `HALT`, keeps the two-person restart, and drops its minimum review
  time.

## 16. Rehearsal decisions take effect

A decision stamped `rehearsal` (every decision made through the local companion) changes the event
and resumes the workflow; only its verdict's source is `rehearsal`, so it never counts as evidence.
contextlayer's own engine recorded rehearsals without effect. Taking effect is what lets a local
console drive a service end to end.

## 17. What the service screens built around the kit

contextlayer's and loan-origination's own screens use `human-kit` but had to add, in the app:
- a decision clock (minimum-review ring, who may decide with independence strikes);
- reading whole events to match a review to its record, since queue rows carry no `subject.ref`;
- refreshing the service's own data after a decision (the kit refreshes only its own reads);
- the kit's service-time helper and deadline sentence, which are not exported;
- per-screen action labels (the panel always shows the control's labels).

Each is a candidate for the kit: the first and second are the likeliest to recur in any service.
