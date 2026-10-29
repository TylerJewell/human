import type { ChangeDetail, RecordsDetail } from "../types";
import { Chip, Facts, Meter, Tag, type Fact } from "../components/ui";
import { capital, formatPercent, keyWords, listNames, readable, valueText } from "../words";

// The subjects that are two things side by side: a change, as each setting in
// force and proposed, with the evidence for it and who it reaches; and two
// records, as each field of one against the other, the fields that agree and
// those that differ.

export interface DiffPart {
  kind: "same" | "removed" | "added";
  text: string;
}

/** The words of `before` and `after`, each kept, struck or added: a longest-common-subsequence diff. */
export function diffWords(before: string, after: string): DiffPart[] {
  const a = before.split(/(\s+)/);
  const b = after.split(/(\s+)/);
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  const parts: DiffPart[] = [];
  const push = (kind: DiffPart["kind"], text: string) => {
    const last = parts[parts.length - 1];
    if (last?.kind === kind) last.text += text;
    else parts.push({ kind, text });
  };
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      push("same", a[i]);
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) push("removed", a[i++]);
    else push("added", b[j++]);
  }
  while (i < a.length) push("removed", a[i++]);
  while (j < b.length) push("added", b[j++]);
  return parts;
}

const text = (value: unknown, name: string) =>
  typeof value === "object" && value !== null ? JSON.stringify(value) : valueText(value, name);

type ChangeKind = "added" | "removed" | "amended" | undefined;

const CHANGE_TAG: Record<Exclude<ChangeKind, undefined>, { word: string; tone: "accent" | "danger" | "brand" }> = {
  added: { word: "Added", tone: "accent" },
  removed: { word: "Removed", tone: "danger" },
  amended: { word: "Amended", tone: "brand" }
};

/** One side's value: a long text changed in place shows its words struck or added. */
function DiffValue({ side, was, is }: { side: "before" | "after"; was?: string; is?: string }) {
  const value = side === "before" ? was : is;
  if (value === undefined) return <span className="hk-muted">Not set</span>;
  const changed = was !== is;
  if (changed && was !== undefined && is !== undefined && (was.length > 40 || is.length > 40)) {
    return (
      <span className={`hk-diff hk-diff--${side}`}>
        {diffWords(was, is)
          .filter((p) => p.kind === "same" || p.kind === (side === "before" ? "removed" : "added"))
          .map((p, i) =>
            p.kind === "same" ? (
              <span key={i}>{p.text}</span>
            ) : side === "before" ? (
              <del key={i} className="hk-diff__removed">
                {p.text}
              </del>
            ) : (
              <ins key={i} className="hk-diff__added">
                {p.text}
              </ins>
            )
          )}
      </span>
    );
  }
  return <span className={changed ? `hk-diff hk-diff--${side}` : "hk-diff"}>{value}</span>;
}

/**
 * Each setting the change names, current and proposed, in two columns; a
 * setting added, removed or amended is tagged, and unchanged settings fold
 * away when there are several.
 */
