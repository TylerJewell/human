# Oversight contract, version 2

The JSON every implementation of `/oversight/*` serves and every console reads. The types are in
[`oversight.schema.json`](oversight.schema.json); each one has a valid example in
[`examples/`](examples). Settings come from Verify v2.8 ([`verify/`](verify)).

Version 2 extends the ai-console contract (`ai-console/docs/handoff/contracts/human.md`). What
changed is listed at the end. A console written for version 1 still reads version 2: every
version 1 field keeps its name and meaning.

## Roles in the contract

| Part | Serves | Built here as |
|---|---|---|
| Service | `/oversight/*` for the events it owns | `java/human-akka`, loaded into any Akka service |
| Companion | `/api/companion/human/*`: every service's events merged, the reviewer stamped on writes | `cli/` (`human-companion`) |
| Console | Reads the companion | `console/apps/console`, `console/apps/playground`, a service's own pages |

## Service routes

All bodies and answers are JSON. Times are ISO 8601 UTC strings; durations are ISO 8601 durations.

### Reading

| Route | Answer |
|---|---|
| `GET /oversight/events?…` | `EventsPage` (query below) |
| `GET /oversight/events/{id}` | `HumanEvent`; 404 when not owned |
| `GET /oversight/summary[?control=]` | `Summary` |
| `GET /oversight/quality?window=P30D` | `Quality` |
| `GET /oversight/verdicts?since=&eventId=&limit=` | `{ "verdicts": [Verdict] }`, newest first; `eventId` keeps one event's |
| `GET /oversight/standing` | `{ "standing": [StandingDecision] }` |
| `GET /oversight/halts` | `{ "halts": [Halt] }` |
| `GET /oversight/registry` | `OverseerRegistry` |
| `GET /oversight/controls` | `{ "controls": [ControlSettings] }`: each human control the service declares, settings resolved |
| `GET /oversight/proof?since=` | `application/x-ndjson`: one `ProofRecord` per line, oldest first |
| `GET /oversight/info` | `ServiceInfo`: the service name, contract version, patterns it serves, whether dev routes are on |

Query for `GET /oversight/events`: `state` (`pending` \| `resolved` \| a state name), `pattern`,
`mode` (`HITL` \| `HOTL` \| `HALT`), `flavor`, `subject` (a subject kind), `control` (control id),
`queue`, `holder`, `roles` (comma list) with `reviewer` (subject): together they keep only the
events that reviewer can act on now ("routable"), `sla` (`at-risk` \| `overdue`), `shadow`
(`true` adds shadow events), `since`, `q` (substring of the question or summary), `limit` 1–200
(default 50), `offset`. Pending events are ordered most urgent first: overdue, then least time
left, then oldest. Resolved events are ordered by `closedAt`, newest first.

Routable means: the reviewer holds a role the current queue needs, is qualified, is not barred by
independence, has not recused, the event is not with its proposer (`CHANGES_REQUESTED`), is not
held by someone else, and the reviewer has not already voted in this round.

### Writing

Every write carries `reviewer` (`Reviewer`). The companion stamps it; a service never invents one.

