# patterns

One human control per oversight pattern ([`spec/fixtures/controls-patterns.yaml`](../../spec/fixtures/controls-patterns.yaml)),
each raised the way its pattern is used. The service runs on port **9164** with the development
routes on, registers itself in `~/.akka/human/services/patterns.json` for the companion, and serves
the whole oversight contract at `/oversight/*` ([`spec/CONTRACT.md`](../../spec/CONTRACT.md)).

People decide the events through `/oversight`: claim, decide, escalate, send back and so on. The
reviewers are the ones in [`spec/fixtures/overseers.yaml`](../../spec/fixtures/overseers.yaml); the
roles a call carries must be ones the registry gives that person.

## Run

```
mvn compile exec:java          # or akka_local_run_service on this directory
curl localhost:9164/oversight/info
```

`mvn verify` runs the TestKit integration tests in-process.

## The pattern tour

The service serves the pattern tour at http://localhost:9164/. Each of the fourteen patterns runs
end to end through this service's own routes in four steps (what happens, who decides, what changes,
what is kept), with the code behind each step beside it. The tour draws the screens of the people
around a decision; the overseer's screens are the human-kit components; the code is cut from this
repository's files when the tour is built (`ui/src/code/sources.ts`), so it is the code the service
runs. "Reset the tour" closes every open event through `POST /oversight/dev/reset`.

The tour is `ui/`. `pnpm build` there writes it into `src/main/resources/static-resources`, and
`pnpm dev` serves it on port 5185 against the running service.

## How the library is wired

- `PatternsSetup` builds the `HumanPlugins`: the fixture catalog and overseer registry, a resumer
  for each waiting workflow, an apply handler for each raise-and-carry-on pattern, the halt scope of
  `eligibility-assessment`, the local proof ledger and the development clock. Its dependency
  provider hands the plug-ins and the `HumanClient` to every component.
- `application.conf` lists the library components the service loads, configures the tool-call
  approval guardrail (`pay` needs pt-0001) and the halt guardrails of `eligibility-assessment`, and
  sets `human.environment`, `human.dev-routes` and `human.register`.

## Domain routes

Each route below makes the service raise its control. The answer names the workflow or event; the
workflow's state, or `GET /oversight/events/{id}`, shows how it ends.