export function SettingsDiff({
  before,
  after,
  labels,
  pending
}: {
  before: Record<string, unknown>;
  after: Record<string, unknown>;
  labels?: Record<string, string>;
  pending: boolean;
}) {
  const fields = labels && Object.keys(labels).length ? Object.keys(labels) : [...new Set([...Object.keys(before), ...Object.keys(after)])];
  const rows = fields.map((field) => {
    const was = field in before ? text(before[field], field) : undefined;
    const is = field in after ? text(after[field], field) : undefined;
    const change: ChangeKind = was === is ? undefined : was === undefined ? "added" : is === undefined ? "removed" : "amended";
    return { field, label: labels?.[field] ?? keyWords(field), was, is, change };
  });
  const changed = rows.filter((r) => r.change);
  const same = rows.filter((r) => !r.change);
  const fold = same.length > 1;
  const shown = fold ? changed : rows;
  const [beforeName, afterName] = pending ? ["In force", "Proposed"] : ["Before", "After"];
  const answer = !changed.length
    ? "No setting changes."
    : `${pending ? "Changes" : "Changed"} ${changed.length === rows.length && rows.length > 1 ? `all ${rows.length} settings` : listNames(changed.map((r) => r.label))}.`;
  return (
    <div className="hk-stack hk-stack--tight" data-testid="change-diff">
      <p className="hk-lead" data-testid="change-answer">
        {answer}
      </p>
      {shown.length ? (
        <table className="hk-table hk-compare">
          <thead>
            <tr>
              <th scope="col">Setting</th>
              <th scope="col">{beforeName}</th>
              <th scope="col">{afterName}</th>
              <th scope="col">
                <span className="hk-sr-only">Change</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.field} data-testid="change-row" data-change={r.change ?? "none"}>
                <th scope="row">{r.label}</th>
                <td data-testid="diff-before">
                  <DiffValue side="before" was={r.was} is={r.is} />
                </td>
                <td data-testid="diff-after">
                  <DiffValue side="after" was={r.was} is={r.is} />
                </td>
                <td className="hk-compare__mark">
                  {r.change ? <Chip tone={CHANGE_TAG[r.change].tone} icon={false}>{CHANGE_TAG[r.change].word}</Chip> : <span className="hk-muted">Unchanged</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {fold ? (
        <details className="hk-fold">
          <summary>
            {same.length} unchanged settings: {listNames(same.map((r) => r.label))}
          </summary>
          <Facts facts={same.map((r) => ({ label: r.label, value: r.was }))} />
        </details>
      ) : null}
    </div>
  );
}

export function ChangeView({ detail, pending }: { detail: ChangeDetail; pending: boolean }) {
  const facts: Fact[] = [
    { label: "Change", value: capital(readable(detail.change).toLowerCase()) },
    { label: "System", value: readable(detail.system) },
    { label: "Reference", value: <code className="hk-code">{detail.ref}</code> }
  ];
  if (detail.proposedBy) facts.push({ label: "Proposed by", value: detail.proposedBy });
  if (detail.builtBy) facts.push({ label: "Built by", value: detail.builtBy });
  if (detail.stage) facts.push({ label: "Stage", value: readable(detail.stage) });
  const blast = detail.blastRadius;
  const evidence = detail.evidence ?? [];
  return (
    <div className="hk-stack" data-testid="subject-change">
      <Facts facts={facts} testId="change-facts" />
      <SettingsDiff before={detail.before} after={detail.after} labels={detail.labels} pending={pending} />
      {blast && (blast.useCases?.length || blast.trafficShare !== undefined) ? (
        <div className="hk-stack hk-stack--tight" data-testid="change-blast">
          <h3 className="hk-subheading">Who it reaches</h3>
          <div className="hk-blast">
            {blast.trafficShare !== undefined ? (
              <div className="hk-blast__share">
                <span className="hk-blast__figure">{formatPercent(blast.trafficShare)}</span>
                <span className="hk-muted">of traffic</span>
                <Meter value={blast.trafficShare} tone="brand" label={`${formatPercent(blast.trafficShare)} of traffic`} />
              </div>
            ) : null}
            {blast.useCases?.length ? (
              <div className="hk-blast__cases">
                <span className="hk-muted">Use cases</span>
                <span className="hk-inline-list">
                  {blast.useCases.map((u) => (
                    <Tag key={u}>{readable(u)}</Tag>
                  ))}
                </span>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
      {evidence.length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-subheading">Evidence</h3>
          <ul className="hk-evidence" data-testid="change-evidence">
            {evidence.map((e) => (
              <li key={`${e.kind}-${e.ref}`}>
                <span className="hk-evidence__kind">{readable(e.kind)}</span>
                <span>{e.summary ?? ""}</span>
                <code className="hk-code hk-muted">{e.ref}</code>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- records

type Agreement = "agree" | "differ";

export function RecordsView({ detail }: { detail: RecordsDetail }) {
  const { left, right } = detail;
  const agree = new Set(detail.agree ?? []);
  const differ = new Set(detail.differ ?? []);
  const fields = [
    ...new Set([
      ...Object.keys(detail.labels ?? {}),
      ...Object.keys(left.fields),
      ...Object.keys(right.fields)
    ])
  ];
  const rows = fields.map((field) => {
    const a = left.fields[field];
    const b = right.fields[field];
    const said: Agreement | undefined = differ.has(field) ? "differ" : agree.has(field) ? "agree" : undefined;
    const by: Agreement = said ?? (JSON.stringify(a) === JSON.stringify(b) ? "agree" : "differ");
    return { field, label: detail.labels?.[field] ?? keyWords(field), a, b, by };
  });
  const differing = rows.filter((r) => r.by === "differ");
  const sideHead = (side: typeof left) => (
    <span className="hk-record-head">
      <span className="hk-record-head__source">{readable(side.source)}</span>
      <code className="hk-code">{side.ref}</code>
      {side.loadedBy ? <span className="hk-record-head__by">loaded by {side.loadedBy}</span> : null}
    </span>
  );
  return (
    <div className="hk-stack" data-testid="subject-records">
      <div className="hk-records-summary">
        <p className="hk-lead">
          Two {readable(detail.entityType).toLowerCase()} records: {rows.length - differing.length} of {rows.length} fields agree
          {differing.length ? `; ${listNames(differing.map((r) => r.label))} ${differing.length === 1 ? "differs" : "differ"}.` : "."}
        </p>
        {detail.score !== undefined ? (
          <div className="hk-records-score" data-testid="records-score">
            <span className="hk-blast__figure">{formatPercent(detail.score)}</span>
            <span className="hk-muted">match{detail.method ? ` by ${detail.method}` : ""}</span>
            <Meter value={detail.score} tone="accent" label={`Match score ${formatPercent(detail.score)}`} />
          </div>
        ) : null}
      </div>
      <table className="hk-table hk-records" data-testid="records-table">
        <thead>
          <tr>
            <th scope="col">Field</th>
            <th scope="col">{sideHead(left)}</th>
            <th scope="col">{sideHead(right)}</th>
            <th scope="col">
              <span className="hk-sr-only">Agreement</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.field} className={`hk-records__row--${r.by}`} data-testid="records-row" data-agreement={r.by}>
              <th scope="row">{r.label}</th>
              <td>{r.a === undefined ? <span className="hk-muted">Not given</span> : text(r.a, r.field)}</td>
              <td>{r.b === undefined ? <span className="hk-muted">Not given</span> : text(r.b, r.field)}</td>
              <td className="hk-compare__mark">
                {r.by === "agree" ? <Chip tone="success">Agree</Chip> : <Chip tone="warning">Differ</Chip>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
