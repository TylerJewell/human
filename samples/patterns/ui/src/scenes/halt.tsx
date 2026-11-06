import { formatMoment, useEvent, useOversight, useRead, useRegistry, type Halt } from "@akka-human/kit";
import { useEffect, useState } from "react";
import { nameIn, useActAs, useMe } from "../parts/people";
import { Button, ErrorText, Eyebrow, Frame, Icon, Mono, Note, Pill, useAction } from "../parts/ui";
import { DecideStep } from "../steps/Decide";
import { AppliedLine, NextBar } from "../steps/common";
import { call, CallError, newRun } from "../tour/api";
import type { Scene, SceneProps } from "./types";

// Halt and restart of the eligibility capability. A safety officer halts it
// at once; the endpoint and the agents' guardrails then refuse its calls. A
// restart is a separate event: whoever asks for it cannot approve it, two
// people must, and approval waits for the service's safe-state checks.

const TARGET = "eligibility-assessment";
const OFFICER = "s.patel@example.com";

interface CallLine {
  at: string;
  status: number;
  said: string;
}

function useHalt(): Halt | undefined {
  return useRead<Halt[]>("halts", (client) => client.halts(), { pollMs: 1500 }).data?.find((h) => h.target === TARGET);
}

/** The eligibility service as its operators see it: halted or running, and the calls made to it. */
function ServiceFrame({ halt, calls, onCall, busy }: { halt?: Halt; calls: CallLine[]; onCall: () => void; busy: boolean }) {
  const registry = useRegistry().data;
  return (
    <Frame
      role="The eligibility service"
      icon="halt"
      title="eligibility-assessment"
      status={halt?.halted ? <Pill tone="bad">Halted</Pill> : <Pill tone="ok">Running</Pill>}
      testId="frame-service"
    >
      {halt?.halted ? (
        <p className="pt-lead">
          Halted by {nameIn(registry, halt.by)} {halt.since ? formatMoment(halt.since) : ""}. Every assessment, tool call and model call is refused.
        </p>
      ) : (
        <p className="pt-lead">Assessments are answered as they arrive.</p>
      )}
      <div className="pt-calls">
        <Button variant="secondary" icon="play" onClick={onCall} busy={busy} testId="assess">
          Send an assessment
        </Button>
        <ol className="pt-calls__log" aria-live="polite">
          {calls.length === 0 ? <li className="pt-muted">No calls sent from the tour yet.</li> : null}
          {calls.slice(0, 4).map((c, i) => (
            <li key={i} className={c.status === 200 ? "ok" : "bad"}>
              <Mono>POST /eligibility/assess</Mono>
              <span className="pt-calls__status">{c.status}</span>
              <span className="pt-muted">{c.said}</span>
            </li>
          ))}
        </ol>
      </div>
    </Frame>
  );
}

function useCalls(): [CallLine[], () => void, boolean] {
  const [calls, setCalls] = useState<CallLine[]>([]);
  const [busy, setBusy] = useState(false);
  const send = () => {
    setBusy(true);
    call<{ eligible: boolean }>("POST", "/eligibility/assess", { applicant: "Tomas Berg", income: 26000 })
      .then(
        (a) => ({ status: 200, said: a.eligible ? "eligible" : "not eligible" }),
        (e: unknown) => ({ status: e instanceof CallError ? e.status : 0, said: e instanceof Error ? e.message : String(e) })
      )
      .then((line) => {
        setCalls((c) => [{ at: new Date().toISOString(), ...line }, ...c]);
        setBusy(false);
      });
  };
  return [calls, send, busy];
}

function HaltTrigger({ run, setRun, go }: SceneProps) {
  const oversight = useOversight();
  const registry = useRegistry().data;
  const me = useMe();
  const actAs = useActAs();
  const halt = useHalt();
  const [calls, send, sending] = useCalls();
  const [reason, setReason] = useState("Assessments are approving applicants with missing payslips since 10:40. Stopping them until the cause is known.");
  const [start, busy, error] = useAction();

  useEffect(() => {
    if (registry && !halt?.halted && me?.subject !== OFFICER) void actAs(OFFICER);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registry, halt?.halted]);

  const haltIt = () =>
    start(async () => {
      const event = await oversight!.client.halt("pt-00012", false, reason);
      oversight!.cache.set(`event:${event.id}`, event);
      oversight!.cache.invalidate("halts");
      setRun(newRun({ events: [event.id], data: { haltEventId: event.id } }));
    });

  return (
    <div className="pt-stack">
      {halt?.halted ? (
        <div className="pt-raised">
          <span className="pt-raised__mark">
            <Icon name="check" size={14} />
          </span>
          <span>
            The halt took effect at once as <Mono>{halt.eventId}</Mono>. Send an assessment to see it refused.
          </span>
          <span className="pt-raised__sp" />
          <Button variant="quiet" icon="arrowR" onClick={() => go("decide")}>
            Restart it
          </Button>
        </div>
      ) : (
        <div className="pt-card">
          <Eyebrow>Halt the capability</Eyebrow>
          <p className="pt-muted">
            The tour acts as {nameIn(registry, me?.subject)}, a safety officer. One authorized person may halt; nobody approves a halt.
          </p>
          <label className="pt-field">
            <span>Why (required)</span>
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} />
          </label>
          <div className="pt-start__row">
            <Button icon="halt" onClick={haltIt} busy={busy} disabled={!reason.trim() || me?.subject !== OFFICER} testId="run">
              Halt eligibility assessment
            </Button>
            <span className="pt-start__call">
              The tour calls <Mono>POST /oversight/halts</Mono> as {nameIn(registry, me?.subject)}.
            </span>
          </div>
          <ErrorText>{error}</ErrorText>
          {run && !halt?.halted ? <Note>The last run has been restarted. Halt it again to run the scenario once more.</Note> : null}
        </div>
      )}
      <ServiceFrame halt={halt} calls={calls} onCall={send} busy={sending} />
    </div>
  );
}

