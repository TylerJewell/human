# Console

What a person sees when a pattern raises an event, and where they see it.

## One page, two parts that vary

Every event gets the same page. The pattern fills two parts: the subject view and the decision
panel.

```
┌ pattern · control · service/agent · state ──────────────────── deadline ring ┐
│ The question, in one plain sentence                                          │
├ SUBJECT (by subject kind) ───────────────────────┬ DECISION PANEL (pattern) ──┤
│ payload / diff / two records / appeal …          │ only the allowed decisions │
│ why it was raised · evidence · blast radius      │ reason box if required     │
│                                                  │ 1 of 2 votes · min review  │
├ HISTORY  raised → claimed → decided → applied (the proof) ───────────────────┤
```

The same everywhere: header, question, deadline ring, claim, history, confirmation of every write,
proof. These exist today in the local console's Human page
(`DecisionPanel`, `Journey`, the
queue).

Chosen by the pattern, from the registry: which decisions the panel offers and their labels,
whether a reason is required, the votes needed, the minimum review time.

## Subject views

The console already shows five subject kinds (`SubjectView.tsx`): `action`, `behaviour`, `change`,
`system`, `output`. They cover nine patterns. Five new views cover the rest:

| View | Pattern | Shows |
|---|---|---|
| `records` | 5 match | The two records side by side, the fields that agree and differ |
| `appeal` | 7 contest | The original decision, the person's grounds, who may not decide |
| `exception` | 4 waiver | The rule set aside, its scope, its expiry |
| `conversation` | 11 hand-off | The transcript so far, and the take-over control |
| `attestation` | 13 | The evidence and how old it is |

A console that does not know a pattern or a subject kind shows the summary and the raw detail, as
`SubjectView` does today when a detail fails its schema. A new pattern never breaks an older console.

## Where a decision is made

One event, several places to decide it. The service owns the event and serves it at `/oversight/*`.
Which place a person uses depends on who they are, not on the pattern.

| Where | Who | What |
|---|---|---|
| The Akka console's Human section | People who govern the system | Change authorization, halts, oversight quality, attestation, standing decisions, and the queue across every service. It sees every event, so it is also the fallback when a domain queue misses its deadline |
| The service's own UI | People who work in the domain and never open the Akka console | A data steward deciding a match, a case reviewer, the person on duty taking a hand-off. They decide next to the record, with the same components in the service's theme |
| Slack, email | Anyone routed to the event | A link to one of the two. One-click approve only for patterns with no minimum review time and no second vote |

All of them read the same event. A claim in one locks the others, and the proof is the same
whichever was used.

## contextlayer

- Its Decisions view is the queue, filtered to its own service.
- The Links and Cases views show the `records` view and the decision panel next to the record.
- Serving `/oversight/*` where the local console's companion looks (its console plan, item S12) puts
  the same events in the Akka console's Human section.

## Components

| Part | For |
|---|---|
| React components | The Akka console, contextlayer's console, any React console |
| Headless hooks over the contract | A React console with its own look |
| Web components (decision panel, queue) | Consoles that are not React |

Themed by CSS tokens, so they look native in the Akka console and take a customer's brand. They
must not depend on the console's own components, which exist only as source inside its workspace.
