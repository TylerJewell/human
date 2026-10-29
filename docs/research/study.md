# Human-in-the-loop patterns in Akka: a study

> **Research record (2026-10-29).** This is the study that led to this repository. Decisions made since replace parts of it:
> - The specification now lives in Verify schema v2.8 ([Specification](../02-spec.md)).
> - One decision event carries the pattern, instead of one event per pattern.
> - Only human controls carry a pattern ([Patterns](../01-patterns.md)).
> - `sr11-7.s4.use` is classed as flag and feedback.
>
> Where this study and the design documents differ, the design documents hold.

**Question.** Across Akka Verify (explainability), the Akka local console and its samples (ai-console),
and the context layer (contextlayer), do the places where a person must step in fall into a small set
of recurring types? If so, should each type be built once, in a shared repository, with a common way
to implement it in an Akka service and to present it in the Akka console or a customer's own console?

**Short answer.** Yes. The evidence settles on **fourteen patterns**. They differ in what triggers
them, what the person sees and which decisions they may make, but they share one lifecycle and one
set of policy settings. So the right shape is not fourteen implementations: it is **one oversight
engine, fourteen pattern profiles, one contract, and one console kit**. Today the SDK gives only the
durable wait, the governance schema has no settings for human controls, and the same engine has been
written three times in Java and partly again in Go, with the copies already disagreeing.

Paths below are relative to `C:\Users\tyler\explainability` (**V**), `C:\Users\tyler\ai-console`
(**A**) and `C:\Users\tyler\contextlayer-repo\service` (**C**).

## 1. Evidence

| Source | What was read |
|---|---|
| Verify corpus | All 1,747 controls under `V/framework/regulations/`, plus the packs: 206 involve a person (53 consent controls left out); only 38 are of kind `human`. Each was given one primary pattern. |
| Verify schema | The `human` kind and its five flavors (`V/framework/schema/control.schema.yaml:818-860`), `halt_config` (`:1242-1306`), ADR-016, the survey, the archived cascade-health scenarios. |
| Local console | The oversight contract (`A/docs/handoff/contracts/human.md`), its plan (`A/docs/plans/human.md`), the companion's `human/` package (Go), the console's Human page (`A/kalix-console/apps/local-console/src/features/human/`), the market study, and the open asks. |
| Samples | loan-origination (**LO**) and trace-ledger (**TL**) in `A/samples/`. |
| Context layer | Its 17 human controls (`C/governance/controls.yaml`) and their implementation (`C/src/main/java/io/akka/contextlayer/`). |
| SDK docs | "Human approval requires more than a pause … A workflow supplies the durable wait and application logic supplies the acceptance conditions" (`C/akka-context/concepts/controls.html.md:28`). |

## 2. The fourteen patterns

Counts: **corpus** = Verify controls whose primary pattern this is; **console** = situations the
local console and its samples handle; **CL** = context layer controls.

