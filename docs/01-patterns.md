# Patterns

A pattern is a recurring situation in which a person must step in. The fourteen below come from a
study of 206 person-related controls in the Verify corpus, the local console's oversight contract,
and three services that each built human oversight by hand (the study is in
[research/study.md](research/study.md)). They are defined as data in Verify:
`framework/schema/registries/human-patterns.yaml`.

## Only human controls carry a pattern

A control has exactly one mechanism: guardrail, sanitizer, eval, gate or human. A pattern belongs
to a control whose mechanism is `human`, in `akka_runtime.human_config.pattern`, and to no other.

- A gate that checks a person's artefact (a signed attestation, a designation of overseers) is a
  gate. It has no pattern.
- An evaluation that scores outputs is an evaluation. If a person must then review what it found,
  that review is a second control, of mechanism `human`, with its own pattern.
- Verify's mechanism-coverage auditor fails a non-human control that carries `human_config`, and a
  human control without one.

## The fourteen

| # | Pattern | Raised when | Work waits? | Who decides | Decisions (as reviewers see them) | If nobody decides |
|---|---|---|---|---|---|---|
| 1 | `action-approval` | An agent or playbook wants to take an action with an effect | Yes | Someone other than whoever, or whatever, asked | Approve · Deny · Edit, then approve · Ask for more · Escalate | Deny |
| 2 | `decision-review` | An automated decision about a person: a denial, termination, adverse notice | Yes | A qualified person inside the domain | Confirm · Do not issue · Amend · Ask for more · Escalate | Escalate; no silent default |
| 3 | `change-authorization` | A release, stage change, connector, policy, model or new use case | Yes | An approver separate from the author | Approve the change · Reject · Request changes | Escalate |
| 4 | `exception` | A rule must be set aside for a stated time | Yes | A second line, sometimes an outside authority | Grant until expiry · Refuse | Refuse |
| 5 | `match-adjudication` | The system cannot tell if two records are one thing, or which label is right | Yes, for anything using the match | A reviewer who did not load the records | Same · Different · Relabel | Stays unmatched |
| 6 | `outcome-reconciliation` | An action was sent but its result is unknown | Yes, for retries | A reviewer other than the requester | It happened · It did not happen · Retry | Escalate; never retried automatically |
| 7 | `contest-appeal` | The person affected asks for a human review, or exercises a right that needs one | No; the decision stands | Not the first decider, nor anyone in their reporting line | Uphold · Modify · Overturn · Ask for more | Escalate |
| 8 | `override` | A person disregards, replaces or reverses an output | No | The oversight person, with a reason | Override · Disregard · Reverse | — |
| 9 | `flag-feedback` | Someone marks an output as wrong, or challenges how the system is used | No | A triager | Accept as an eval case · Dismiss | Ages out |
| 10 | `triage` | A monitor, watcher or anomaly detector finds something | No | The team it is routed to | Resolve · Dismiss · Report | Escalate |
| 11 | `hand-off` | A user asks for a person, a crisis signal, or a failed step | Yes, for that conversation or run | The person on duty | Take over · Return to the agent · Close | Escalate to a fallback route |
| 12 | `halt-restart` | Something must stop now | The system itself | One person halts; two approve the restart, after safe-state checks | Halt · Approve restart · Keep halted | Stays halted |
| 13 | `attestation` | A cadence: periodic review, acceptance, independent validation | No, but evidence goes stale | An owner, a board, or an independent validator | Attest · Attest with findings · Refuse to attest | Control goes stale |
| 14 | `oversight-quality` | Review times, approval and override rates show the oversight is not working | No; can block new high-risk decisions | The control's accountable owner | Reset · Retrain · Reassign | Escalate; the signal stays raised |

The reviewer's labels map onto one vocabulary of fourteen decisions ([Specification](02-spec.md)).
"Uphold" is `APPROVE`, "Overturn" is `REVERSE`, "Take over" is `DECLINE_SYSTEM`. A label never
changes what the decision means.

## Patterns and flavors

The five human flavors (ADR-016) stay. A flavor says who decides and how tightly the person is
coupled to the system. A pattern says what situation they decide. Each pattern runs under some
flavors and not others:

| Flavor | Patterns |
|---|---|
| `human-domain-decision` | action approval, decision review, exception, match, outcome reconciliation, contest and appeal, override, triage, hand-off, attestation |
| `human-system-authorization` | action approval, change authorization, exception, outcome reconciliation, override |
| `human-monitoring` | triage, attestation, oversight quality |
| `human-feedback` | flag and feedback, attestation |
| `human-halt` | halt and restart |

## Not patterns

- **Low model confidence** is a trigger. It raises pattern 1, 2 or 11; it has no lifecycle of its
  own.
- **A reviewer who never answers** is a policy every pattern needs (timeout, escalation), not a
  pattern.
- **Designing oversight and naming competent overseers** (23 corpus controls) belongs in the
  overseer registry, not in a runtime pattern.

## The corpus today

The 38 human controls in Verify carry a pattern:

| Pattern | Controls |
|---|---|
| `halt-restart` | 10 |
| `decision-review` | 8 |
| `contest-appeal` | 8 |
| `triage` | 6 |
| `flag-feedback` | 2 |
| `action-approval`, `override`, `hand-off`, `attestation` | 1 each |

Four patterns have no human control in the corpus: change authorization, exception, match and
outcome reconciliation. Their obligations are met by gates today: a change approved before
deploy, an exception granted by a regulator. Where a source requires a person to decide at run
time, the rule above says that decision is a human control of its own beside the gate. Whether to
add those controls is in [For discussion](08-discussion.md).

The patterns with the least evidence are match (two corpus controls), outcome reconciliation (none)
and override (three). Each rests mostly on one system.
