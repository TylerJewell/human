# akka/human

An internal library for the fourteen recurring patterns of human oversight in Akka services: a
person approves an action, reviews a decision before it takes effect, authorises a change, hears an
appeal, takes over a conversation, halts a system, and so on. It gives a service one engine for all
of them, one HTTP contract for every console, and console components that show any of them.

**Status: built standalone, internal, uncommitted.** All six phases of the [plan](docs/07-plan.md)
run here end to end: the library, stand-ins for the SDK and runtime behaviour it needs, a companion
that plays the akka CLI's part, the console, and three sample services (one control per pattern,
contextlayer's controls, loan-origination's scenarios), each passing the conformance suite.
[For discussion](docs/08-discussion.md) lists what needs a decision.

## Run it

Needs Java 21+, Maven, Node 22 with pnpm, Go, and the Akka local runtime.

| Step | Command |
|---|---|
| Build and test the Java modules | `mvn verify` |
| Build the consoles | `pnpm install` then `pnpm -r build` |
| Run the three services on the local runtime | In each of `samples/patterns` (9164), `samples/contextlayer` (9165), `samples/loan-origination` (9166): `mvn compile exec:java`, or the Akka plugin's `akka_local_run_service` |
| Run the companion and the console | `cd cli`, `go build -o bin/human-companion.exe ./cmd/human-companion`, then `bin/human-companion.exe`; open http://localhost:9167/ |
| Decide in a service's own screens | http://localhost:9165/ (contextlayer), http://localhost:9166/ (underwriting desk) |
| Check a service against the contract | `java -jar java/human-conformance/target/human-conformance.jar --url http://localhost:9164` |

Each service registers itself under `~/.akka/human/services`, where the companion finds it.

The specification is not in this repository. It is part of Akka Verify
(`akka/governance-explainability`, schema v2.8, merged as `641097fa`): `human_config` on every human
control, the pattern and decision registries, and the overseer registry. This repository pins a
Verify commit and reads those files.

## Reading order

| Document | What it covers |
|---|---|
| [Patterns](docs/01-patterns.md) | The fourteen patterns, the rule that only human controls carry one, and how the corpus maps onto them |
| [Specification](docs/02-spec.md) | What Verify v2.8 defines: `human_config`, the defaults, one decision vocabulary, verdicts, halts, the overseer registry |
| [Server](docs/03-server.md) | How a workflow, an autonomous agent or an endpoint uses a pattern, and what the engine does for it |
| [Console](docs/04-console.md) | The standard decision page, the subject views, and where a decision is made |
| [Repository](docs/05-repository.md) | Modules, where each could move later, and the stand-ins for missing SDK and runtime behaviour |
| [Changes elsewhere](docs/06-changes-elsewhere.md) | What changes in Verify, ai-console, the akka CLI and contextlayer, and the requests to the SDK, runtime and platform teams |
| [Plan](docs/07-plan.md) | Phases and what finishes each |
| [For discussion](docs/08-discussion.md) | Open questions and the choices made so far that need a second look |
| [Build](docs/09-build.md) | How the phases are built here standalone: rules, ports, module owners, decisions the code follows |
| [Contract](spec/CONTRACT.md) | The `/oversight/*` and companion routes and types every implementation serves and every console reads |
| [Study](docs/research/study.md) | The research behind the fourteen patterns: evidence, counts, and the disagreements between the three existing implementations |

## Decided so far

- The library is `akka/human`, internal for now, with the console components inside it.
- Modules sit where the SDK, runtime, CLI or console would take them, so absorbing one deletes a
  module instead of rewriting its callers.
- Missing SDK and runtime behaviour is built here behind the interface the real version would have.
- Patterns are defined in Verify, as data. A pattern applies only to a control whose mechanism is
  `human`. A gate or evaluation that involves a person stays a gate or evaluation; if a person must
  review its output, that review is a second, human control.
- contextlayer is the first service to use the library; here it is rebuilt as `samples/contextlayer`,
  and the real service is unchanged.
