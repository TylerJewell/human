import { useRegistry } from "@akka-human/kit";
import { useState, type ReactNode } from "react";
import { nameIn } from "../parts/people";
import { AppliedLine, NextBar, RaisedBar, StartBar, Waiting } from "../steps/common";
import { Frame, Icon, Mono, money, Note, Pill, useAction, type Tone } from "../parts/ui";
import { call, newRun, useDomain } from "../tour/api";
import type { Scene, SceneProps } from "./types";

// The patterns where a workflow pauses for a person: action approval, decision
// review, change authorization, outcome reconciliation and hand-off. Each
// scenario starts the sample's workflow through its own route and reads the
// workflow's state back, which is what the people around it see.

/** Polls a route until `pick` finds the event id the workflow wrote into its state. */
async function awaitState<T>(path: string, pick: (t: T) => string | null | undefined, tries = 30): Promise<[T, string]> {
  for (let i = 0; i < tries; i++) {
    const state = await call<T>("GET", path);
    const id = pick(state);
    if (id) return [state, id];
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`${path} raised no event`);
}

/** One line of an agent's run: a step it has done, or the one it is waiting on. */
function RunLine({ state, children, aside }: { state: "done" | "wait" | "bad" | "next"; children: ReactNode; aside?: ReactNode }) {
  return (
    <li className={`pt-runline pt-runline--${state}`}>
      <span className="pt-runline__mark" aria-hidden="true">
        {state === "done" ? <Icon name="check" size={13} /> : null}
      </span>
      <span className="pt-runline__text">{children}</span>
      {aside ? <span className="pt-runline__aside">{aside}</span> : null}
    </li>
  );
}

// ---- action approval

interface Payment {
  id: string;
  supplier: string;
  amount: number;
  currency: string;
  status: string;
  approvalEventId: string | null;
  reconciliationEventId: string | null;
  attempts: number;
  note: string;
}

const PAYMENT_TONE: Record<string, [Tone, string]> = {
  CHECKING: ["wait", "Checking"],
  AWAITING_APPROVAL: ["wait", "Waiting for approval"],
  AWAITING_RECONCILIATION: ["wait", "Waiting for a person"],
  PAYING: ["wait", "Paying"],
  PAID: ["ok", "Paid"],
  NOT_PAID: ["bad", "Not paid"],
  DENIED: ["bad", "Cancelled"],
  NO_ANSWER: ["bad", "No answer"]
};

function PaymentPill({ status }: { status?: string }) {
  if (!status) return <Pill>Ready</Pill>;
  const [tone, word] = PAYMENT_TONE[status] ?? ["idle", status];
  return (
    <Pill tone={tone} pulse={tone === "wait"}>
      {word}
    </Pill>
  );
}

type Variant = "workflow" | "task" | "tool";

const VARIANTS: { id: Variant; label: string; call: string }[] = [
  { id: "workflow", label: "A workflow step", call: "POST /payments" },
  { id: "task", label: "An agent's task", call: "POST /agent-approvals" },
  { id: "tool", label: "An agent's tool call", call: "POST /tool-calls" }
];

const PAY = { supplier: "S-77", amount: 4200, currency: "EUR" };

interface TaskState {
  taskId: string;
  status: string;
  failureReason: string | null;
}

interface ToolDecision {
  decision: "allow" | "deny" | "fail";
  reason: string;
}

