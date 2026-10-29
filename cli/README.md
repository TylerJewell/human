# human-companion

Plays the akka CLI companion's part for human oversight, standalone. It finds the local services
that serve `/oversight/*`, merges their events into one API for consoles, stamps the local reviewer
on every write, and forwards each write to the service that owns it. The routes are
[`spec/CONTRACT.md`](../spec/CONTRACT.md), "Companion routes".

## Run

```sh
cd cli
go run ./cmd/human-companion            # http://localhost:9167
# or
go build -o human-companion ./cmd/human-companion && ./human-companion
```

| Flag | Default | |
|---|---|---|
| `--port` | `9167` | Served on 127.0.0.1 and ::1 |
| `--home` | `$AKKA_HUMAN_HOME`, else `~/.akka/human` | Services register in `services/*.json`; the reviewer is kept in `reviewer.json` |
| `--registry` | none | An overseer registry in Verify's YAML format. Without it, the first running service's `GET /oversight/registry`, else `spec/fixtures/overseers.yaml` |
| `--console-dir` | `console/apps/console/dist` when built | Served at `/`; a path that is not a file answers `index.html` |
| `--conformance-jar` | `java/human-conformance/target/human-conformance.jar` | Run by `POST /api/companion/human/conformance` |

Defaults inside the repository are found from the executable's directory, then the working
directory, upward. CORS allows the console dev servers on `http://localhost:5181` and
`http://localhost:5182`.

## Discovery

Each service writes `<home>/services/<service>.json`
(`{service, url, oversight, contract, startedAt}`) when it starts. On a request the companion lists
the directory and probes `GET {url}{oversight}/info` with a one-second timeout; a service that
answers is up. One discovery is reused for two seconds. `GET /api/companion/human/services`
answers `{ "services": [{ service, url, up, info }] }`, down services included.

## What the merged routes do

- **Events.** The query is passed to every running service, except `routable`, `service`, `cursor`,
  `limit` and `offset`, which the companion acts on: `routable=me` becomes
  `roles=<reviewer roles>&reviewer=<subject>`, and `service=` asks one service. Each page asks
  every service for `limit` events from that service's offset, merges them in the contract's order
  (pending first: overdue, then least time left, then oldest; then resolved, `closedAt` newest
  first), keeps `limit`, and answers `next`, an opaque cursor holding each service's offset. Pass
  it back as `cursor`. `total` is the services' totals added. Rows are `HumanEventRow`s derived from
  the services' `HumanEvent`s, with `owner.service` the registered service.
- **Writes.** Every body gets the reviewer
  `{subject, name, roles, identityProvider: "human-companion", rehearsal: true}`, replacing any the
  body names. Event writes go to the service that answers `GET /oversight/events/{id}`; halts,
  contests, overrides and flags go to the service whose `/oversight/controls` lists the body's
  `control`; a flag without a control goes to the only running service, or the one with an event
  on the flag's `flowId` or `sessionId`; a revocation goes to the service whose `/oversight/standing`
  lists the id. The owner's status and body are answered unchanged.
- **Summary.** Figures and per-state and per-pattern counts added, per-day arrays added day by day
  (aligned on the newest day), halts concatenated.
- **Quality.** Reviewers merged by subject: counts added, `approvalRate`, `overrideRate`,
  `agreementWithAi` and `medianReviewSeconds` averaged over each service's decisions (the median
  cannot be recomputed, so its weighted mean stands in), the oldest claim kept, and `share`
  recomputed over all decisions. Weekly rows merged by week (`raised` added, rates averaged over
  what was raised). Outcomes, coverage and agreement added; review times, heatmap rows and signals
  concatenated, each tagged with `service`.
- **Verdicts, standing, halts, controls.** Concatenated, each item tagged with `service` when it
  does not name one; verdicts newest first, cut to `limit` when one is asked.
- **Errors.** A service's client error to a read (other than 404) is the query's fault and is
  answered unchanged; a service that fails otherwise is left out of the merge.

## Reviewer

`GET /api/companion/human/reviewer` answers the reviewer kept in `<home>/reviewer.json`, or, until
one is, the registry's first qualified overseer acting in all of their roles.
`PUT` with `{subject, roles}` keeps a person of the registry acting in roles they hold.

## Test

```sh
cd cli
go test ./...
go vet ./...
```

The tests run the companion against two fake services built from `spec/examples`.