| Route | Body | Answer |
|---|---|---|
| `POST /oversight/events/{id}/claim` | `{ reviewer }` | `HumanEvent` |
| `POST /oversight/events/{id}/release` | `{ reviewer }` | `HumanEvent` |
| `POST /oversight/events/{id}/decision` | `DecisionRequest` | `HumanEvent` |
| `POST /oversight/events/{id}/escalate` | `{ reviewer, reason }` | `HumanEvent` |
| `POST /oversight/events/{id}/send-back` | `{ reviewer, requested }` | `HumanEvent` |
| `POST /oversight/events/{id}/resubmit` | `{ reviewer, note? }` | `HumanEvent` (proposer only) |
| `POST /oversight/events/{id}/withdraw` | `{ reviewer, reason }` | `HumanEvent` (proposer or admin) |
| `POST /oversight/events/{id}/extend` | `{ reviewer, extra, reason }` | `HumanEvent` (once, at most P7D) |
| `POST /oversight/events/{id}/reopen` | `{ reviewer, reason }` | `HumanEvent` (closed events, accountable owner only) |
| `POST /oversight/flags` | `{ reviewer, note, flowId?, sessionId?, output?, control? }` | `HumanEvent` (a `flag-feedback` event) |
| `POST /oversight/halts` | `{ reviewer, control, lift, reason, target? }` | `HumanEvent` (a `halt-restart` event) |
| `POST /oversight/contests` | `{ reviewer, control, ref, grounds, appellant? }` | `HumanEvent` (a `contest-appeal` event) |
| `POST /oversight/overrides` | `{ reviewer, control, ref, action, rationale, replacement? }` | `HumanEvent` (an `override` event, decided at once) |
| `POST /oversight/standing/{id}/revoke` | `{ reviewer, reason }` | `StandingDecision` |

`decision` accepts every action in the decision vocabulary that the event allows. `ESCALATE`,
`SEND_BACK`, `REQUEST_MORE_INFO` and `RECUSE` do not settle the event; the first two behave as
their own routes. `REQUEST_MORE_INFO` keeps the event pending and records the question in
`infoRequests`. `RECUSE` releases the event and bars the reviewer from it.

`halts` stops or restarts `target`, the service or capability the halt applies to; without it, the
control's `halt_config.target`. A service may halt and restart one target under two controls: one
that allows `HALT` and one that allows `APPROVE`. A halt or restart named on the other control is
raised under the one that allows it.

A decision needs the reviewer's claim, with two exceptions: a later vote under `TWO_PERSON`,
`N_OF_M` or `CONSENSUS` (state `AWAITING_SECOND`), which sends the `presentedAt` it was shown the
event; and a control with no minimum review time. The minimum review time counts from the claim,
or from `presentedAt`.

Errors:

| Status | Body | When |
|---|---|---|
| 404 | `{ "error" }` | The event, control or standing decision is unknown |
| 409 | `{ "error", "state" }` | The event is not in a state that takes the call: held by another, already decided, past its deadline under `late_decision: REFUSE` |
| 403 | `{ "error" }` | The reviewer's roles, qualification, authority or independence do not allow the call |
| 422 | `{ "error", "errors": [] }` | The request is invalid: an action the event does not allow, a missing rationale, a check the event does not list, a decision under the minimum review time under `minimum_review: ENFORCE` |

### Development routes

Served only when the service turns them on (`HumanPlugins.devRoutes`; the samples read
`human.dev-routes = true` from their configuration). The conformance suite and the playground use
them; production never turns them on.

| Route | Body | Answer |
|---|---|---|
| `POST /oversight/dev/events` | `RaiseRequest` | `HumanEvent` |
| `POST /oversight/dev/clock` | `{ "advance": duration }` or `{ "reset": true }` | `{ "now", "offset" }` |
| `POST /oversight/dev/tick` | `{}` | `{ "expired": n, "atRisk": n }`: runs every deadline due at the dev clock's time |
| `POST /oversight/dev/reset` | `{}` | `{ "removed": n }`: closes every event as `KILLED` and clears halts and standing decisions |

The dev clock moves the time the service uses for deadlines, claims and review times. Real timers
still run; `tick` acts on what is due now.

## Companion routes

`human-companion` serves these on `http://localhost:9167`. It discovers services from the
registration files below and forwards each write to the event's owner, answering the owner's
response unchanged.

