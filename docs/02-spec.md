# Specification

Verify defines what a human control declares. Schema v2.8 merged to
Verify's main branch as `641097fa` on 2026-10-29; the decisions are in
Verify's ADR-018, human patterns. This repository reads these files and defines
none of its own.

| File in Verify | Holds |
|---|---|
| `framework/schema/control.schema.yaml` | `akka_runtime.human_config`, `halt_config` |
| `framework/schema/registries/human-patterns.yaml` | The fourteen patterns and every default |
| `framework/schema/registries/human-decisions.yaml` | The decision vocabulary, sticky modes, verdicts, verdict sources |
| `framework/schema/overseer-registry.schema.yaml` | Who may decide, and why |
| `specs/corpus/aud-taxonomy.yaml` | The proof events |

## `human_config`

Every human control carries it. `pattern` and `subject_kind` are required; everything else is
optional. A setting the control leaves out comes from its pattern's defaults, then from the shared
defaults; a deployment may override any of them when it writes its bundle.

| Group | Fields |
|---|---|
| What | `pattern`, `subject_kind` (action, behaviour, change, system, output, records, appeal, exception, conversation, attestation), `trigger` (detector, condition; for monitors a metric, comparison, threshold, window and baseline) |
| Who | `routing`: roles, groups, attestations, review mode (`SINGLE`, `TWO_PERSON`, `N_OF_M`, `CONSENSUS`), votes required, independence (`proposer`, `builder`, `first-reviewer`, `first-reviewer-line`), what settles a split vote. `accountable_owner` |
| When | `sla_target`, `at_risk_at`, `claim_ttl`, `minimum_review_seconds` and whether it is enforced, `clock_while_sent_back`, `late_decision` |
| If nobody | `timeout_behaviour` (`RAISE_TIMEOUT`, `RESUME_WITH_DEFAULT`, `ESCALATE`, `ABANDON`), `timeout_default`, `escalation_chain` (queues, each with the roles that decide there and when it starts) |
| How | `allowed_actions`, `sticky_modes`, `rationale_required`, `ai_recommendation` (a judge agent that reviews first) |
| Afterwards | `proof_event` |
| Where | `shadow` (record "would have raised" without routing), `strength` per environment (`prevent`, `warn`, `record`) |

A corpus control states only what its source text requires: today, pattern and subject kind. Roles,
deadlines and escalation queues are a deployment's to fill in.

## Shared defaults

The three hand-built implementations disagreed on eight rules. Each is now a setting, with this
default:

| Rule | Default | loan-origination / trace-ledger / contextlayer had |
|---|---|---|
| When an event shows as at risk | 75 % of the deadline | 75 % / 75 % / 80 % |
| What settles a split two-person vote | any vote other than approve | not defined / not defined / any non-approve |
| A decision after the deadline, on an event still open | accepted; its verdict is breached | not defined / accepted / refused |
| A decision under the minimum review time | refused (hand-off only warns) | not enforced / refused / either |
| How long a claim lasts | 30 minutes; it can be claimed again | configured / 15 / 30 minutes |
| Where qualification is checked | by routing, against the overseer registry | hard-coded / registry / token roles |
| The clock while an event is sent back | keeps running | runs / paused / runs |
| Who lifts an oversight-quality block | the control's accountable owner, with a reason | undefined |

## One decision vocabulary

`APPROVE`, `DENY`, `EDIT`, `REDIRECT`, `REPLAN`, `ESCALATE`, `HALT`, `RECUSE`, `REQUEST_MORE_INFO`,
`SEND_BACK`, `DECLINE_SYSTEM`, and for decisions made after the output `OVERRIDE`, `DISREGARD`,
`REVERSE`. The names the audit taxonomy, the scenario schema and `humans.md` used before are kept as
aliases. Each pattern allows a subset and gives each a label for reviewers.

Sticky modes let a decision extend beyond its event: `APPROVE_ONCE` (the default),
`APPROVE_FOR_SESSION`, `APPROVE_FOR_PATTERN`, `DENY_FOR_PATTERN`.

## What a human verdict concludes

A human verdict judges the oversight, not the AI's action. A reviewer who denies an unsafe action
has made the control work.

| Verdict | When |
|---|---|
| upheld | An authorised, qualified reviewer decided in time, with a rationale where required, after the minimum review time, with the votes the review mode needs, whatever they decided |
| breached | A fail-open timeout, no authority or qualification, under the minimum review time, late, a missed deadline with no escalation, or the two-person rule broken |
| inconclusive | Abandoned, withdrawn, or a fail-closed timeout |
| error | Killed, or the decision was never applied |

Verdicts carry a source: `runtime`, `simulated` or `rehearsal`. Only `runtime` counts as evidence.

## Halts

`halt_config` keeps its shape (scope, exercisers, endpoint, affected calls, safe state) and gains
`target`, the service or capability a scoped halt stops. Exercising a halt always records
`HaltExercised`; lifting it records `HaltLifted`. Restarting is a decision under the control's
`human_config`, by default two reviewers.

## Proof events

`HumanOversightDecisionRecordedV2` records every decision: event, control, pattern, subject kind,
reviewer and roles, decision, sticky scope, votes, rationale, review time, whether it was late, its
verdict and its source. `HaltExercised` and `HaltLifted` record halts. The original
`HumanOversightDecisionRecorded` is deprecated; its events stay valid.

## Overseer registry

A deployment's list of the people who may decide: identity, name, roles, groups, who they report to
(for the `first-reviewer-line` rule), authority limited to controls and decisions, attestations with
completion and expiry, and a qualified flag with its reason. Routing refuses a decision from anyone
not qualified for the control. The corpus holds no registry.
