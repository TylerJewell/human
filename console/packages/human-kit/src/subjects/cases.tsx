import type { AppealDetail, AttestationDetail, ConversationDetail, ExceptionDetail, Reviewer } from "../types";
import { Button, Chip, Facts, Icon, Meter, Prose, Tag, type Fact } from "../components/ui";
import { actionDone, formatDuration, formatMoment, formatShortMoment, parseDuration, readable } from "../words";
import { fromNow, humanSpan } from "./operations";

// The subjects that are a person's case: an appeal against a decision that
// already stands, a rule set aside until an expiry, a conversation handed to
// a person, and an attestation whose evidence ages.

// ---------------------------------------------------------------- appeal

const DECISION_WORDS: Record<string, string> = { APPROVE: "Approved", DENY: "Denied" };

export function AppealView({ detail, reviewer }: { detail: AppealDetail; reviewer?: Reviewer | null }) {
  const { original } = detail;
  const excluded = detail.excluded ?? [];
  const barred = reviewer && excluded.includes(reviewer.subject);
  const facts: Fact[] = [];
  if (detail.appellant) {
    const { name, ref } = detail.appellant;
    facts.push({ label: "Appellant", value: name ? `${name}${ref && ref !== name ? ` (${ref})` : ""}` : ref });
  }
  if (detail.filedAt) facts.push({ label: "Filed", value: formatMoment(detail.filedAt) });
  if (detail.right) facts.push({ label: "Right to review", value: detail.right });
  if (excluded.length)
    facts.push({
      label: "May not decide",
      value: (
        <span className="hk-inline-list" data-testid="appeal-excluded">
          {excluded.map((x) => (
            <Tag key={x}>{x}</Tag>
          ))}
        </span>
      )
    });
  const attachments = detail.attachments ?? [];
  return (
    <div className="hk-stack" data-testid="subject-appeal">
      <article className="hk-card" data-testid="appeal-original">
        <header className="hk-card__head">
          <span className="hk-card__eyebrow">The decision appealed</span>
          <Chip tone="default" icon={false}>
            {DECISION_WORDS[original.decision] ?? actionDone(original.decision)}
          </Chip>
        </header>
        <p className="hk-card__title">{original.summary}</p>
        <p className="hk-muted">
          {[original.decidedBy ? `By ${original.decidedBy}` : undefined, original.decidedAt ? formatMoment(original.decidedAt) : undefined]
            .filter(Boolean)
            .join(" · ")}{" "}
          <code className="hk-code">{original.ref}</code>
        </p>
      </article>
      <figure className="hk-quote" data-testid="appeal-grounds">
        <figcaption className="hk-quote__caption">Grounds</figcaption>
        <blockquote className="hk-quote__body">{detail.grounds}</blockquote>
      </figure>
      <Facts facts={facts} testId="appeal-facts" />
      {barred ? (
        <p className="hk-note hk-note--warning" role="note" data-testid="appeal-barred">
          You are named among those who may not decide this appeal.
        </p>
      ) : null}
      {attachments.length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-subheading">Attachments</h3>
          <ul className="hk-evidence">
            {attachments.map((a) => (
              <li key={`${a.kind}-${a.ref}`}>
                <span className="hk-evidence__kind">{readable(a.kind)}</span>
                <code className="hk-code">{a.ref}</code>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- exception

export function ExceptionView({ detail, now }: { detail: ExceptionDetail; now: number }) {
  const until = Date.parse(detail.until);
  const from = detail.from ? Date.parse(detail.from) : undefined;
  const expired = until <= now;
  const span = from !== undefined && until > from ? (Math.min(now, until) - from) / (until - from) : undefined;
  const facts: Fact[] = [{ label: "Scope", value: detail.scope }];
  if (detail.requestedBy) facts.push({ label: "Requested by", value: detail.requestedBy });
  if (detail.grantor) facts.push({ label: "Granted by", value: readable(detail.grantor) });
  return (
    <div className="hk-stack" data-testid="subject-exception">
      <article className="hk-card hk-card--rule" data-testid="exception-rule">
        <header className="hk-card__head">
          <span className="hk-card__eyebrow">Rule set aside</span>
          <code className="hk-code">{detail.rule.id}</code>
        </header>
        <p className="hk-card__title">{detail.rule.text}</p>
      </article>
      <div className="hk-window" data-testid="exception-window" data-expired={expired}>
        <div className="hk-window__ends">
          <span>
            <span className="hk-muted">From </span>
            {detail.from ? formatShortMoment(detail.from) : "once granted"}
          </span>
          <span>
            <span className="hk-muted">Until </span>
            {formatShortMoment(detail.until)}
          </span>
        </div>
        {span !== undefined ? (
          <Meter value={span} tone={expired ? "danger" : "brand"} label="Share of the exception's window that has passed" />
        ) : null}
        <p className={expired ? "hk-note hk-note--danger" : "hk-note"} data-testid="exception-expiry">
          {expired ? `Expired ${fromNow(detail.until, now)}.` : `Expires ${fromNow(detail.until, now)}, on ${formatMoment(detail.until)}.`}
        </p>
      </div>
      <Facts facts={facts} testId="exception-facts" />
      {detail.justification ? <Prose heading="Justification">{detail.justification}</Prose> : null}
      {detail.compensating?.length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-subheading">Compensating controls</h3>
          <ul className="hk-ticks" data-testid="exception-compensating">
            {detail.compensating.map((c) => (
              <li key={c}>
                <Icon name="check" className="hk-ticks__icon" />
                {c}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- conversation

const REASON: Record<string, { word: string; tone: "danger" | "warning" | "default" }> = {
  "user-asked": { word: "The user asked for a person", tone: "default" },
  crisis: { word: "Crisis signal", tone: "danger" },
  "step-failed": { word: "A step failed", tone: "warning" },
  policy: { word: "Policy requires a person", tone: "default" }
};

const ROLE: Record<string, string> = { user: "User", agent: "Agent", person: "Person", system: "System" };

export function ConversationView({
  detail,
  canTakeOver,
  takeOverLabel = "Take over",
  onTakeOver
}: {
  detail: ConversationDetail;
  canTakeOver?: boolean;
  takeOverLabel?: string;
  onTakeOver?: () => void;
}) {
  const reason = REASON[detail.reason] ?? { word: readable(detail.reason), tone: "default" as const };
  return (
    <div className="hk-stack" data-testid="subject-conversation">
      <div className="hk-convo-head">
        <Chip tone={reason.tone}>{reason.word}</Chip>
        <span className="hk-muted">on {readable(detail.channel).toLowerCase()}</span>
        {detail.takenOverBy ? (
          <Chip tone="accent" testId="conversation-taken">
            Taken over by {detail.takenOverBy}
          </Chip>
        ) : null}
      </div>
      {detail.run?.error ? (
        <p className="hk-note hk-note--warning" data-testid="conversation-run">
          {detail.run.step ? `Step ${detail.run.step}` : "A step"}
          {detail.run.workflowId ? ` of ${detail.run.workflowId}` : ""} failed: {detail.run.error}
        </p>
      ) : null}
      <ol className="hk-transcript" aria-label="Transcript" data-testid="conversation-transcript">
        {detail.transcript.map((turn, i) => (
          <li key={i} className={`hk-turn hk-turn--${ROLE[turn.role] ? turn.role : "system"}`} data-testid="conversation-turn">
            <span className="hk-turn__who">
              {ROLE[turn.role] ?? readable(turn.role)}
              {turn.at ? <span className="hk-turn__at"> · {formatShortMoment(turn.at)}</span> : null}
            </span>
            <span className="hk-turn__text">{turn.text}</span>
          </li>
        ))}
      </ol>
      {!detail.takenOverBy && canTakeOver && onTakeOver ? (
        <div className="hk-takeover">
          <Button variant="primary" onClick={onTakeOver} testId="conversation-take-over">
            <Icon name="person" />
            {takeOverLabel}
          </Button>
          <span className="hk-muted">The agent stops answering while you have the conversation.</span>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- attestation

const ATTESTS: Record<string, string> = { control: "Control", model: "Model", framework: "Framework" };

export function AttestationView({ detail, now }: { detail: AttestationDetail; now: number }) {
  const cadence = parseDuration(detail.cadence);
  const facts: Fact[] = [
    {
      label: ATTESTS[detail.attests] ?? readable(detail.attests),
      value: (
        <>
          {detail.target.name} <code className="hk-code">{detail.target.ref}</code>
        </>
      )
    }
  ];
  if (detail.cadence) facts.push({ label: "Cadence", value: `Every ${formatDuration(detail.cadence)}` });
  if (detail.periodEnd)
    facts.push({ label: "Period ends", value: `${formatMoment(detail.periodEnd)} (${fromNow(detail.periodEnd, now)})` });
  facts.push({
    label: "Last attested",
    value: detail.lastAttestedAt ? `${formatMoment(detail.lastAttestedAt)} (${fromNow(detail.lastAttestedAt, now)})` : "Never"
  });
  const evidence = detail.evidence.map((e) => {
    const age = e.asOf ? now - Date.parse(e.asOf) : undefined;
    const stale = e.stale === true || (age !== undefined && cadence !== undefined && age > cadence);
    return { ...e, age, stale };
  });
  const current = evidence.filter((e) => !e.stale).length;
  return (
    <div className="hk-stack" data-testid="subject-attestation">
      <Facts facts={facts} testId="attestation-facts" />
      <div className="hk-stack hk-stack--tight">
        <h3 className="hk-subheading">Evidence</h3>
        <p className={current < evidence.length ? "hk-note hk-note--warning" : "hk-note"} data-testid="attestation-freshness">
          {evidence.length === 0
            ? "No evidence is attached."
            : `${current} of ${evidence.length} ${evidence.length === 1 ? "piece is" : "pieces are"} current${cadence ? ` for a ${formatDuration(detail.cadence)} cadence` : ""}.`}
        </p>
        <ul className="hk-evidence hk-evidence--aged" data-testid="attestation-evidence">
          {evidence.map((e) => (
            <li key={`${e.kind}-${e.ref}`} data-stale={e.stale}>
              <span className="hk-evidence__kind">{readable(e.kind)}</span>
              <span>
                {e.summary ?? ""} <code className="hk-code hk-muted">{e.ref}</code>
              </span>
              <span className="hk-evidence__age">
                {e.asOf ? (
                  <span className="hk-muted" title={formatMoment(e.asOf)}>
                    {e.age !== undefined && e.age >= 0 ? `${humanSpan(e.age)} old` : `as of ${formatShortMoment(e.asOf)}`}
                  </span>
                ) : (
                  <span className="hk-muted">Undated</span>
                )}
                {e.stale ? <Chip tone="warning">Stale</Chip> : <Chip tone="success">Current</Chip>}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