function ApprovalTrigger({ run, setRun, event, go }: SceneProps) {
  const ran = (run?.data.variant as Variant | undefined) ?? undefined;
  const [variant, setVariant] = useState<Variant>(ran ?? "workflow");
  const [start, busy, error] = useAction();
  const payment = useDomain<Payment>(ran === "workflow" && run?.entity ? `/payments/${run.entity}` : undefined).data;

  const runIt = () =>
    start(async () => {
      if (variant === "workflow") {
        const { id } = await call<{ id: string }>("POST", "/payments", { ...PAY, resultUnknown: false });
        const [, eventId] = await awaitState<Payment>(`/payments/${id}`, (p) => p.approvalEventId);
        setRun(newRun({ entity: id, eventId, events: [eventId], data: { variant } }));
      } else if (variant === "task") {
        const { taskId, eventId } = await call<{ taskId: string; eventId: string }>("POST", "/agent-approvals", {
          agentId: "payments",
          tool: "pay",
          arguments: PAY
        });
        setRun(newRun({ entity: taskId, eventId, events: [eventId], data: { variant } }));
      } else {
        const session = `chat-${Math.random().toString(36).slice(2, 8)}`;
        const answer = await toolCall(session);
        const eventId = /(he-[\w-]+)/.exec(answer.reason)?.[1];
        if (!eventId) throw new Error(`The guardrail answered ${answer.decision}: ${answer.reason}`);
        setRun(newRun({ entity: session, eventId, events: [eventId], data: { variant, first: answer } }));
      }
    });

  const first = run?.data.first as ToolDecision | undefined;
  return (
    <div className="pt-stack">
      <div className="pt-segment" role="radiogroup" aria-label="How the agent asks">
        {VARIANTS.map((v) => (
          <button
            key={v.id}
            type="button"
            role="radio"
            aria-checked={variant === v.id}
            className={variant === v.id ? "on" : undefined}
            onClick={() => setVariant(v.id)}
          >
            {v.label}
          </button>
        ))}
      </div>
      <StartBar call={VARIANTS.find((v) => v.id === variant)!.call} label="Ask to pay 4,200 EUR" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Frame
        role="The payments agent"
        icon="agent"
        title="Pay supplier S-77 for invoice INV-2207"
        status={ran === "workflow" ? <PaymentPill status={payment?.status} /> : run ? <Pill tone="wait" pulse>Waiting for approval</Pill> : <Pill>Ready</Pill>}
        testId="frame-agent"
      >
        <ol className="pt-run">
          <RunLine state="done">Read invoice INV-2207 from S-77 Nordic Freight: 4,200 EUR.</RunLine>
          <RunLine state="done">Matched it to purchase order PO-5521.</RunLine>
          <RunLine
            state={run ? "wait" : "next"}
            aside={variant === "tool" && first ? <Mono>{first.decision}</Mono> : undefined}
          >
            <Mono>pay(supplier: "S-77", amount: 4200, currency: "EUR")</Mono>
          </RunLine>
        </ol>
        {variant === "tool" && first ? (
          <Note>
            The guardrail denied the call with <Mono>{first.reason}</Mono>. The agent sees a refusal and retries after approval; SDK request 13 would let the
            call wait instead.
          </Note>
        ) : variant === "task" && run ? (
          <Note>
            The agent's next task depends on the unassigned approval task <Mono>{run.entity}</Mono>, which completes when a person approves.
          </Note>
        ) : (
          <Note>Payments above 1,000 EUR need an AI system owner's approval before the agent may make them.</Note>
        )}
      </Frame>
    </div>
  );
}

function toolCall(session: string) {
  return call<ToolDecision>("POST", "/tool-calls", {
    agentId: "payments",
    sessionId: session,
    tool: "pay",
    arguments: JSON.stringify(PAY)
  });
}

