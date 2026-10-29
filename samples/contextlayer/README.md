# contextlayer

contextlayer's human controls on the library: the seventeen human rows of its signed bundle (revision 59),
written as bundle v3 in [`governance/controls.yaml`](src/main/resources/governance/controls.yaml) with a
Verify v2.8 `human_config` naming each row's pattern. The service is an isolated port of the
human-touching slice of `contextlayer-repo/service`: small entities and workflows stand in for the graph,
the connectors and the models, so each control is raised from the flow that raises it there. It runs on
port **9165** with the development routes on, registers itself for the companion, and serves the oversight
contract at `/oversight/*`.

No hand-written human-event code remains: decisions are made through `/oversight`, the library delivers
each outcome once, and an apply handler per domain change carries it out (what `HumanEventApplier` and
`GateDecisions` did). Identity reviews are raises of match-adjudication; unknown outcomes are raises of
outcome-reconciliation.

## Run

```
mvn compile exec:java          # or akka_local_run_service on this directory
curl localhost:9165/oversight/info
```

`mvn verify` runs the TestKit integration tests in-process.

## Controls and patterns

| Row | Name | Flavor | Pattern | Subject | Who decides | Notes |
|---|---|---|---|---|---|---|
| custom-cl0003 | Risky Action Approval | system authorization | action-approval | action | approver, then admin | Not the requester; 24 h, then denied; 10 s review recorded, not enforced |
| custom-cl0004 | Link Review | domain decision | match-adjudication | records | reviewer, then admin | Neither person who loaded the two records; 7 days; 5 s review recorded |
| custom-cl0005 | Release Approval | system authorization | change-authorization | change | approver, then admin | The owner may approve their own release |
| custom-cl0040 | Stage Change Approval | system authorization | change-authorization | change | approver, then admin | SHADOW needs a release, LIVE an approved one |
| custom-cl0006 | Four-Eyes Connector Activation | system authorization | change-authorization | change | approver, then admin | Not the person who imported the version |
| custom-cl0041 | Connector Deactivation | system authorization | change-authorization | change | approver, then admin | |
| custom-cl0011 | Emergency Halt | halt | halt-restart | system | one approver | Allows only HALT; halts a playbook, or every playbook (`playbooks`) |
| custom-cl0012 | Two-Person Restart | halt | halt-restart | system | two approvers | Allows APPROVE and DENY; restarts what cl0011 halted |
| custom-cl0014 | Domain Join Approval | system authorization | change-authorization | change | approver, then admin | Not whoever last changed either domain |
| custom-cl0042 | Domain Administration | system authorization | change-authorization | change | admin | Set or remove a domain, revoke a join rule |
| custom-cl0043 | Person Runs the Question | domain decision | action-approval | action | operator, then admin | The drafted query runs only when a person runs it; no rationale needed |
| custom-cl0044 | Unknown Outcome Resolution | domain decision | outcome-reconciliation | action | reviewer, then admin | Not the requester; APPROVE = it took effect, DENY = it did not; never retried |
| custom-cl0045 | Failed Step Hand-Off | monitoring | triage | action | operator, then admin | The run does not wait; APPROVE acknowledges, ESCALATE reports |
| custom-cl0046 | Case Resolution | domain decision | triage | behaviour | reviewer, then admin | APPROVE resolves, DENY dismisses |
| custom-cl0047 | Control Waiver | system authorization | exception | exception | admin | End date at most 365 days away |
| custom-cl0048 | Control Acceptance | system authorization | change-authorization | change | approver, then admin | Holds while the catalog digest is unchanged |
| custom-cl0049 | Evidence Assertion | system authorization | change-authorization | change | admin | The admin may assert alone; each assertion is a new revision |

Every row keeps its flavor. Settings that differ from the rows before: cl0004 and cl0014 name two people
(`independence: [proposer, builder]`: the two loaders, the two domain changers) where the rows named
`contributors` or the last changer; cl0011 allows `HALT` only, the decision that exercises a halt.

The people are in [`governance/overseers.yaml`](src/main/resources/governance/overseers.yaml): three
approvers, two reviewers, two operators (one also a reviewer), two admins, and one suspended person.

## Routes

Each route that asks for a decision answers `{ eventId, state }`; the decision is made through
`/oversight/events/{eventId}/…` and the change is applied when it settles.

### Playbooks and actions

