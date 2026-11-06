# Oversight contract, version 3

The JSON every implementation of `/oversight/*` serves and every console reads. The types are in
[`oversight.schema.json`](oversight.schema.json); each one has a valid example in
[`examples/`](examples). Settings come from Verify v2.8, and a verdict is Verify's verdict record
(verdict schema v1); both are pinned in [`verify/`](verify).

Version 3 changes one type, `Verdict`, to the record Verify defines. Every other type is as in
version 2, and version 2 extends version 1: a console written for version 1 reads every version 1
field with its name and meaning. What changed is listed at the end.

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
| `GET /oversight/verdicts?since=&until=&eventId=&limit=&offset=` | `VerdictsPage` (query below) |
| `GET /oversight/standing` | `{ "standing": [StandingDecision] }`; each carries `service`, the name `GET /oversight/info` reports |
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

Query for `GET /oversight/verdicts`: `since` and `until`, inclusive, bound the verdicts by
`observedAt`; `eventId` keeps one event's; `limit` 1–200 (default 50); `offset` (default 0).
Verdicts are newest first, equal times in verdict id order. The answer is a `VerdictsPage`:

```json
{ "verdicts": [ Verdict ], "total": 130, "hasMore": true, "nextOffset": 50 }
```

`total` counts every verdict the query matches, not only the page. `hasMore` is true when verdicts
follow the page, and `nextOffset` is the `offset` that reads them (null when none follow). To read a
set whole, send `until` with the first request, set to the time of the request, and follow
`nextOffset`: a verdict reached afterwards falls after `until`, so it cannot shift the pages.

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

### Oversight-quality blocks

An oversight-quality event judges the control its `subject.ref` names: the monitored control of the
quality figures. While it blocks that control, the service holds new decisions on the control's
events until the people the oversight-quality control routes to (its accountable owner, in pt-00014)
reset it.

- **What is refused.** `decision` with any action but `ESCALATE`, `SEND_BACK`, `REQUEST_MORE_INFO`,
  `RECUSE` or `HALT` answers 409 `{ "error", "state", "blockedBy" }`: `error` names the
  oversight-quality event and `blockedBy` is its id. Claim, release, escalate, send back, resubmit,
  extend and withdraw still work, and new events are still raised; they wait. The clock keeps running,
  so a blocked event escalates or times out under its control's settings.
- **What shows it.** Every pending event of the blocked control carries `blockedBy`, the
  oversight-quality event's id, in `GET /oversight/events`, `GET /oversight/events/{id}` and the
  answer to every write; it is null otherwise.
- **When it lifts.** For each oversight-quality control and monitored control, the latest event
  decides. It blocks while pending, escalated included, and after it times out or is abandoned. It
  lifts when that event is settled with Reset (`APPROVE`), Retrain (`REPLAN`) or Reassign
  (`REDIRECT`), or withdrawn or killed. Settling a newer event against the same control, such as the
  one that reopens a timed-out event, lifts it as well.
- **Which controls.** The setting is `block_decisions` on the oversight-quality control
  (`blockDecisions` in `ControlSettings`), on by default for oversight-quality and false for every other
  pattern. Verify v2.8 gives a control no risk level, so every control an oversight-quality event
  judges is treated as high-risk: the quality control's metric and threshold choose which controls,
  and `block_decisions: false` turns the block off. Only an event routed at strength `prevent` blocks;
  under `warn`, or recorded without routing, it does not. Verify v2.8's `human_config` allows no other
  keys, so a bundle can set `block_decisions` only once Verify adds it; until then the default holds.
- **What it does not hold.** Acts a person starts and that are decided at once: exercising a halt
  (`POST /oversight/halts`) and an override (`POST /oversight/overrides`). A standing decision granted
  before the block still settles a matching new event; revoke it to stop that. The service reads blocks
  from its queue, so an oversight-quality event raised a moment ago can take a moment to block.

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
| `GET /api/companion/human/summary`, `…/quality`, `…/standing`, `…/halts`, `…/controls` | Merged across services; each list item keeps the `service` it carries, or gains the name of the service it came from |
| `GET /api/companion/human/verdicts?since=&until=&eventId=&limit=&offset=` | Every service's verdicts merged newest first and paged like a service's: a `VerdictsPage` whose `total` sums the services'. Each verdict is passed on unchanged, because its digest covers every field; `subject.service` names its service |
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
from a stale file. It stands in for a runtime API that lists the pending decisions of the services running on the machine; until the runtime has one, the companion reads these files.

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

### Related events

`related[]` holds `{ eventId, relation }`: another event, and how it stands to this one. The
relation is one of these names, or a name of the service's own that starts with `x-` (lower-case
words joined by hyphens, such as `x-supersedes`). No other name is valid: the library refuses to
raise an event with one, and the schema rejects it.