function ApprovalEffect({ run, event, go }: SceneProps) {
  const variant = (run?.data.variant as Variant | undefined) ?? "workflow";
  const payment = useDomain<Payment>(variant === "workflow" && run?.entity ? `/payments/${run.entity}` : undefined).data;
  const task = useDomain<TaskState>(variant === "task" && run?.entity ? `/agent-approvals/${run.entity}` : undefined).data;
  const [retry, setRetry] = useState<ToolDecision>();
  const [start, busy, error] = useAction();
  if (!run) return null;
  return (
    <div className="pt-stack">
      <Frame
        role="The payments agent"
        icon="agent"
        title="Pay supplier S-77 for invoice INV-2207"
        status={
          variant === "workflow" ? (
            <PaymentPill status={payment?.status} />
          ) : variant === "task" ? (
            <Pill tone={task?.status === "COMPLETED" ? "ok" : task?.status === "FAILED" ? "bad" : "wait"}>{task ? readableTask(task.status) : "Reading"}</Pill>
          ) : (
            <Pill tone={retry ? (retry.decision === "allow" ? "ok" : "bad") : "idle"}>{retry ? `Guardrail: ${retry.decision}` : "Ready to retry"}</Pill>
          )
        }
      >
        <ol className="pt-run">
          <RunLine state="done">Read invoice INV-2207 from S-77 Nordic Freight: 4,200 EUR.</RunLine>
          <RunLine state="done">Matched it to purchase order PO-5521.</RunLine>
          {variant === "workflow" ? (
            <RunLine state={payment?.status === "PAID" ? "done" : payment?.status === "DENIED" ? "bad" : "wait"}>
              {payment?.status === "PAID"
                ? `Paid ${money(payment.amount)} to S-77.`
                : payment?.status === "DENIED"
                  ? `Cancelled the payment: ${payment.note}.`
                  : "Waiting for the decision."}
            </RunLine>
          ) : variant === "task" ? (
            <RunLine state={task?.status === "COMPLETED" ? "done" : task?.status === "FAILED" ? "bad" : "wait"}>
              {task?.status === "COMPLETED"
                ? "The approval task completed, so the agent's payment task may run."
                : task?.status === "FAILED"
                  ? `The approval task failed (${task.failureReason}), which cancels the tasks that depend on it.`
                  : "The approval task is still open."}
            </RunLine>
          ) : (
            <RunLine state={retry?.decision === "allow" ? "done" : retry ? "bad" : "next"} aside={retry ? <Mono>{retry.decision}</Mono> : undefined}>
              <Mono>pay(supplier: "S-77", amount: 4200, currency: "EUR")</Mono>
            </RunLine>
          )}
        </ol>
        {variant === "workflow" && payment?.note ? (
          <Note>
            The workflow recorded: <Mono>{payment.note}</Mono>
          </Note>
        ) : null}
        {variant === "tool" ? (
          <div className="pt-inline">
            <button
              type="button"
              className="pt-btn pt-btn--secondary"
              disabled={busy || event?.pending}
              onClick={() => start(async () => setRetry(await toolCall(run.entity!)))}
            >
              The agent retries the call
            </button>
            {retry ? (
              <span className="pt-muted">
                The guardrail answered <Mono>{retry.decision}</Mono>
                {retry.reason ? `: ${retry.reason}` : ""}.
              </span>
            ) : event?.pending ? (
              <span className="pt-muted">The retry passes once the call is approved.</span>
            ) : null}
            {error ? <span className="pt-error">{error}</span> : null}
          </div>
        ) : null}
      </Frame>
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

const readableTask = (status: string) => status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, " ");

export const actionApproval: Scene = { Trigger: ApprovalTrigger, Effect: ApprovalEffect };

// ---- decision review

interface Notice {
  id: string;
  applicant: string;
  text: string;
  decisionRef: string;
  status: string;
  eventId: string | null;
  note: string;
}

const NOTICE_TEXT =
  "Dear Mara Lindqvist,\n\nYour claim for housing benefit, reference HB-30117, has been declined. The income you reported, 3,900 EUR a month, is above the limit of 3,400 EUR for a household of two.\n\nYou can appeal this decision within 30 days by replying to this letter or through your account. A person who was not involved in this decision will review your appeal.";

const NOTICE_TONE: Record<string, [Tone, string]> = {
  AWAITING_REVIEW: ["wait", "Held for review"],
  SENT: ["ok", "Sent"],
  WITHHELD: ["bad", "Withheld"],
  NO_ANSWER: ["bad", "No answer"]
};

function Letter({ text, status }: { text: string; status?: string }) {
  const [tone, word] = status ? (NOTICE_TONE[status] ?? ["idle", status]) : ["idle" as Tone, "Draft"];
  return (
    <Frame role="Mara Lindqvist's letter" icon="letter" title="Decision on claim HB-30117" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-letter">
      <div className={`pt-letter${status === "WITHHELD" ? " pt-letter--void" : ""}`}>
        {text.split("\n\n").map((p, i) => (
          <p key={i}>{p}</p>
        ))}
      </div>
    </Frame>
  );
}

function NoticeTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const notice = useDomain<Notice>(run?.entity ? `/notices/${run.entity}` : undefined).data;
  const runIt = () =>
    start(async () => {
      const { id } = await call<{ id: string }>("POST", "/notices", { applicant: "Mara Lindqvist", text: NOTICE_TEXT, decisionRef: "HB-30117" });
      const [, eventId] = await awaitState<Notice>(`/notices/${id}`, (n) => n.eventId);
      setRun(newRun({ entity: id, eventId, events: [eventId] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /notices" label="Draft the notice" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Letter text={NOTICE_TEXT} status={notice?.status} />
    </div>
  );
}

function NoticeEffect({ run, event, go }: SceneProps) {
  const notice = useDomain<Notice>(run?.entity ? `/notices/${run.entity}` : undefined).data;
  if (!notice) return <Waiting>Reading the notice.</Waiting>;
  return (
    <div className="pt-stack">
      <Letter text={notice.text} status={notice.status} />
      {notice.status === "SENT" && notice.text !== NOTICE_TEXT ? <Note>The reviewer amended the letter before it was sent; this is the text Mara received.</Note> : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const decisionReview: Scene = { Trigger: NoticeTrigger, Effect: NoticeEffect };

// ---- change authorization

interface Rollout {
  id: string;
  model: string;
  from: string;
  to: string;
  proposedBy: string;
  builtBy: string;
  trafficShare: number;
  status: string;
  eventId: string | null;
  note: string;
}

const ROLLOUT_TONE: Record<string, [Tone, string]> = {
  AWAITING_AUTHORIZATION: ["wait", "At the gate"],
  ROLLED_OUT: ["ok", "Rolled out"],
  REJECTED: ["bad", "Rejected"],
  NO_ANSWER: ["bad", "No answer"]
};

function Pipeline({ rollout }: { rollout?: Rollout }) {
  const status = rollout?.status;
  const stage = !status ? 1 : status === "AWAITING_AUTHORIZATION" ? 2 : status === "ROLLED_OUT" ? 4 : 2;
  const stages = ["Trained", "Evaluated offline", "Authorization", "10% of traffic"];
  const [tone, word] = status ? (ROLLOUT_TONE[status] ?? ["idle", status]) : ["idle" as Tone, "Proposed"];
  return (
    <Frame role="The release pipeline" icon="rollout" title="notice-writer: m-4 to m-5" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-pipeline">
      <ol className="pt-pipeline">
        {stages.map((s, i) => (
          <li key={s} className={i < stage ? "done" : i === stage ? (status === "REJECTED" ? "bad" : "at") : undefined}>
            <span className="pt-pipeline__dot" aria-hidden="true" />
            <span>{s}</span>
          </li>
        ))}
      </ol>
      <div className="pt-facts3">
        <div>
          <span className="pt-muted">Proposed by</span>
          <b>K. Osei</b>
        </div>
        <div>
          <span className="pt-muted">Built by</span>
          <b>trainer</b>
        </div>
        <div>
          <span className="pt-muted">Offline quality</span>
          <b>0.91 to 0.90</b>
        </div>
      </div>
      {rollout?.note ? (
        <Note>
          The pipeline recorded: <Mono>{rollout.note}</Mono>
        </Note>
      ) : null}
    </Frame>
  );
}

function RolloutTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const rollout = useDomain<Rollout>(run?.entity ? `/rollouts/${run.entity}` : undefined).data;
  const runIt = () =>
    start(async () => {
      const { id } = await call<{ id: string }>("POST", "/rollouts", {
        model: "notice-writer",
        from: "m-4",
        to: "m-5",
        proposedBy: "k.osei@example.com",
        builtBy: "trainer",
        trafficShare: 0.1
      });
      const [, eventId] = await awaitState<Rollout>(`/rollouts/${id}`, (r) => r.eventId);
      setRun(newRun({ entity: id, eventId, events: [eventId] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /rollouts" label="Propose the rollout" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Pipeline rollout={rollout} />
    </div>
  );
}

function RolloutEffect({ run, event, go }: SceneProps) {
  const rollout = useDomain<Rollout>(run?.entity ? `/rollouts/${run.entity}` : undefined).data;
  return (
    <div className="pt-stack">
      <Pipeline rollout={rollout} />
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const changeAuthorization: Scene = { Trigger: RolloutTrigger, Effect: RolloutEffect };

// ---- outcome reconciliation

function ProviderLog({ payment }: { payment?: Payment }) {
  const status = payment?.status;
  return (
    <Frame role="The payments agent" icon="agent" title="Pay supplier S-31 for invoice INV-1184" status={<PaymentPill status={status} />} testId="frame-provider">
      <ol className="pt-run">
        <RunLine state="done">Sent the payment of 860 EUR to the payment provider.</RunLine>
        <RunLine state={payment ? "bad" : "next"}>
          {payment ? "The provider accepted the request, then timed out without confirming it." : "Waiting for the provider to confirm."}
        </RunLine>
        {status === "PAID" ? (
          <RunLine state="done">A person confirmed the payment went through. Nothing is sent again.</RunLine>
        ) : status === "NOT_PAID" ? (
          <RunLine state="bad">A person confirmed it did not go through. The invoice stays open.</RunLine>
        ) : status && status !== "AWAITING_RECONCILIATION" ? (
          <RunLine state="done">
            Sent again: attempt {payment?.attempts}, {status === "PAID" ? "paid" : status.toLowerCase().replace(/_/g, " ")}.
          </RunLine>
        ) : payment ? (
          <RunLine state="wait">The workflow waits for a person instead of retrying.</RunLine>
        ) : null}
      </ol>
    </Frame>
  );
}

function ReconcileTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const payment = useDomain<Payment>(run?.entity ? `/payments/${run.entity}` : undefined).data;
  const runIt = () =>
    start(async () => {
      const { id } = await call<{ id: string }>("POST", "/payments", { supplier: "S-31", amount: 860, currency: "EUR", resultUnknown: true });
      const [, eventId] = await awaitState<Payment>(`/payments/${id}`, (p) => p.reconciliationEventId);
      setRun(newRun({ entity: id, eventId, events: [eventId] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /payments" label="Pay 860 EUR" onRun={runIt} busy={busy} error={error} ran={!!run}>
        <Note>The payment is under the 1,000 EUR limit, so it needs no approval; the provider's answer is what goes missing.</Note>
      </StartBar>
      <RaisedBar event={event} go={go} />
      <ProviderLog payment={payment} />
    </div>
  );
}

function ReconcileEffect({ run, event, go }: SceneProps) {
  const payment = useDomain<Payment>(run?.entity ? `/payments/${run.entity}` : undefined).data;
  return (
    <div className="pt-stack">
      <ProviderLog payment={payment} />
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const outcomeReconciliation: Scene = { Trigger: ReconcileTrigger, Effect: ReconcileEffect };

// ---- hand-off

interface Conversation {
  id: string;
  channel: string;
  status: string;
  eventId: string | null;
  takenOverBy: string | null;
}

const SAID = ["My refund for order 4471 still has not arrived.", "This is the third time I am asking. I want to talk to a person, please."];

const CHAT_TONE: Record<string, [Tone, string]> = {
  WAITING_FOR_PERSON: ["wait", "Waiting for a person"],
  WITH_PERSON: ["ok", "With a person"],
  WITH_AGENT: ["gold", "With the agent"],
  CLOSED: ["idle", "Closed"],
  NO_ANSWER: ["bad", "No answer"]
};

function Chat({ conversation, started, names }: { conversation?: Conversation; started: boolean; names: (s: string) => string }) {
  const status = conversation?.status;
  const [tone, word] = status ? (CHAT_TONE[status] ?? ["idle", status]) : ["idle" as Tone, "Live"];
  return (
    <Frame role="The customer's chat" icon="chat" title="Web chat · order 4471" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-chat">
      <div className="pt-chat">
        <div className="pt-bubble pt-bubble--agent">Hello, I am the support assistant. How can I help?</div>
        {SAID.map((s, i) => (
          <div key={i} className={`pt-bubble pt-bubble--user${!started && i === 1 ? " pt-bubble--typing" : ""}`}>
            {s}
          </div>
        ))}
        {started ? <div className="pt-bubble pt-bubble--agent">I will bring in a person now.</div> : null}
        {status === "WITH_PERSON" ? (
          <>
            <div className="pt-chat__join">{names(conversation!.takenOverBy ?? "")} joined the conversation.</div>
            <div className="pt-bubble pt-bubble--person">
              Hello, this is {names(conversation!.takenOverBy ?? "").split(" ").slice(-1)[0]}. I can see the refund was issued on Monday; I am checking with the bank
              now.
            </div>
          </>
        ) : status === "WITH_AGENT" ? (
          <div className="pt-chat__join">The conversation went back to the assistant.</div>
        ) : status === "WAITING_FOR_PERSON" ? (
          <div className="pt-chat__join pt-chat__join--wait">A person will join shortly.</div>
        ) : null}
      </div>
    </Frame>
  );
}

function HandOffTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const conversation = useDomain<Conversation>(run?.entity ? `/handoffs/${run.entity}` : undefined).data;
  const names = useNames();
  const runIt = () =>
    start(async () => {
      const { id } = await call<{ id: string }>("POST", "/handoffs", { channel: "web chat", reason: "user-asked", userSaid: SAID });
      const [, eventId] = await awaitState<Conversation>(`/handoffs/${id}`, (c) => c.eventId);
      setRun(newRun({ entity: id, eventId, events: [eventId] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /handoffs" label="Ask for a person" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Chat conversation={conversation} started={!!run} names={names} />
    </div>
  );
}

function HandOffEffect({ run, event, go }: SceneProps) {
  const conversation = useDomain<Conversation>(run?.entity ? `/handoffs/${run.entity}` : undefined).data;
  const names = useNames();
  return (
    <div className="pt-stack">
      <Chat conversation={conversation} started names={names} />
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const handOff: Scene = { Trigger: HandOffTrigger, Effect: HandOffEffect };


function useNames() {
  const registry = useRegistry().data;
  return (s: string) => nameIn(registry, s);
}