| Route | Body | Control |
|---|---|---|
| `POST /assistants` | `{ id?, name, by }` | |
| `GET /assistants`, `GET /assistants/{id}` | | Each playbook with its releases, stage and the halt that stops it |
| `POST /assistants/{id}/releases` | `{ version, by }` | cl0005 |
| `POST /assistants/{id}/stage` | `{ stage, by }` | cl0040 (422 when the stage needs a release it lacks) |
| `POST /assistants/{id}/halt` | `{ reviewer, reason }` | cl0011, decided at once by the approver |
| `POST /assistants/{id}/restart` | `{ reviewer, reason }` | cl0012 |
| `POST /assistants/{id}/actions` | `{ actionType, payload, risk, requestedBy }` | cl0003 for MEDIUM and HIGH; a payload with `timeout` leaves the outcome unknown (cl0044), one with `fail` fails |
| `GET /actions`, `GET /actions/{key}` | | The action ledger |
| `POST /assistants/{id}/runs` | `{ steps, failing, by }` | cl0045 for each step in `failing` |
| `GET /runs/{id}` | | |

### Links, connectors, domains, questions

| Route | Body | Control |
|---|---|---|
| `POST /identity/records` | `{ rule, key, entityType, source, ref, loadedBy, fields }` | cl0004 once the key's cluster holds two records; a pending review of other records is superseded |
| `GET /links`, `GET /links/{clusterId}` | | Both records side by side, what agrees and differs, the review |
| `POST /connectors/{pack}/versions` | `{ hash, by }` | |
| `POST /connectors/{pack}/activate` | `{ mode, by }` | cl0006 |
| `POST /connectors/{pack}/deactivate` | `{ by, reason }` | cl0041 |
| `GET /connectors`, `GET /connectors/{pack}` | | |
| `POST /domains/{name}` | `{ entityTypes, readerRoles, by, reason }` | cl0042 |
| `POST /domains/{name}/remove` | `{ by, reason }` | cl0042 |
| `POST /join-rules` | `{ a, b, by, reason }` | cl0014 |
| `POST /join-rules/revoke` | `{ a, b, by, reason }` | cl0042 |
| `GET /domains` | | |
| `POST /questions` | `{ text, by }` | cl0043 |
| `GET /questions/{id}` | | The draft, who ran it and its rows |

### Watchers and governance

| Route | Body | Control |
|---|---|---|
| `POST /watchers/{id}/readings` | `{ title, metric, value, threshold, comparison, headline }` | cl0046 when the value crosses; readings while the case is open add to it |
| `GET /cases`, `GET /cases/{id}` | | Cases with their headline fields |
| `POST /controls/{rowId}/waivers` | `{ by, reason, until }` | cl0047 |
| `POST /controls/{rowId}/acceptance` | `{ by, reason }` | cl0048 |
| `POST /controls/{rowId}/assertions` | `{ by, records, documents, reason }` | cl0049 |
| `GET /controls/{rowId}/claims` | | Whether a waiver is in force, whether the acceptance is current or stale, the assertions |

### Screens

`GET /` and any other path the routes above do not take serve
`src/main/resources/static-resources/`: a path naming a file serves it, any other serves `index.html`, so
the Decisions, Links and Cases screens built into that directory route themselves.

## Console

contextlayer's own console, at http://localhost:9165/, for the people who decide its events without
opening the Akka console: a data steward confirming a link, a reviewer closing a case. It is a Vite and
React app in [`ui/`](ui), drawn with `@akka-human/kit` in the look of contextlayer's console design
(dark, Instrument Sans and Roboto Mono, gold for what is current). Its screens use hash routes, since
`/links` and `/cases` are the service's own JSON routes.

- **Decisions** (`#/decisions`): the kit's queue of this service's events, waiting and answered. A decision
  opens on its own page: the decision clock (the deadline ring, the minimum-review ring, who may decide
  with whoever asked struck out), the control and who asked, what it would send or change, why it was
  raised, the kit's decision panel and the history.
- **Links** (`#/links`): links waiting for a review and those decided. A link shows its two records side by
  side from `GET /links`, what agrees and differs, who loaded each, and the decision panel of its
  `custom-cl0004` event (Same or Different, with a reason) beneath them.
- **Cases** (`#/cases`): cases by status with their headline fields; a case shows its evidence, what the
  watcher saw and the decision panel of its `custom-cl0046` event (resolve or dismiss).

The top bar's picker chooses who the console acts as, from `GET /oversight/registry`. Writes go to
`/oversight/*` on the same origin with that person stamped as a rehearsal (`identityProvider:
contextlayer-console`), so they take effect and never count as evidence.

The built files are kept in `src/main/resources/static-resources/`, so the Maven build needs no Node.
After changing the console, rebuild them and restart the service:

```
pnpm install                                           # once, at the repository root
pnpm --filter @akka-human/contextlayer-ui build        # writes static-resources/
pnpm --filter @akka-human/contextlayer-ui test         # the screens' own logic
pnpm --filter @akka-human/contextlayer-ui dev          # http://localhost:5183, calls forwarded to :9165
```

## Halts

A playbook is halted under its own id; `playbooks` halts every playbook. Actions and runs check both. The
halt route of the contract takes the same `target`
(`POST /oversight/halts { reviewer, control, lift, reason, target? }`), and a halt or restart named on
either control is raised under the one that allows it.
