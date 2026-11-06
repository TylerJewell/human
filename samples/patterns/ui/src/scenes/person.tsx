import { actionLabel, formatMoment, OutcomeChip, useEvent, useOversight, useRegistry, type ControlSettings, type HumanEvent } from "@akka-human/kit";
import { useEffect, useState, type ReactNode } from "react";
import { nameIn, useActAs, useMe } from "../parts/people";
import { Button, ErrorText, Eyebrow, Frame, Icon, Mono, Note, Pill, Rows, useAction, type Tone } from "../parts/ui";
import { AppliedLine, NextBar, RaisedBar, Waiting } from "../steps/common";
import { call, newRun, useDomain } from "../tour/api";
import { useRecord } from "./cases";
import type { Scene, SceneProps } from "./types";

// The patterns a person starts: contest and appeal, override, and flag and
// feedback. The person files through the sample's route or the contract, and
// the library raises the event on their behalf.

// ---- contest and appeal

interface ResolvedPage {
  events: HumanEvent[];
}

const GROUNDS =
  "The income on my claim, 3,900 EUR, included a one-off bonus of 800 EUR paid in March. My regular income is 3,100 EUR a month, which is under the limit.";

/** The most recent decided notice: the decision an applicant can appeal. */
function useDecidedNotice() {
  return useDomain<ResolvedPage>("/oversight/events?control=pt-00002&state=resolved&limit=10", 4000).data?.events.find(
    (e) => e.outcome?.action && e.state !== "KILLED"
  );
}

/** What the reviewed notice became, by the reviewer's action. */
const NOTICE_WORDS: Record<string, string> = { APPROVE: "Sent as drafted", EDIT: "Amended and sent", DENY: "Withheld" };

function AppealFrame({ notice, status, children }: { notice?: HumanEvent; status: ReactNode; children: ReactNode }) {
  const registry = useRegistry().data;
  return (
    <Frame role="Mara Lindqvist's account" icon="user" title="Claim HB-30117" status={status} testId="frame-account">
      {notice ? (
        <Rows
          rows={[
            ["Decision", "Housing benefit declined"],
            ["Notice", `${NOTICE_WORDS[notice.outcome!.action!] ?? actionLabel(notice.outcome!.action!, notice.actionLabels)} after review by ${nameIn(registry, notice.decisions.at(-1)?.reviewer.subject)}, ${formatMoment(notice.closedAt ?? notice.history.at(-1)!.at)}`],
            ["Reviewed under", <Mono>{notice.id}</Mono>]
          ]}
        />
      ) : null}
      {children}
    </Frame>
  );
}

function ContestTrigger({ run, setRun, event, go }: SceneProps) {
  const notice = useDecidedNotice();
  const [grounds, setGrounds] = useState(GROUNDS);
  const [start, busy, error] = useAction();
  if (!notice && !run) {
    return (
      <div className="pt-stack">
        <Frame role="Mara Lindqvist's account" icon="user" title="Claim HB-30117" status={<Pill>No decision yet</Pill>}>
          <p className="pt-lead">An appeal starts from a decision that was already made. Mara has not received one yet.</p>
          <Note>Run the decision review first: its notice is the decision Mara appeals, and whoever reviewed it cannot hear her appeal.</Note>
        </Frame>
        <div className="pt-next">
          <Button variant="secondary" icon="arrowR" onClick={() => (window.location.hash = "/p/decision-review/trigger")}>
            Open the decision review
          </Button>
        </div>
      </div>
    );
  }
  const contested = (run?.data.notice as string | undefined) ?? notice?.id;
  const fileIt = () =>
    start(async () => {
      const raised = await call<{ eventId: string }>("POST", `/decisions/${encodeURIComponent(notice!.id)}/contest`, {
        appellant: "mara.lindqvist@example.com",
        grounds
      });
      setRun(newRun({ eventId: raised.eventId, events: [raised.eventId], data: { notice: notice!.id, grounds } }));
    });
  return (
    <div className="pt-stack">
      <AppealFrame notice={notice} status={run ? <Pill tone="wait" pulse>Appeal filed</Pill> : <Pill>Decided</Pill>}>
        <label className="pt-field">
          <span>Why the decision is wrong</span>
          <textarea value={(run?.data.grounds as string | undefined) ?? grounds} onChange={(e) => setGrounds(e.target.value)} rows={3} disabled={!!run} />
        </label>
      </AppealFrame>
      <div className="pt-start">
        <div className="pt-start__row">
          <Button icon={run ? "reset" : "play"} variant={run ? "secondary" : "primary"} onClick={fileIt} busy={busy} disabled={!notice} testId="run">
            {run ? "Appeal again" : "File the appeal"}
          </Button>
          <span className="pt-start__call">
            The tour calls <Mono>POST /decisions/{contested ?? "{ref}"}/contest</Mono> on the patterns service.
          </span>
        </div>
        <ErrorText>{error}</ErrorText>
      </div>
      <RaisedBar event={event} go={go} />
    </div>
  );
}

