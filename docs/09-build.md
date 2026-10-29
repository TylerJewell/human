# Build

How the phases in [Plan](07-plan.md) are built in this repository, standalone. Everything needed to
run the patterns end to end lives here: the library, the stand-ins for missing SDK and runtime
behaviour, a companion that plays the akka CLI's part, consoles, and sample services that play
contextlayer's and loan-origination's parts.

## Rules

- **Isolated.** Nothing outside this repository changes. `ai-console`, `contextlayer-akka`,
  `explainability` and the akka CLI are read for reference only. Where this library needs a change
  in one of them, the change is built here instead, as a stand-in or a sample.
- **The contract is fixed.** [`spec/CONTRACT.md`](../spec/CONTRACT.md) and
  [`spec/oversight.schema.json`](../spec/oversight.schema.json) define every route and type. Code
  follows them; a needed change to the contract is made there first, with its example, and
  `pnpm spec:check` passes.
- **Settings come from Verify.** [`spec/verify/`](../spec/verify) is pinned at `641097fa`. Never edit
  it.
- **No commits.** The tree stays uncommitted until the owner asks.
- **Akka runtime.** Integration tests run in-process with the TestKit (`mvn verify`); they need no
  daemon. To run a service, call `akka_local_status` first and `akka_local_start` only if it is not
  started; use `akka_local_run_service`. Never call `akka_local_stop`: other people's services run
  under the same daemon.
- **Comments describe the code**, for a reader with no memory of how it got here. No history, no
  "now", no "fixed".

## Ports

| Port | What |
|---|---|
| 9164 | `samples/patterns`: one control per pattern |
| 9165 | `samples/contextlayer`: contextlayer's human controls on the library |
| 9166 | `samples/loan-origination`: the loan scenarios on the library |
| 9167 | `cli` `human-companion`: the merged API and the built console |
| 5181 | `console/apps/playground` dev server |
| 5182 | `console/apps/console` dev server |
| 5183 | `samples/contextlayer/ui` dev server |
| 5184 | `samples/loan-origination/ui` dev server |

## Modules and owners

| Module | Phase | Holds |
|---|---|---|
| `spec/` | 1 | Contract, schema, examples, pinned Verify files, fixtures (`overseers.yaml`, `controls-patterns.yaml`) |
| `java/parent`, `java/human-api` | 1 | Build settings; the public API and plug-ins (no Akka types) |
| `java/human-engine` | 2, 5 | Plain Java: settings resolution, the event state machine for all fourteen patterns, routing, verdicts, quality, standing decisions, the JSON views the contract defines |
| `java/human-akka` | 2, 5 | Component library: event entity, queue view, deadline consumer and timed action, halt entity, standing decisions, `/oversight/*` endpoint, `HumanClient`, workflow and task bridges, registration |
| `java/human-runtime-local` | 2, 4 | Stand-ins: proof ledger entity and NDJSON export (runtime 10), suspend/resume with the person recorded (runtime 11), halt guardrails within one service (runtime 14), the tool-call approval guardrail (SDK 13) |
| `java/human-testkit` | 2 | A movable clock and helpers to raise, claim and decide in a service's tests |
| `java/human-conformance` | 2, 5 | Scenarios per pattern over HTTP against any `/oversight`, using the dev routes; a runnable jar with a JSON report |
| `samples/patterns` | 2, 5 | A service with the fourteen controls of `spec/fixtures/controls-patterns.yaml`, each raised the way its pattern is used |
| `console/packages/human-kit` | 3, 5 | React components, the ten subject views, the decision panel, headless hooks, CSS tokens |
| `console/packages/human-elements` | 3 | Web components for the decision panel and the queue |
| `console/apps/playground` | 3 | Drives the components against a running service |
| `cli/` | 6 | `human-companion`: the akka CLI companion's human routes, standalone |
| `console/apps/console` | 6 | The Akka console's Human section, standalone, on `human-kit` |
| `samples/contextlayer` | 4 | contextlayer's human controls and its Decisions, Links and Cases screens, on the library |
| `samples/loan-origination` | 5 | The loan scenarios S1 to S7 and its domain reviews, on the library |

## Decisions the code follows

- **Rehearsal takes effect.** A decision stamped `rehearsal: true` changes the event like any other;
  its verdict's source is `rehearsal`, so it never counts as evidence.
- **Halts.** One authorised person exercises a halt: `POST /oversight/halts` with `lift: false`
  raises a `halt-restart` event and records the requester's `HALT` at once, appending
  `HaltExercised`. A restart (`lift: true`) is a `halt-restart` event under the control's review
  mode (two people by default) and can be approved only when every safe-state check passes;
  approval appends `HaltLifted`.
- **Standing decisions.** `APPROVE_FOR_PATTERN` and `DENY_FOR_PATTERN` create a standing decision on
  the event's `matchKey`, for 30 days unless revoked. A raise whose control and `matchKey` match an
  active one is settled at once by it, recorded with actor `standing:<id>`.
- **Defaults.** Verify's shared defaults: at-risk at 0.75 of the deadline, split vote `DENY`, late
  decisions accepted and breached, minimum review enforced, claims last 30 minutes, the clock runs
  while sent back, qualification through the overseer registry, the accountable owner answers
  oversight quality.
- **Time.** The engine never reads a clock; the time is passed in. Components read `HumanClock`,
  which the dev routes can move.
- **Registration.** A service writes `$AKKA_HUMAN_HOME/services/<service>.json` (default
  `~/.akka/human`) on startup; the companion reads the directory.

## Done when

| Phase | Check |
|---|---|
| 1 | `pnpm spec:check` passes; `mvn -f java/human-api/pom.xml verify` passes |
| 2 | `mvn verify` passes for the Java modules; the conformance suite passes against `samples/patterns` for action approval, change authorization and halt and restart |
| 3 | `pnpm -r test` passes; the playground decides events in the running patterns service |
| 4 | `samples/contextlayer` names a pattern on every human control and has no hand-written human-event code; its screens decide events through `human-kit` |
| 5 | The conformance suite passes all fourteen patterns against `samples/patterns`, `samples/contextlayer` and `samples/loan-origination` |
| 6 | The companion merges the three services; the console and contextlayer's screens show the same event the same way; a decision in one shows in the other |
