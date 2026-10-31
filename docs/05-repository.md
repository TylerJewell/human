# Repository

Server code, console components and stand-ins for missing SDK and runtime behaviour live here
together. Each module sits where it would move if the SDK, the runtime, the CLI or the console took
it over, so absorbing a module deletes it here and its callers keep working.

## Modules

| Module | Holds | Would move to |
|---|---|---|
| `spec/` | The Verify registries at a pinned commit, and `oversight.schema.json` for the `/oversight/*` JSON (taken from the Akka local console's oversight contract) | Verify already; the contract to the Akka local console or the runtime docs |
| `java/human-api` | What a workflow, agent or endpoint calls: raising an event per pattern, `Decision`, `Subject` per kind, the workflow and autonomous-task bridges. Interfaces and records only | `akka-javasdk`, as an `akka.javasdk.human` package |
| `java/human-engine` | Plain Java: the lifecycle state machine with time passed in, settings resolution, routing and independence, verdicts, quality figures | The runtime |
| `java/human-akka` | A component library: the event entity, queue view, deadline consumer and timed action, halt entity, `/oversight/*` endpoint | Components the runtime provides |
| `java/human-runtime-local` | The stand-ins below, each behind a `human-api` interface | Deleted as the runtime ships each one |
| `java/human-testkit` | A fake clock, and helpers that raise and decide events in a service's tests | `akka-javasdk-testkit` |
| `java/human-conformance` | Scenarios per pattern, run over HTTP against any `/oversight` implementation | Stays |
| `console/packages/human-kit` | React components, subject views, headless hooks | The Akka console's packages |
| `console/packages/human-elements` | Web components for the decision panel and the queue | The Akka console's packages |
| `console/apps/playground` | A page that drives the components against a sample service | Stays |
| `samples/` | Small services, one per group of patterns, for the conformance suite and the playground | Stays |

Build: Maven with the Akka parent pom on SDK 3.7.0, Java 21; pnpm with the local console's
versions (React 19, Vite 7, TypeScript 5.8, Tailwind 4, Vitest 4, node 22).

## Stand-ins for SDK and runtime behaviour

Each names the request it replaces and sits behind the interface the real one would have.

| Request | Stand-in |
|---|---|
| SDK 12: request a human decision | `human-api` itself. A workflow step raises the event and pauses; the engine calls the workflow back with the final outcome. An agent's approval is a task assigned to no agent |
| SDK 13: tool calls that wait for approval | A tool wrapper that raises an action approval and fails the call with "awaiting approval"; the calling workflow retries after the decision. A true wait inside the tool loop needs the SDK |
| Runtime 10: oversight records in the governance ledger | A local ledger entity that appends `HumanOversightDecisionRecordedV2`, `HaltExercised` and `HaltLifted`, with an NDJSON export the console's evidence pages read |
| Runtime 11: a person recorded on suspend, resume, terminate | A wrapper around the workflow client's `suspend` and `resume` that records who called it |
| Runtime 14: a scoped halt | A halt registry the service checks before tool and model calls, within one service. A halt across services needs the runtime |
| Runtime 15: an API for pending decisions | `/oversight/*` at the path the console's companion reads, plus a registration file the companion can discover |
| Platform 2: reviewers' roles from the identity provider | The overseer registry file, read at startup |

## Not here

- **The akka CLI companion.** It already merges `/oversight` across services, and changes in
  the Akka local console ([Changes elsewhere](06-changes-elsewhere.md)).
- **The console's Human page.** It moves onto `human-kit` in the Akka local console.
- **Pattern definitions.** These stay in Verify.