| # | Pattern | Triggered by | Blocks? | Who decides | Decisions | If nobody decides | Corpus | Console | CL |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **Action approval** | An agent or playbook wants to do something with an effect | Yes | Someone other than whoever (or whatever) asked | approve, deny, edit then approve, defer | Default decision, usually deny | 6 | LO deadline extension | cl0003, cl0043 |
| 2 | **Decision review before effect** | An automated decision about a person (denial, termination, adverse notice) | Yes | A qualified person inside the domain | approve, deny, corroborate | Escalate; no silent default | 16 | LO underwriting referral, held notice | — |
| 3 | **Change authorization** | A release, stage change, connector, policy or new use case | Yes | An approver separate from the author; level set by risk | approve, deny, request changes | Stays open, escalates | 18 | LO/TL two-person change | cl0005, cl0040, cl0006, cl0041, cl0014, cl0042 |
| 4 | **Exception or waiver** | A rule must be set aside for a time | Yes | A second line, sometimes an outside authority | grant with expiry, refuse | Refused | 5 | archived compliance exception | cl0047 |
| 5 | **Match and adjudication** | The system cannot tell if two records are one thing, or which label is right | Yes, for anything that would use the match | A reviewer who did not load the records | same, different; label | Stays unmatched | 2 | — | cl0004 |
| 6 | **Outcome reconciliation** | An action was sent but its result is unknown | Yes, for retries | A reviewer other than the requester | it happened, it did not, retry | Never retried automatically | 0 | — | cl0044 |
| 7 | **Contest and appeal** | The person affected asks for a human review | No (the decision already stands) | Not the first decider nor their subordinate; free to overturn | uphold, modify, overturn | Deadline breach is evidence | 39 | LO contest review | — |
| 8 | **Override with justification** | A person disregards or reverses an output | No | The oversight person | override, disregard, reverse, with a reason | — | 3 | console decision actions | — |
| 9 | **Flag and feedback** | Someone marks an output as wrong | No | A triager | accept as an eval case, dismiss | Ages out | 4 | LO flagged replies | — |
| 10 | **Triage of alerts, cases and incidents** | A monitor, watcher or anomaly finds something | No (nothing waits) | A team the item is routed to | resolve, dismiss, report; regulatory clock | Escalates; reporting deadline | 22 | LO behaviour monitors | cl0046 |
| 11 | **Hand-off to a person** | A user asks for a human, a crisis signal, or a failed step | Yes for that conversation or run | A person on duty | take over, return, close | Fallback route | 15 | — | cl0045 |
| 12 | **Halt and restart** | Something must stop now | System-level | One to stop; two to restart; safe-state checks before restart | halt, resume | Stays halted | 13 | LO assessment halts | cl0011, cl0012 |
| 13 | **Scheduled attestation** | A cadence: periodic review, acceptance, independent validation | No, but evidence goes stale | Board, owner, or an independent validator | attest, attest with findings, refuse | Control goes stale | 34 | — | cl0048, cl0049 |
| 14 | **Oversight quality** | Review times, override and approval rates across many decisions | Can block new high-risk decisions | Oversight owner | reset, retrain, reassign | Signal stays raised | 6 | TL figures, companion quality, console Quality tab | cl0008 |

Together these hold 183 of the 206 human-related controls. The rest (23) are about **designing**
oversight and naming competent overseers (for example `V/framework/regulations/eu-ai-act/controls-ch03-high-risk.yaml:4740`):
they belong in an overseer registry, not in a runtime pattern.

### Notes on each pattern

