# Changes elsewhere

What changes outside this repository. Paths are relative to each repository. None of these changes
has been made: each one has a standalone equivalent here (the companion in `cli/`, the console in
`console/apps/console`, contextlayer and loan-origination in `samples/`) that shows the change
working before it is made in the real repository.

## Verify (`akka/governance-explainability`)

Done: schema v2.8, merged as `641097fa` ([Specification](02-spec.md)). It answered the local
console's explainability asks 3 to 9.

Follow-up merged as #33 and #34 (`96ea36b6`); this repository pins it, and
the follow-up changed no registry in substance. What it covered:
- State in ADR-018, the `human_config` description and the pattern registry header that only human
  controls carry a pattern, and that a person reviewing a gate's or an evaluation's result is a
  second, human control. ADR-018 says tagging gates and evaluations "is not part of this change",
  which reads as if it could be later; under the rule it never is.
- Split the twelve human controls that also carry a gate, guardrail or eval config into two controls
  each, or drop the extra block.
- Add human controls where a source requires a person to decide at run time and only a gate covers
  it today.

## ai-console

| Asset | Change |
|---|---|
| `docs/handoff/contracts/human.md` | Add `pattern` and the five new subject kinds; replace the draft `human_config` with Verify v2.8; point to `spec/oversight.schema.json` here |
| `kalix-console/apps/local-console/src/features/human/` | Replace the generic parts with `human-kit`. The Overseers, Standing, Halts and Quality tabs stay and use the components. Remove the loan sample's wording |
| `kalix-console/apps/local-console/src/features/policies/HumanConfigForm.tsx` | Edit `pattern` and each pattern's settings; stop writing `flavor: custom-human` |
| `samples/loan-origination` | Replace about twenty hand-written `Human*` and `Oversight*` classes with the library. Its plain-pause domain reviews become `decision-review` events |
| `samples/trace-ledger` | The same. Its simulated ledger stays as a second `/oversight` implementation that must pass the conformance suite |
| `docs/handoff/rd-inputs.md`, `docs/asks/*.md` | Mark explainability asks 3 to 9 answered; update the SDK, runtime and platform requests below |
| `kalix-console/apps/cloud-console` | Later, and the platform team's: the production inbox in Control tower, on the same components |

## akka CLI companion (`ai-console/akka-cli`, through a PR to the CLI)

`internal/companion/human/` is twelve files of Go, about 4,700 lines.
- Pass `pattern` and the new subject kinds through; check them against `oversight.schema.json`.
- Read quality figures from services instead of computing them in `quality.go`.
- Keep what only the companion can do: merging events across services, "routable to me", acting as
  a chosen reviewer.
- Run the conformance suite against its merge layer.
- CLI ask 8: write the official `human_config` into signed bundles.
- The Akka plugin's spec templates offer a pattern, so `/akka:specify` and coding agents stop
  writing the engine by hand.

## contextlayer (first adopter)

- Delete the hand-written engine: `governance/HumanEvent*.java`, `application/HumanEventEntity`,
  `HumanEventTimedAction`, `HumanEventView`, `HumanEventNotifier`, `api/OversightEndpoint.java`,
  `api/HumanEventJson.java`.
- Keep the domain parts as plug-ins: `HumanEventApplier` becomes the apply handler; `IdentityReviews`
  and `UnknownOutcomes` become the handlers for match and outcome reconciliation.
- `governance/controls.yaml`: each of its seventeen human controls names a pattern; re-sign the
  bundle as v3.
- Console plan (`service/docs/CONSOLE-PLAN.md`): S12 comes from the library's endpoint; the
  Decisions, Links and Cases screens use `human-kit`.

## Requests to the SDK, runtime and platform teams

These are already filed in ai-console `docs/handoff/rd-inputs.md`. They change wording, not number.

| Request | Team | With this library |
|---|---|---|
| 12: request a human decision | SDK | Absorb `human-api`, which shows the shape working |
| 13: tool calls that wait for approval | SDK | Still needed; a library cannot wait inside the tool loop |
| 10: oversight records in the governance ledger | Runtime | Record the events the library emits |
| 11: a person on suspend, resume, terminate | Runtime | Needed so a halt's proof names who stopped it |
| 14: a scoped halt | Runtime | Needed for halts across services |
| 15: an API for pending decisions | Runtime | Standardize the `/oversight` path and its discovery |
| 16: windowed monitors | Runtime, Platform | Unchanged; they feed triage |
| 1–5: inbox, roles from the identity provider, notifications, halt, retention | Platform | The production companion, built on the contract and the components |
