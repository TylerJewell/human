# Pinned from Verify

Copied from Verify at commit `974e057b14e17c25b083c57f00efedb11d714691`
(schema v2.10, verdict schema v1). Do not edit here; change Verify and copy again.

| File | Source |
|---|---|
| `human-patterns.yaml` | `framework/schema/registries/human-patterns.yaml` |
| `human-decisions.yaml` | `framework/schema/registries/human-decisions.yaml` |
| `overseer-registry.schema.yaml` | `framework/schema/overseer-registry.schema.yaml` |
| `human-config.schema.json` | `human_config`, `halt_config`, `duration` and `humanAction` from `framework/schema/control.schema.yaml`, as JSON |
| `verdict.schema.yaml` | `framework/schema/verdict.schema.yaml` |

`Verdict` in `../oversight.schema.json` is `verdict.schema.yaml` as JSON, with each of its `$defs`
renamed `Verdict` followed by the name in Pascal case (`human-detail` is `VerdictHumanDetail`).
`spec/check.mjs` checks every verdict in the examples against both.