1. **Action approval.** The corpus requires fail-closed approval by an authenticated person who is not
   the requesting agent, one approval per action (`V/framework/regulations/aws-agentic-scoping/controls.yaml:151`;
   maker-checker at `sfc-ai-cyberattack-circular/controls.yaml:555`). Variants: *edit then approve* (the
   console's EDIT with a JSON patch), *the person executes* (CL's "Person Runs the Question", cl0043),
   and *standing approval* (approve a class of actions ahead of time; LO's standing decisions).
2. **Decision review before effect.** Health prior-authorisation and employment rules require a
   licensed or same-specialty reviewer before an adverse decision takes effect
   (`cms-prior-auth/controls.yaml:457`, `us-state-iowa/controls.yaml:555`). Qualification, not just a
   role, is the rule.
3. **Change authorization.** Separation of duties for releases and changes
   (`aicpa-soc2/controls.yaml:24`, `mifid2/controls.yaml:212`) and risk-tiered approval to adopt a use
   case (`osfi-e23/controls.yaml:999`). Six of CL's 17 human controls are this pattern.
4. **Exception or waiver.** Always time-boxed and second-line; at the extreme an outside authority
   (judicial authorization within 24 hours for remote biometric identification,
   `eu-ai-act/controls-ch02-prohibited.yaml:2768`).
5. **Match and adjudication.** Rare in the corpus (one identity-match control, one labelling control),
   but central to any system joining data across sources: CL routes every cross-source join through it.
   Independence is "did not load the records", not "did not request".
6. **Outcome reconciliation.** No corpus control names it, yet any agent that acts on outside systems
   meets it: a call timed out after sending. Retrying could act twice; a person decides. Kept as a
   **candidate** pattern until a second adopter needs it.
7. **Contest and appeal.** The largest bucket (39). The reviewer must be independent of the first
   decision and free to overturn it, and must not defer to the system's output
   (`erisa/controls.yaml:607`, `colorado-sb26-189/controls.yaml:354`, `gdpr-art22/controls.yaml:505`).
   It starts from the affected person, not the system, and does not block the original decision.
8. **Override with justification.** The EU AI Act requires that a person can disregard or reverse an
   output (`eu-ai-act/controls-ch03-high-risk.yaml:5335`); no corpus control asks for the reason, which
   the console's decision actions (OVERRIDE, DISREGARD, REVERSE) would capture.
9. **Flag and feedback.** Non-blocking; an accepted flag becomes an evaluation case (LO
   `FlaggedReplies.java:48-136`).
10. **Triage.** Monitoring with intervention (`aicpa-soc2/controls.yaml:143`, alerts within 5 s at
    `mifid2/controls.yaml:1185`) and incident decisions with 15, 10 or 2-day reporting clocks
    (`eu-ai-act/controls-ch09-surveillance.yaml:1352`). CL's cases are this pattern: nothing waits,
    a person resolves or dismisses with a note.
11. **Hand-off to a person.** User-initiated (`sg-imda-mgf-agentic/controls.yaml:664`), crisis referral
    (`china-anthropomorphic-ai/controls.yaml:376`), and manual fallback when a step fails (CL cl0045;
    `china-nfra-ai-guidance/controls.yaml:3641`).
12. **Halt and restart.** The only pattern the schema models with settings (`halt_config`: scope,
    exercisers, affected calls). Restart is unmodelled in the corpus except one control
    (`uk-mod-jsp936/controls.yaml:1047`); CL and the console require two people.
13. **Scheduled attestation.** Annual reviews, independent model validation, acceptance and assertion
    of controls (`sr11-7/controls.yaml:244, :470`; CL cl0048, cl0049, which deliberately keep accepting
    a control and asserting its evidence as separate records by different people).
14. **Oversight quality.** The corpus's one runtime rule: if more than 10% of approvals in 7 days took
    under 8 seconds, deny new high-risk decisions (`eu-ai-act/controls-ch03-high-risk.yaml:11829`).
    It judges the reviewers, not the AI, and acts on the other thirteen patterns. No corpus control
    defines how the block is lifted.

**Escalation on low model confidence** has no control of its own; it appears only as a trigger inside
other controls. It is a *trigger* (raise pattern 1, 2 or 11 when confidence is low), not a pattern.
**A reviewer who never answers** has no control at all; it is a *policy* every pattern needs
(section 3).

## 3. What the patterns share

Every pattern runs the same lifecycle and is configured along the same axes. This is what makes one
engine possible.

**Lifecycle** (the contract, `A/docs/handoff/contracts/human.md:83-84`): open → claimed → in review →
decided (or awaiting a second reviewer) → applied → closed, with escalated, changes requested
(send back), timed out, abandoned, withdrawn and reopened. "A decision is a record, not an action":
the owner of the subject applies it and reports applied (`A/docs/plans/human.md:186-188`).

**Policy axes** (one `human_config`, with a preset per pattern):

| Axis | Settings |
|---|---|
| Who | roles; qualification; in-domain or system officer; independence (not the proposer, not a contributor, not the first decider or their subordinate); quorum (single, two-person, N of M, consensus) |
| When | deadline; at-risk point; claim length; minimum review time (warn or enforce); whether the clock pauses while sent back |
| If nobody | timeout behaviour (raise, resume with a default decision, escalate, abandon); escalation tiers; what absence of the whole tier does |
| How | the allowed decisions (a subset of one vocabulary of 14); rationale required; justification on override; edits allowed |
| Afterwards | one named proof event per pattern; a verdict on the oversight itself; apply and report; reopen; turning a decision into a standing decision |
| Where | shadow or live per environment; strength (prevent, warn, record) |

**Subject renderers** are what differs on screen: an action's payload, a change's diff, two records
side by side, an appeal's original decision, a halt's safe-state checks, a flagged output.

## 4. Why build it now

**The SDK stops at the pause.** Four of the five control types have SDK constructs (guardrails,
sanitizers, evaluators with a control id, gates). Human decisions have `thenPause`, suspend and resume,
and an unassigned approval task (`A/docs/handoff/shipped/sdk.md:42-48`). Authority, independence,
deadlines, escalation, minimum review and evidence are left to every application.

**So every application rebuilt it, and the copies drift.** One contract, three Java implementations,
and a fourth partial one in the console's Go companion:

| Rule | loan-origination | trace-ledger | contextlayer |
|---|---|---|---|
| States | 15 (one never produced) | 15 plus REMOVED | 10 |
| At-risk point | 75% | 75% | 80% |
| Claim lasts | configured | 15 min | 30 min |
| Two-person vote | — | — | any non-approve settles it |
| Clock while sent back | runs | paused | runs |
| Late decision | — | accepted, verdict breached | refused |
| Minimum review time | not enforced | refused | warn or refuse |
| Qualification | hard-coded true | registry | token roles |
| Deadline clock | timers from the journal | polling over a view | one expiry timer, state computed on read |

The companion re-implements the filters, routability, urgency order and quality figures in Go. LO
also runs a second, unconnected human mechanism: its domain reviews are plain workflow pauses that
never raise an oversight event, each with its own minimum review and four-eyes code.

**The schema cannot describe a human control.** There is no `human_config` in the Verify schema; only
halts have settings. The five flavors blur patterns that differ in blocking and independence (pattern 2
and pattern 7 are both `human-domain-decision`, though one blocks and one does not), and proof event
names are not standardized (HumanDecisionAuthorizationRecorded, HumanReviewCompleted,
HumanApprovalRecorded, OutputOverrideRecorded, …).

**The console experience is locked in one app.** The Human page's generic parts (queue, claim,
minimum review countdown, four-eyes explanation, deadline ring, journey, confirmation of every write,
quality) depend only on the event's fields, but they live in `apps/local-console`; nothing is in the
shared components package, although the plan intended it (`A/docs/plans/human.md:329-330`). A
customer cannot put them in their own console. Some parts are still written for the loan sample (loan
names, "days" wording).