| Relation | Meaning | Set by |
|---|---|---|
| `reopens` | On the new event of a reopening: the closed event it reopens | The library, on `reopen` |
| `reopened-as` | On a closed event that was reopened: the new event the reopening raised | The library, on `reopen` |
| `reraised-after` | On an event raised because the event it names closed without a decision: the review or contest is raised again so the item is not stranded | A service, when it raises again |
| `held-again-after` | On an event raised again for an item that an earlier event held: the earlier event | A service, when it raises again |
| `proposed-by` | On an event raised on the proposal of a decision, such as a halt a reviewer proposed when deciding: the event holding that decision | A service, when it raises the proposed event |

A service names a relation with the constants of `Relations` in `human-api`; the library sets
`reopens` and `reopened-as` through the same constants.

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

A verdict is Verify's verdict record, and Verify's rules (`specs/platform/verdicts.md` at the pinned
commit) reach the same conclusion from its `detail` as the table above. A service fills it as follows:

- `verdictId` is `vd-` and the event id; `observedAt` is when the outcome was applied, or when the
  event closed.
- `control.rowId` is the control id, which must have 8 to 64 lower-case letters, digits and hyphens.
  Nobody has signed the controls a service declares, so `control.bundle` is a draft: the service as
  `systemId` and the control's revision as `draftRevision`.
- `environment` is `production` when the service runs in production, and `pre-production` otherwise.
- `reason.code` is the first reason the table gives; `reason.text` says every reason in a sentence.
- `detail.workItem` is the event and its queue. A decision a person settled adds the reviewer, the
  times, the review seconds, `action` and `rationale`, and `votes` where the control takes more than
  one. One a standing decision settled adds `standing`: its id, its mode, who granted it and when. A
  timeout adds `timeout`, with `exhausted` when an escalation reached the last queue.
- `integrity.digest` is `sha256:` and the hex SHA-256 of the RFC 8785 canonical JSON of the record
  without `integrity`. Absent fields are left out, never null, and nothing may add a field afterwards.

### What a reviewer is shown

A service whose controls include a sanitizer control with `sanitizer_config.injection_point:
before-human-display` masks every event it answers with its configured sanitizers
(`akka.javasdk.sanitization`). It masks the text of the subject's `summary` and `detail`, the
trigger's `reason` and `values`, the `ask`, the `evidence`, the AI recommendation's `rationale` and
the `context`. Ids, people, settings and verdicts are left as they are, so the event can still be
routed and decided. The service's own record keeps the text as raised. Without such a control
nothing is masked: a reviewer sometimes needs the value itself, such as an account number on a
payment they approve.

## Proof records

`GET /oversight/proof` answers the records the service appended, as Verify's audit taxonomy names
them: `HumanOversightDecisionRecordedV2` for every settling decision (and every vote, in `votes`),
`HaltExercised` and `HaltLifted`. The payloads follow `specs/corpus/aud-taxonomy.yaml` at the
pinned commit. It stands in for the runtime writing oversight records to its own ledger; until it does, each service serves the records it appended.

## What changed from version 2

- `Verdict`, in `GET /oversight/verdicts` and in `verdict` on a closed event, is Verify's verdict
  record. Its fields map from version 2's as follows:
  - `id` is `verdictId`, and `at` is `observedAt`;
  - `eventId` and `boundary` are `detail.workItem`;
  - `reasons` is `reason`, with the first reason as its code;
  - `action` is `detail.action`, lower-case with hyphens (`request-more-info`);
  - `deciders` are `detail.votes`, `detail.reviewer` or `detail.standing`;
  - `pattern` is `detail.pattern`;
  - `source.service` is `subject.service`.

  `control.name`, `subjectKind` and `appliedAt` are not in the record: the control's name is in
  `GET /oversight/controls`, and the subject kind and the time applied are on the event.
- The companion no longer adds `service` to a verdict.
- `ServiceInfo.contract` is 3.
- A sanitizer control at `before-human-display` masks what a reviewer is shown (What a reviewer is
  shown).

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
- `subject.ref` on rows, so a service can match a queued event to its own record.
- New routes: `quality`, `verdicts`, `controls`, `proof`, `info`, `registry`, `contests`,
  `overrides`, and the development routes.
- A rehearsal decision takes effect; only its verdict's source differs.

Added to version 2 afterwards, without changing a field's meaning:

- `GET /oversight/verdicts` takes `until` and `offset` and answers `total`, `hasMore` and
  `nextOffset` (`VerdictsPage`).
- `service` on every `StandingDecision`.
- The names `related[].relation` can carry (Related events); a name of a service's own starts
  with `x-`.