| Pattern | Control | Route | Body | What happens |
|---|---|---|---|---|
| Action approval | pt-0001 | `POST /payments` | `{ supplier, amount, currency?, resultUnknown? }` | `PaymentWorkflow`: above 1,000 it raises an approval and pauses (backstop 2 h); approve pays, EDIT pays the patched `/amount`, deny or a timeout cancels |
| Outcome reconciliation | pt-0006 | `POST /payments` with `resultUnknown: true` | as above | The payment's result is unknown: a person records whether it happened (APPROVE), did not (DENY), or retries it (REPLAN) |
| | | `GET /payments/{id}` | | The payment, with the events it raised |
| Decision review | pt-0002 | `POST /notices` | `{ applicant, text, decisionRef }` | `NoticeWorkflow`: the adverse notice waits for a case reviewer; EDIT sends the patched `/output` |
| | | `GET /notices/{id}` | | |
| Change authorization | pt-0003 | `POST /rollouts` | `{ model, from, to, proposedBy, builtBy, trafficShare }` | `RolloutWorkflow`: two model-risk owners, neither proposer nor builder, approve; a send-back returns it to the proposer, who resubmits through `POST /oversight/events/{id}/resubmit` |
| | | `GET /rollouts/{id}` | | |
| Exception | pt-0004 | `POST /waivers` | `{ ruleId, ruleText, scope, until, requestedBy, justification, compensating? }` | The waiver handler records it granted until `until`, or refused (`GET /records/{waiverId}`) |
| Match and adjudication | pt-0005 | `POST /matches` | `{ entityType, left, right, loadedBy, score }` | Whoever loaded the records may not decide; the match handler records same, different or relabelled |
| Contest and appeal | pt-0007 | `POST /decisions/{ref}/contest` | `{ appellant, grounds }` | `ref` is a closed event of this service; whoever decided it, and their reporting line, may not hear the appeal |
| Override | pt-0008 | `POST /outputs/{ref}/override` | `{ reviewer, action, rationale, replacement? }` | Decided at once: OVERRIDE (with the replacement), DISREGARD or REVERSE |
| Flag and feedback | pt-0009 | `POST /replies/{flowId}/flag` | `{ by, note, output }` | A triager accepts the flag as an evaluation case or dismisses it; the handler writes the case (`GET /records/{eventId}`) |
| Triage | pt-0010 | `POST /monitor/referral-share` | `{ referralShare }` | `ReferralMonitor` raises a triage event below 20%; repeated readings add occurrences to the pending one |
| Hand-off | pt-0011 | `POST /handoffs` | `{ channel, reason?, userSaid: [] }` | `HandOffWorkflow`: the person on duty takes over (DECLINE_SYSTEM), returns it to the agent (REDIRECT) or closes it |
| | | `GET /handoffs/{id}` | | |
| Halt and restart | pt-0012 | `POST /oversight/halts` | `{ reviewer, control: "pt-0012", lift: false, reason }` | One safety officer halts `eligibility-assessment` at once; `POST /eligibility/assess` then answers 503 |
| | | `POST /oversight/halts` with `lift: true` | | A restart event; two other safety officers approve it, only while every safe-state check passes |
| | | `PUT /eligibility/safe-state` | `{ backlogDrained }` | Sets the safe-state check the restart needs |
| | | `POST /eligibility/assess` | `{ applicant, income }` | The capability itself; refused while halted |
| Attestation | pt-0013 | `POST /attestations/run` | `{}` | `AttestationSchedule` raises the quarter's attestation now; it also runs at the start of each quarter |
| Oversight quality | pt-0014 | `POST /quality/check` | `{}` | `QualityMonitor` reads the quality signals and raises an event for each figure over its limit; it also runs hourly |
| | | `GET /records/{id}` | | What an apply handler recorded for an event or a waiver or match id |

### Agents

| Route | Body | What happens |
|---|---|---|
| `POST /agent-approvals` | `{ agentId, tool, arguments }` | Creates an unassigned task (`HumanTasks.DECISION`) and raises pt-0001 with `Resume.task`; approval assigns the task to the decider and completes it, anything else fails it |
| `GET /agent-approvals/{taskId}` | | The task's status and result |
| `POST /tool-calls` | `{ agentId, sessionId, tool, arguments }` | What the tool-call approval guardrail decides for the call: `deny` with "awaiting approval: &lt;eventId&gt;" until a person approves, then `allow` once; a standing decision on the call lets it pass at once |

## Trying a scenario

```
curl -X POST localhost:9164/payments -H 'content-type: application/json' \
  -d '{"supplier":"S-77","amount":4200,"currency":"EUR"}'
curl localhost:9164/payments/<id>                     # approvalEventId
curl -X POST localhost:9164/oversight/events/<eventId>/claim -H 'content-type: application/json' \
  -d '{"reviewer":{"subject":"tyler.jewell@akka.io","roles":["ai-system-owner"]}}'
curl -X POST localhost:9164/oversight/dev/clock -H 'content-type: application/json' -d '{"advance":"PT11S"}'
curl -X POST localhost:9164/oversight/events/<eventId>/decision -H 'content-type: application/json' \
  -d '{"reviewer":{"subject":"tyler.jewell@akka.io","roles":["ai-system-owner"]},"action":"APPROVE","rationale":"Invoice matches."}'
curl localhost:9164/payments/<id>                     # PAID
```

pt-0001 asks for 10 seconds of review, counted from the claim; the dev clock stands in for waiting.
Deadline timers run on real time, so after moving the clock past a deadline, `POST /oversight/dev/tick`
applies what is due.