**The asks are all still open** (status 2026-10-26): SDK asks 10–16 (oversight ledger records, a
principal on suspend and resume, an SDK call to request a human decision, tool calls that wait for
approval, a scoped halt, a local API for pending events, windowed monitors) and explainability asks 3–9
(`human_config` per flavor, one decision vocabulary, a change as a subject, what a human verdict
concludes, halt values, shadow and strength, an overseer registry). The market study names the same
gaps as the differentiator: deadlines with timeout outcomes, qualified routing, separation of duties, a
governed stop, and override analytics (`A/docs/research/human-oversight-market.md:75-90`).

## 5. Recommendation

Build an **Akka human oversight library** as its own repository, with four parts. Design it so the SDK
and the Verify schema can absorb parts of it later; it is the working answer to SDK ask 12 and
explainability asks 3, 4 and 9.

```
 Verify schema            human_config + pattern + one decision vocabulary + named proof events
        │ (control rows name a pattern and its settings)
 Server library (Java)    engine ── profiles 1–14 ── plug-ins ── /oversight contract
        │ (REST + streams)
 Console kit (React)      generic parts + subject renderers + headless hooks + web components
        │
 Akka local console  ·  a service's own console (contextlayer)  ·  a customer's console
```

**1. Specification (in Verify).** Add `human_config` with the axes in section 3, a `pattern` field
naming one of the fourteen, one decision vocabulary, and one proof event name per pattern. Split
pattern 2 from pattern 7 (blocking versus not). Keep the five flavors as the coarse grouping the
runtime already reports on.

