# Server

How an Akka service uses a pattern, and what the library does for it. Code below is a sketch of the
proposed API, not working code.

## Three ways a pattern is used

| Shape | Patterns | Built on |
|---|---|---|
| **Wait for a person** | 1 action approval, 2 decision review, 3 change authorization, 4 exception, 5 match, 6 reconciliation, 11 hand-off | A workflow step raises the event, then `thenPause`. An autonomous agent's approval is a task assigned to no agent, which a person completes or fails |
| **Raise and carry on** | 9 flag, 10 triage, 13 attestation, 14 oversight quality | Any component raises the event; the decision runs an apply handler; nothing resumes |
| **A person starts it** | 7 contest, 8 override, 12 halt | An endpoint or the console opens the event. Halt and restart use the workflow `suspend` and `resume` the SDK already has |

## A workflow

```java
private StepEffect askToRelease() {
  human.raise(ActionApproval.ask("Release the claims context to Northwind?")
      .control("cl0003")
      .subject(Subject.action("release", currentState().release()))
      .resumes(ReleaseWorkflow.class, workflowId));
  return stepEffects().thenPause(ActionApproval.backstop(ReleaseWorkflow::noAnswer));
}

public Effect<Done> decided(Decision d) {   // called once, with the final outcome
  return d.approved()
      ? effects().transitionTo(ReleaseWorkflow::publish).thenReply(done())
      : effects().end().thenReply(done());
}
```

The engine runs everything up to the decision: deadline, at-risk warning, escalation, claim, votes,
minimum review time, qualification. The workflow's pause timeout is only a backstop, set past the
last escalation, so the workflow hears exactly one outcome.

## An autonomous agent

The SDK already has a human step for autonomous agents: a task that no agent is assigned to, which
blocks the tasks that depend on it until it is completed or failed (the publishing sample's
approval gate). The library creates that task and an event that points at it. On approve it
assigns the task to the decider and completes it with the decision; on deny it fails the task, and
the SDK cancels its dependents.

A tool call that waits for approval inside an agent's tool loop is not possible today: a
`ToolCallGuardrail` can only allow, deny or fail. The stand-in fails the call with "awaiting
approval" and the calling workflow retries it after the decision ([Repository](05-repository.md)).

## Raised without waiting

```java
human.raise(Triage.ask("A watcher found 40 links whose sources now disagree")
    .control("cl0046")
    .subject(Subject.behaviour(metric, window, value, baseline)));
```

The decision runs the service's apply handler for that subject kind (for example, accepting a flag
writes an evaluation case) and the handler reports the decision applied.

## What the engine owns

- **Lifecycle:** open, claimed, in review, decided or awaiting another vote, applied, closed, plus
  escalated, sent back, timed out, abandoned, withdrawn and reopened. A decision is a record, not an
  action: the subject's owner applies it and reports that it did.
- **Settings:** a control's `human_config`, then its pattern's defaults, then the shared defaults
  ([Specification](02-spec.md)).
- **Routing:** roles, qualification against the overseer registry, independence, the "routable to
  me" queue.
- **Clocks:** deadlines and escalation driven from the event journal, so a missed deadline acts on
  its own.
- **Verdicts and quality figures:** computed once, on the server, so consoles stop computing them.
- **Proof:** `HumanOversightDecisionRecordedV2`, `HaltExercised`, `HaltLifted`.
- **The `/oversight/*` endpoint:** the contract every console reads.

## What the service supplies

These are plug-ins, wired in the service's own setup class. The SDK ignores a setup class inside a
library.

| Plug-in | Does |
|---|---|
| Control catalog | Reads `human_config` from the service's signed bundle |
| Apply handler, per subject kind | Carries out a decision and reports it applied |
| Identity and qualification | Maps the caller to an overseer; defaults to the overseer registry file |
| Access control | Decides who may read and decide over `/oversight`, for services with their own authorization |
| Halt scope | Checks the safe state before a restart, for services with halts |

## How a service loads it

The SDK loads components from a library jar. The library is built with the Akka parent pom and
`non-akka-service` set to true; the service lists the components it uses in `application.conf`:

```hocon
akka.javasdk.library.components {
  event-sourced-entity = ["akka.human.akka.HumanEventEntity"]
  view                 = ["akka.human.akka.HumanQueueView"]
  consumer             = ["akka.human.akka.HumanDeadlines"]
  timed-action         = ["akka.human.akka.HumanClock"]
  http-endpoint        = ["akka.human.akka.OversightEndpoint"]
}
```

Not yet known: whether a service can change the `@JWT` and `@Acl` settings of an endpoint that
comes from a library. The first phase finds out; if it cannot, that becomes a request to the SDK
team.