const APPEAL_WORDS: Record<string, [Tone, string]> = {
  upheld: ["bad", "Decision stands"],
  modified: ["gold", "Decision modified"],
  overturned: ["ok", "Decision overturned"]
};

function ContestEffect({ run, event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  const notice = useEvent(run?.data.notice as string | undefined).data;
  const outcome = record?.outcome ?? "";
  const [tone, word] = APPEAL_WORDS[outcome] ?? ["wait" as Tone, "Appeal filed"];
  return (
    <div className="pt-stack">
      <AppealFrame notice={notice} status={<Pill tone={tone}>{word}</Pill>}>
        {record ? (
          <div className="pt-letter">
            <p>Dear Mara Lindqvist,</p>
            <p>
              An appeals officer who was not involved in the first decision has reviewed your appeal. {outcome === "overturned" ? "The decision is overturned, and your claim is granted." : outcome === "modified" ? "The decision is modified." : "The decision stands."}
            </p>
            {record.fields.rationale ? <p>The reason: {record.fields.rationale}</p> : null}
          </div>
        ) : (
          <Waiting>Mara's appeal is with an appeals officer.</Waiting>
        )}
      </AppealFrame>
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const contestAppeal: Scene = { Trigger: ContestTrigger, Effect: ContestEffect };

// ---- override

interface Assessment {
  applicant: string;
  eligible: boolean;
  referred: boolean;
  assessedAt: string;
}

/** An override as it reads on the output it changed. */
const OVERRIDDEN: Record<string, string> = { REVERSE: "Reversed", OVERRIDE: "Replaced", DISREGARD: "Disregarded" };

function AssessmentFrame({ assessment, override }: { assessment?: Assessment; override?: { action: string; by: string; rationale: string; replacement?: string } }) {
  const status = override ? (
    <Pill tone="gold">Overridden</Pill>
  ) : assessment ? (
    <Pill tone={assessment.eligible ? "ok" : "bad"}>{assessment.eligible ? "Eligible" : "Not eligible"}</Pill>
  ) : (
    <Pill>Not assessed</Pill>
  );
  return (
    <Frame role="The eligibility service" icon="agent" title="Assessment for Ana Ruiz" status={status} testId="frame-assessment">
      <Rows
        rows={[
          ["Applicant", "Ana Ruiz"],
          ["Declared income", "32,000 EUR this year"],
          [
            "Result",
            assessment ? (
              <span className={override ? "pt-struck" : undefined}>{assessment.eligible ? "Eligible" : "Not eligible: income at or above 30,000 EUR"}</span>
            ) : (
              "—"
            )
          ],
          ...(override
            ? ([
                ["Overridden", `${override.action} by ${override.by}`],
                ["Result now", override.replacement ?? (override.action === "Reversed" ? "Eligible" : "No result: the output is set aside")],
                ["Reason", override.rationale]
              ] as [string, ReactNode][])
            : []),
          ["Assessed", assessment ? `automatically, ${formatMoment(assessment.assessedAt)}` : "—"]
        ]}
      />
    </Frame>
  );
}

function OverrideTrigger({ run, setRun, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const assessment = run?.data.assessment as Assessment | undefined;
  const runIt = () =>
    start(async () => {
      const a = await call<Assessment>("POST", "/eligibility/assess", { applicant: "Ana Ruiz", income: 32000 });
      setRun(newRun({ entity: `assessment-ana-ruiz-${Date.now().toString(36)}`, data: { assessment: a } }));
    });
  return (
    <div className="pt-stack">
      <AssessmentFrame assessment={assessment} />
      <div className="pt-start">
        <div className="pt-start__row">
          <Button icon={run ? "reset" : "play"} variant={run ? "secondary" : "primary"} onClick={runIt} busy={busy} testId="run">
            {run ? "Assess again" : "Assess Ana Ruiz"}
          </Button>
          <span className="pt-start__call">
            The tour calls <Mono>POST /eligibility/assess</Mono> on the patterns service.
          </span>
        </div>
        <ErrorText>{error}</ErrorText>
      </div>
      {assessment ? (
        <div className="pt-raised">
          <span>The assessor answered on its own. No event was raised: an override starts when a person disagrees.</span>
          <span className="pt-raised__sp" />
          <Button variant="quiet" icon="arrowR" onClick={() => go("decide")}>
            Override it
          </Button>
        </div>
      ) : null}
    </div>
  );
}

/** The output an override put in place, which the library keeps on the override subject. */
const replacementOf = (event: HumanEvent) => (typeof event.subject.detail.replacement === "string" ? event.subject.detail.replacement : undefined);

const RATIONALE = "The declared income includes a one-off redundancy payment of 9,000 EUR. Ana's regular income is 23,000 EUR, under the limit.";

function OverrideDecide({ run, setRun, event, go }: SceneProps) {
  const oversight = useOversight();
  const registry = useRegistry().data;
  const me = useMe();
  const actAs = useActAs();
  const controls = useDomain<{ controls: ControlSettings[] }>("/oversight/controls", 60_000).data;
  const settings = controls?.controls.find((c) => c.control.rowId === "pt-00008");
  const [action, setAction] = useState("REVERSE");
  const [rationale, setRationale] = useState(RATIONALE);
  const [replacement, setReplacement] = useState("Eligible");
  const [start, busy, error] = useAction();
  const officer = "s.patel@example.com";

  useEffect(() => {
    if (!event && registry && me?.subject !== officer) void actAs(officer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registry, event]);

  if (!run) return null;
  const assessment = run.data.assessment as Assessment;
  if (event) {
    return (
      <div className="pt-stack">
        <div className="pt-raised">
          <span className="pt-raised__mark">
            <Icon name="check" size={14} />
          </span>
          <span>
            The override was raised and decided at once as <Mono>{event.id}</Mono>.
          </span>
          <OutcomeChip row={event} />
          <span className="pt-raised__sp" />
          <Button variant="quiet" icon="arrowR" onClick={() => go("effect")}>
            See what changed
          </Button>
        </div>
        <AssessmentFrame
          assessment={assessment}
          override={{ action: OVERRIDDEN[event.outcome?.action ?? action] ?? actionLabel(event.outcome?.action ?? action, event.actionLabels), by: nameIn(registry, event.decisions.at(-1)?.reviewer.subject), rationale: event.decisions.at(-1)?.rationale ?? rationale, replacement: replacementOf(event) }}
        />
      </div>
    );
  }
  const overrideIt = () =>
    start(async () => {
      const decided = await oversight!.client.override({
        control: "pt-00008",
        ref: run.entity!,
        action,
        rationale,
        ...(action === "OVERRIDE" ? { replacement } : {})
      });
      oversight!.cache.set(`event:${decided.id}`, decided);
      setRun({ ...run, eventId: decided.id, events: [decided.id] });
    });
  const actions = settings?.allowedActions ?? ["OVERRIDE", "DISREGARD", "REVERSE"];
  return (
    <div className="pt-decide pt-decide--single" data-testid="override">
      <div className="pt-actas">
        <span className="pt-avatar" aria-hidden="true">
          SP
        </span>
        <div className="pt-actas__who">
          <span className="pt-actas__line">
            The tour acts as <b>{nameIn(registry, me?.subject)}</b>, an oversight officer.
          </span>
          <span className="pt-actas__roles">The control allows system owners and oversight officers to override; the library checks the role when the override is made.</span>
        </div>
      </div>
      <div className="pt-card">
        <Eyebrow>Override the output</Eyebrow>
        <h3 className="pt-question">Should the eligibility output for Ana Ruiz be set aside?</h3>
        <div className="pt-segment" role="radiogroup" aria-label="Override action">
          {actions.map((a) => (
            <button key={a} type="button" role="radio" aria-checked={action === a} className={action === a ? "on" : undefined} onClick={() => setAction(a)}>
              {settings ? actionLabel(a as never, settings.actionLabels) : a}
            </button>
          ))}
        </div>
        {action === "OVERRIDE" ? (
          <label className="pt-field">
            <span>The output in its place</span>
            <input value={replacement} onChange={(e) => setReplacement(e.target.value)} />
          </label>
        ) : null}
        <label className="pt-field">
          <span>Why (required)</span>
          <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} rows={3} />
        </label>
        <div className="pt-start__row">
          <Button icon="check" onClick={overrideIt} busy={busy} disabled={!rationale.trim() || me?.subject !== officer} testId="override-submit">
            Record the override
          </Button>
          <span className="pt-start__call">
            The tour calls <Mono>POST /oversight/overrides</Mono> as {nameIn(registry, me?.subject)}.
          </span>
        </div>
        <ErrorText>{error}</ErrorText>
      </div>
    </div>
  );
}

function OverrideEffect({ run, event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  const registry = useRegistry().data;
  if (!run || !event) return null;
  return (
    <div className="pt-stack">
      <AssessmentFrame
        assessment={run.data.assessment as Assessment}
        override={{
          action: OVERRIDDEN[event.outcome?.action ?? "REVERSE"] ?? actionLabel(event.outcome?.action ?? "REVERSE", event.actionLabels),
          by: nameIn(registry, event.decisions.at(-1)?.reviewer.subject),
          rationale: event.decisions.at(-1)?.rationale ?? "",
          replacement: replacementOf(event)
        }}
      />
      {record ? (
        <Note>
          The apply handler recorded <Mono>{record.outcome}</Mono> for the override of <Mono>{run.entity}</Mono>.
        </Note>
      ) : null}
      <AppliedLine event={event} />
      <NextBar go={go} to="record" label="See the record" />
    </div>
  );
}

export const override: Scene = {
  Trigger: OverrideTrigger,
  Decide: OverrideDecide,
  Effect: OverrideEffect,
  reached: (run, event) => (!run ? 0 : !event ? 1 : 3)
};

// ---- flag and feedback

const QUESTION = "When will the refund for order 4471 arrive?";
const WRONG = "Refunds are paid within 24 hours, so it should already be in your account.";
const NOTE = "Refunds take 5 to 10 working days. The assistant promised 24 hours.";

function FlagChat({ flagged, accepted }: { flagged: boolean; accepted?: boolean }) {
  return (
    <Frame role="The customer's chat" icon="chat" title="Web chat · order 4471" status={flagged ? <Pill tone="wait" pulse={accepted === undefined}>Reply flagged</Pill> : <Pill>Live</Pill>} testId="frame-flag-chat">
      <div className="pt-chat">
        <div className="pt-bubble pt-bubble--user">{QUESTION}</div>
        <div className={`pt-bubble pt-bubble--agent${flagged ? " pt-bubble--flagged" : ""}`}>
          {WRONG}
          {flagged ? (
            <span className="pt-bubble__flag">
              <Icon name="flag" size={12} /> Flagged: {NOTE}
            </span>
          ) : null}
        </div>
      </div>
    </Frame>
  );
}

function FlagTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const flagIt = () =>
    start(async () => {
      const flow = `flow-${Math.random().toString(36).slice(2, 8)}`;
      const raised = await call<{ eventId: string }>("POST", `/replies/${flow}/flag`, { by: "customer:order-4471", note: NOTE, output: WRONG });
      setRun(newRun({ entity: flow, eventId: raised.eventId, events: [raised.eventId] }));
    });
  return (
    <div className="pt-stack">
      <FlagChat flagged={!!run} />
      <div className="pt-start">
        <div className="pt-start__row">
          <Button icon={run ? "reset" : "flag"} variant={run ? "secondary" : "primary"} onClick={flagIt} busy={busy} testId="run">
            {run ? "Flag it again" : "Flag the reply"}
          </Button>
          <span className="pt-start__call">
            The tour calls <Mono>POST /replies/{run?.entity ?? "{flowId}"}/flag</Mono> on the patterns service.
          </span>
        </div>
        <ErrorText>{error}</ErrorText>
      </div>
      <RaisedBar event={event} go={go} />
    </div>
  );
}

function FlagEffect({ event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  const accepted = record ? record.outcome.includes("accepted") : undefined;
  return (
    <div className="pt-stack">
      <FlagChat flagged accepted={accepted} />
      <Frame
        role="The evaluation set"
        icon="ledger"
        title="refund-questions"
        status={record ? <Pill tone={accepted ? "ok" : "idle"}>{accepted ? "Case added" : "Dismissed"}</Pill> : <Pill tone="wait">Waiting for a triager</Pill>}
        testId="frame-evalset"
      >
        {record && accepted ? (
          <Rows
            rows={[
              ["Input", QUESTION],
              ["Wrong output", WRONG],
              ["Expected", NOTE],
              ["From", <Mono>{event!.id}</Mono>]
            ]}
          />
        ) : record ? (
          <p className="pt-muted">The triager dismissed the flag, so no case was added.</p>
        ) : (
          <Waiting>The flag is with a feedback triager.</Waiting>
        )}
      </Frame>
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const flagFeedback: Scene = { Trigger: FlagTrigger, Effect: FlagEffect };