function HaltDecide({ run, setRun, event, go }: SceneProps) {
  const oversight = useOversight();
  const registry = useRegistry().data;
  const me = useMe();
  const actAs = useActAs();
  const halt = useHalt();
  const [reason, setReason] = useState("The cause was a stale document check. It is fixed, and the backlog has been drained.");
  const [start, busy, error] = useAction();
  const [setting, settingBusy, settingError] = useAction();
  const drained = halt?.safeStateChecks?.find((c) => c.name === "backlog drained")?.passed ?? false;

  useEffect(() => {
    if (!event && registry && me?.subject !== OFFICER) void actAs(OFFICER);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [registry, event?.id]);

  if (!run) return null;
  const toggle = () =>
    setting(async () => {
      await call("PUT", "/eligibility/safe-state", { backlogDrained: !drained });
      oversight!.cache.invalidate("halts");
    });

  const safeState = (
    <div className="pt-card" data-testid="safe-state">
      <div className="pt-card__head">
        <Eyebrow>Safe-state checks</Eyebrow>
        <Pill tone={drained ? "ok" : "bad"}>{drained ? "All pass" : "One fails"}</Pill>
      </div>
      <div className="pt-check">
        <span className={`pt-check__mark${drained ? " ok" : ""}`}>{drained ? <Icon name="check" size={13} /> : null}</span>
        <span>The assessment backlog is drained.</span>
        <span className="pt-raised__sp" />
        <Button variant="secondary" onClick={toggle} busy={settingBusy}>
          {drained ? "Mark it not drained" : "Mark it drained"}
        </Button>
      </div>
      <Note>
        The service answers this check through its halt scope. The tour sets it with <Mono>PUT /eligibility/safe-state</Mono>.
      </Note>
      <ErrorText>{settingError}</ErrorText>
    </div>
  );

  if (!event) {
    const askRestart = () =>
      start(async () => {
        const restart = await oversight!.client.halt("pt-00012", true, reason);
        oversight!.cache.set(`event:${restart.id}`, restart);
        setRun({ ...run, eventId: restart.id, events: [...run.events.filter((e) => e !== restart.id), restart.id] });
      });
    return (
      <div className="pt-stack">
        <div className="pt-card">
          <Eyebrow>Ask for a restart</Eyebrow>
          <p className="pt-muted">
            {nameIn(registry, me?.subject)} asks for the restart. The control then bars them from approving it, so two other safety officers decide.
          </p>
          <label className="pt-field">
            <span>Why it is safe to restart</span>
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} />
          </label>
          <div className="pt-start__row">
            <Button icon="play" onClick={askRestart} busy={busy} disabled={!reason.trim() || !halt?.halted} testId="restart">
              Ask to restart
            </Button>
            <span className="pt-start__call">
              The tour calls <Mono>POST /oversight/halts</Mono> with <Mono>lift: true</Mono>.
            </span>
          </div>
          <ErrorText>{error}</ErrorText>
        </div>
        {safeState}
      </div>
    );
  }
  return (
    <div className="pt-stack">
      {event.pending ? safeState : null}
      <DecideStep event={event} go={go} />
    </div>
  );
}

function HaltEffect({ run, event, go }: SceneProps) {
  const halt = useHalt();
  const [calls, send, sending] = useCalls();
  const haltEvent = useEvent(run?.data.haltEventId as string | undefined).data;
  return (
    <div className="pt-stack">
      <ServiceFrame halt={halt} calls={calls} onCall={send} busy={sending} />
      {haltEvent && event ? (
        <Note>
          Halted under <Mono>{haltEvent.id}</Mono> and restarted under <Mono>{event.id}</Mono>.
        </Note>
      ) : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const haltRestart: Scene = {
  Trigger: HaltTrigger,
  Decide: HaltDecide,
  Effect: HaltEffect,
  reached: (run, event) => (!run ? 0 : !event || event.pending ? 1 : 3)
};