| Route | Does |
|---|---|
| `GET /api/companion/human/events?…` | The same query as the service, over every service, merged and paged with one opaque cursor: the answer's `next`, sent back as `cursor=`; `routable=me` uses the local reviewer; `service=` keeps one service. Answers `{ "rows": [HumanEventRow], "total", "next" }` |
| `GET /api/companion/human/events/{id}` | The event, from the service that owns it |
| `POST /api/companion/human/events/{id}/{claim\|release\|decision\|escalate\|send-back\|resubmit\|withdraw\|extend\|reopen}` | Forwarded with the stamped reviewer |
| `GET /api/companion/human/summary`, `…/quality`, `…/verdicts`, `…/standing`, `…/halts`, `…/controls` | Merged across services |
| `POST /api/companion/human/flags`, `…/halts`, `…/contests`, `…/overrides` | Sent to the service that declares the control (or that owns the flow) |
| `POST /api/companion/human/standing/{id}/revoke` | Sent to the service that lists the id |
| `GET /api/companion/human/registry` | The overseer registry the companion stamps reviewers from |
| `GET`/`PUT /api/companion/human/reviewer` | The local reviewer: `{ "subject", "name", "roles": [] }`, a person from the registry and the roles chosen |
| `GET /api/companion/human/services` | `{ "services": [ { "service", "url", "up", "info": ServiceInfo } ] }`, one per registration file |
| `POST /api/companion/human/conformance` | `{ "service" }`: runs the conformance suite against that service's dev routes and answers its report |
| `GET /` | The console (`console/apps/console`, built) |

The companion stamps `{ subject, roles, name, identityProvider: "human-companion", rehearsal:
true }`. A rehearsal decision takes effect like any other; its verdict's source is `rehearsal`,
so it never counts as evidence.

## Registration

A service writes `services/<service>.json` under `$AKKA_HUMAN_HOME` (default `~/.akka/human`) when
it starts:

```json
{ "service": "patterns", "url": "http://localhost:9164", "oversight": "/oversight",
  "contract": 2, "startedAt": "2026-10-29T14:00:00Z" }
```

The companion lists the directory, and calls `GET {url}/oversight/info` to tell a running service
from a stale file. This file stands in for runtime request 15 (a local API for pending decisions).

## The event

`HumanEvent` in the schema. The parts a console builds its page from:

- **Header:** `pattern`, `patternName`, `control`, `subject.service`/`subject.agent`, `state`, `sla`.
- **Question:** `ask.question`, with `ask.checks`.
- **Subject view, by `subject.kind`:** `subject.summary` and `subject.detail` (shapes below).
- **Decision panel, by pattern:** `allowedActions` with `actionLabels` and `effects`,
  `rationaleRequired`, `routing.reviewMode`/`required`, `minimumReviewSeconds`, `sticky`.
- **History and proof:** `history`, `decisions`, `outcome`, `conclusion`, `evidence`.
- **Time:** `now`, the service's time when it answered. Deadline rings, claim expiry and the
  minimum-review countdown count against it, not the browser's clock: a service's dev clock may be
  moved.

A console that does not know a pattern or a subject kind shows `subject.summary` and the raw
`detail`; a new pattern never breaks an older console.

### Subject detail by kind

| Kind | `detail` |
|---|---|
| `action` | `tool`, `arguments`, `authority { missing[], required }`, `recentSteps[]`; for outcome reconciliation also `sentAt`, `requestedBy`, `lastKnown` |
| `behaviour` | `metric`, `unit`, `window`, `comparison`, `threshold`, `baseline`, `value`, `series[{at,value}]`, `contributors[{kind,ref,weight}]` |
| `change` | `system`, `change`, `ref`, `proposedBy`, `builtBy`, `before`, `after`, `labels`, `evidence[{kind,ref,summary}]`, `blastRadius { useCases[], trafficShare }`, `stage` |
| `system` | `scope`, `target`, `affectedCalls`, `callsPerMinute`, `safeStateChecks[{name,passed}]`, `halted`, `lift` |
| `output` | `output`, `replacement?`, `flag? {by,note}`, `flowId`, `decision? {ref, summary, affects}` |
| `records` | `entityType`, `left {source, ref, loadedBy, fields}`, `right {…}`, `labels`, `agree[]`, `differ[]`, `score`, `method` |
| `appeal` | `original {ref, decision, summary, decidedBy, decidedAt}`, `appellant {ref, name?}`, `grounds`, `right`, `filedAt`, `excluded[]`, `attachments[{kind,ref}]` |
| `exception` | `rule {id, text}`, `scope`, `requestedBy`, `justification`, `from`, `until`, `compensating[]`, `grantor` |
| `conversation` | `channel`, `reason` (`user-asked` \| `crisis` \| `step-failed` \| `policy`), `transcript[{at, role, text}]`, `run? {workflowId, step, error}`, `takenOverBy` |
| `attestation` | `attests` (`control` \| `model` \| `framework`), `target {ref, name}`, `cadence`, `periodEnd`, `lastAttestedAt`, `evidence[{kind, ref, summary, asOf, stale}]` |

