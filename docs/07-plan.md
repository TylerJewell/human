# Plan

All six phases are built in this repository and run standalone ([Build](09-build.md)). Nothing
outside the repository was changed; contextlayer and loan-origination are rebuilt here as samples.

| Phase | Work | Done when | Status |
|---|---|---|---|
| 0. Specification | Verify v2.8: `human_config`, the pattern and decision registries, verdicts, halts, the overseer registry | Merged, `641097fa` | Done |
| 1. Scaffold | The modules in [Repository](05-repository.md); pin Verify; `oversight.schema.json` from the contract | Both builds run; the schema validates the contract's examples | Done: 27 examples and the fixtures validate |
| 2. Engine and components | The state machine for all fourteen patterns, journal-driven deadlines, the Akka component library, the stand-ins, the conformance suite | A sample service passes the suite for patterns 1, 3 and 12 | Done: `samples/patterns` passes all fourteen |
| 3. Console components | `human-kit`, `human-elements`, the playground | The playground decides events in the sample service | Done |
| 4. contextlayer | Its seventeen human controls on the library; Decisions, Links and Cases screens on `human-kit` | No hand-written human-event code; every human control names a pattern | Done in `samples/contextlayer` |
| 5. The other eleven patterns | Each with its view and conformance scenarios; loan-origination on the library | Fourteen patterns pass the suite; three services use the library | Done: three services pass with no failures |
| 6. The console and the CLI | The Akka console's Human section on `human-kit`; the companion | The console and contextlayer's screens show the same event the same way | Done: `console/apps/console` served by `cli/` |

trace-ledger was not rebuilt: its role as a second, hand-written `/oversight` implementation is
taken by the conformance suite, which any implementation can be run against.
