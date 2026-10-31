# Changes elsewhere

What changes outside this repository. Paths are relative to each repository. Each change has a
standalone equivalent here (the companion in `cli/`, the console in `console/apps/console`,
contextlayer and loan-origination in `samples/`) that shows the change working.

## Verify

Done: schema v2.8, merged as `641097fa` ([Specification](02-spec.md)). It answered the Akka local
console's explainability requests: `human_config` per flavor, one decision vocabulary, a change as a
subject, what a human verdict concludes, halt values, shadow and strength, and an overseer registry.

Follow-up merged to Verify as `96ea36b6`; this repository pins it, and
the follow-up changed no registry in substance. What it covered:
- State in ADR-018, the `human_config` description and the pattern registry header that only human
  controls carry a pattern, and that a person reviewing a gate's or an evaluation's result is a
  second, human control. ADR-018 says tagging gates and evaluations "is not part of this change",
  which reads as if it could be later; under the rule it never is.
- Split the twelve human controls that also carry a gate, guardrail or eval config into two controls
  each, or drop the extra block.
- Add human controls where a source requires a person to decide at run time and only a gate covers
  it today.

Open: per-control action labels. Verify v2.8 has no per-control override of the labels a pattern
gives its decisions. A service that needed the labels Approve and Deny had to change its pattern to
get them. The schema needs a field on `human_config` that overrides the label of each decision the
pattern offers.

## Akka local console

Done:
- It uses version 2 of the oversight contract by reference ([`spec/CONTRACT.md`](../spec/CONTRACT.md),
  `spec/oversight.schema.json`) and no longer keeps its own copy.
- Its governance bundles name a pattern on each human control, with the Verify v2.8 `human_config`,
  and no longer write `flavor: custom-human`.
- Its companion has parity with the companion in `cli/`.
- Its loan-origination sample runs on the library.
- Its trace-ledger sample is a history-only ledger.
- Its build gate runs this library's conformance suite.

| Asset | Change still to make |
|---|---|
| The Human page of the local console | Replace the generic parts with `human-kit`. The Overseers, Standing, Halts and Quality tabs stay and use the components. Remove the loan sample's wording |
| The policies page's `HumanConfigForm` | Edit `pattern` and each pattern's settings |
| The cloud console | Later, and the platform team's: the production inbox in Control tower, on the same components |

## akka CLI companion

The companion in the akka CLI repository is Go code. Its parity with the companion in `cli/` is done
(see above). What remains belongs to the akka CLI project:
- Write the official `human_config` into signed bundles.
- Make the Akka plugin's spec templates offer a pattern, so `/akka:specify` and coding agents stop
  writing the engine by hand.

## contextlayer (first adopter)

- Delete the hand-written engine: `governance/HumanEvent*.java`, `application/HumanEventEntity`,
  `HumanEventTimedAction`, `HumanEventView`, `HumanEventNotifier`, `api/OversightEndpoint.java`,
  `api/HumanEventJson.java`.
- Keep the domain parts as plug-ins: `HumanEventApplier` becomes the apply handler; `IdentityReviews`
  and `UnknownOutcomes` become the handlers for match and outcome reconciliation.
- `governance/controls.yaml`: each of its seventeen human controls names a pattern; re-sign the
  bundle as v3.
- Console plan: the S12 screen comes from the library's endpoint; the Decisions, Links and Cases
  screens use `human-kit`.

## Requests to the SDK, runtime and platform teams

These are filed with each team. They change wording, not number.

| Request | Team | With this library |
|---|---|---|
| 12: request a human decision | SDK | Absorb `human-api`, which shows the shape working |
| 13: tool calls that wait for approval | SDK | Still needed; a library cannot wait inside the tool loop |
| Access control on `/oversight` | SDK | Let a service set `@Acl` or `@JWT` on the library's endpoint. Until then a service subclasses `OversightEndpoint` ([Build](09-build.md#packaging)) |
| 10: oversight records in the governance ledger | Runtime | Record the events the library emits |
| 11: a person on suspend, resume, terminate | Runtime | Needed so a halt's proof names who stopped it |
| 14: a scoped halt | Runtime | Needed for halts across services |
| 15: an API for pending decisions | Runtime | Standardize the `/oversight` path and its discovery |
| 16: windowed monitors | Runtime, Platform | Unchanged; they feed triage |
| 1–5: inbox, roles from the identity provider, notifications, halt, retention | Platform | The production companion, built on the contract and the components |