### States

`DETECTED OPEN CLAIMED IN_REVIEW AWAITING_SECOND ESCALATED CHANGES_REQUESTED` are pending.
`DECIDED APPLIED TIMED_OUT ABANDONED KILLED WITHDRAWN CLOSED REOPENED` are not.
`REOPENED` is the state of a closed event a person has reopened; the reopening raises a new event
in `related`.

### Settling and verdicts

| Outcome | `slaOutcome` | Verdict |
|---|---|---|
| Decided in time with the votes, qualification, rationale and review time required | `COMPLETED` | `upheld` |
| Decided after the deadline (`late_decision: ACCEPT_BREACHED`) | `COMPLETED` | `breached`, reason `late` |
| Decided under the minimum review time (`minimum_review: WARN`) | `COMPLETED` | `breached`, reason `below-minimum` |
| Timed out, `RESUME_WITH_DEFAULT` with a default that lets the subject proceed (`APPROVE`) | `TIMED_OUT` | `breached`, reason `fail-open` |
| Timed out, `RESUME_WITH_DEFAULT` with `DENY`, or `RAISE_TIMEOUT` | `TIMED_OUT` | `inconclusive` |
| Timed out, `ESCALATE`, at the last queue | `TIMED_OUT` | `breached`, reason `missed-deadline` |
| `ABANDONED`, `WITHDRAWN` | `ABANDONED` | `inconclusive` |
| `KILLED`, or a decision never applied | `KILLED` | `error` |

A split vote under `TWO_PERSON` or `N_OF_M` settles by `routing.splitVote`: `DENY` settles the
event denied, `ESCALATE` moves it to the next queue.

## Proof records

`GET /oversight/proof` answers the records the service appended, as Verify's audit taxonomy names
them: `HumanOversightDecisionRecordedV2` for every settling decision (and every vote, in `votes`),
`HaltExercised` and `HaltLifted`. The payloads follow `specs/corpus/aud-taxonomy.yaml` at the
pinned commit. This export stands in for runtime request 10 (oversight records in the ledger).

## What changed from version 1

- `pattern`, `patternName` and `actionLabels` on every event; `pattern` on rows and as a query.
- Five new subject kinds: `records`, `appeal`, `exception`, `conversation`, `attestation`.
- `flavor` takes Verify's names (`human-domain-decision`, …) unchanged.
- Settings follow Verify v2.8 `human_config`: `routing.groups`, `attestations`, `of`,
  `splitVote`, `independence`; `sla.atRiskAt`, `lateDecision`, `clockWhileSentBack`;
  `minimumReview`; `claimTtl`.
- `verdict` on a closed event: `conclusion`, `reasons`, `source`.
- `decision` takes every action; `RECUSE` and `REQUEST_MORE_INFO` are new.
- `conditions` on a decision (APPROVE or EDIT), carried into `outcome.conditions`.
- `now` on every event and row; typed `aiRecommendation`, `slaExtensions` and `context`.
- New routes: `quality`, `verdicts`, `controls`, `proof`, `info`, `registry`, `contests`,
  `overrides`, and the development routes.
- A rehearsal decision takes effect; only its verdict's source differs.