**2. Server library (Java, Akka SDK).**
- A pure state machine that takes the time as a parameter (the context layer's style, easiest to test).
- An event-sourced entity per human event; a consumer and timed action that drive deadlines from the
  journal (loan-origination's style, the only one that escalates on its own); a view for queues.
- The `/oversight/*` endpoint and its JSON exactly as the contract says, at the path the console reads.
- Plug-ins: the control catalog read from `human_config`; apply handlers per subject kind; halt scopes
  with safe-state checks; standing-decision matching; the identity and qualification provider.
- Bridges: `pauseForHuman(pattern, subject)` for workflows, so domain reviews raise oversight events
  instead of bare pauses; for autonomous agents, a task assigned to no agent (the SDK's external
  input) that the engine completes or fails; halt and restart through workflow `suspend` and `resume`.
- Quality figures computed once, server-side, so the console and the companion stop computing them.
- Fourteen **profiles**: presets of `human_config`, the subject kind, the default decisions and the
  proof event. A service picks a profile and overrides settings; it writes no lifecycle code.

**3. Contract.** The `/oversight/*` JSON is the stable boundary between services and every console. A
conformance suite (scenarios per pattern, including a reviewer who never answers, an escalation tier
that is empty, an override without a reason, a late decision, a split two-person vote) runs against
any implementation.

**4. Console kit (React).**
- Generic parts lifted from the console's Human page: queue with "routable to me", claim, decision
  panel with minimum review and four-eyes, required reason, the deadline ring (the context console's
  decision clock), journey and story, confirmation dialog, quality.
- Subject renderers per pattern: action payload, change diff, records side by side, appeal with the
  original decision, halt with safe-state checks, flagged output, attestation.
- Themed by tokens, so it looks native in the Akka console and takes a customer's brand.
- For consoles that are not React: headless hooks over the contract, and web components for the
  decision panel and the queue.

**First adopters.** The context layer first: it has the most patterns (nine) and its console is being
built now. Then loan-origination, which removes its second mechanism. Then the local console's Human
page moves onto the kit.

## 6. Decide first

These are where the three implementations disagree; the library needs one answer each, or a setting.

1. At-risk point (75% or 80%), and whether it is a setting.
2. Two-person votes: what settles a split; can the second reviewer be the escalation target.
3. Late decisions: refused, or accepted and recorded as a breached verdict.
4. Minimum review time: warn or enforce, per control.
5. Claim length and whether a claim can be renewed.
6. Where qualification is checked when there is no companion: the service, a shared registry, or the
   identity provider.
7. Whether the clock pauses while an event is sent back.
8. How an oversight-quality block (pattern 14) is lifted, and by whom.

## 7. Phases

| Phase | Work | Done when |
|---|---|---|
| 0 | Agree the spec: `human_config`, `pattern`, vocabulary, proof events, the eight decisions above | Verify schema and contract updated; asks 3, 4, 9 answered |
| 1 | Extract the engine from the context layer and loan-origination into the library; conformance suite | All three samples' scenarios pass against the library |
| 2 | Console kit from the console's Human page generic parts, plus renderers for patterns 1, 3, 5, 12 | The context console's Decisions and Links screens use it |
| 3 | Migrate contextlayer, then loan-origination, then trace-ledger; the companion reads the library's quality figures | No hand-written human-event code left in the three |
| 4 | Remaining profiles and renderers (2, 4, 6–11, 13, 14), each with a sample scenario | Fourteen profiles with conformance scenarios |
| 5 | SDK and platform take what belongs there (request-a-decision call, tool-call approval, scoped halt, ledger records) | The library shrinks to profiles, renderers and plug-ins |

## 8. What could make this wrong

- **Only our own code shows the duplication.** All three implementations are Akka-internal samples or
  demos. A customer pilot should confirm the patterns before phase 4.
- **Thin evidence for three patterns.** Match and adjudication (2 corpus controls), outcome
  reconciliation (0) and override with justification (3) rest mostly on one system each. They stay
  candidates, built only when a second adopter needs them.
- **Profiles could harden into code.** A profile must stay data (settings, subject kind, renderer
  name); behaviour belongs to the engine or a plug-in, or the fourteen become fourteen libraries.
- **The scenarios are stale.** 8 of the 17 control ids the cascade-health scenarios assert no longer
  exist; the conformance suite should be written fresh against the current corpus.
