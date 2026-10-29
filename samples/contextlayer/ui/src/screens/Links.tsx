import { DecisionPanel, ErrorLine, useEvent, useRegistry } from "@akka-human/kit";
import { useLinks } from "../api";
import { fieldRows, linkTitle, loadersOf, sortLinks, type LinkView, type Member } from "../model";
import { Chip, Head, Icon, Loading } from "../parts/ui";

// Links: records from different sources that an identity rule matched, waiting
// for a reviewer to say whether they are one person or thing. Each link shows
// its two records side by side, what agrees and what differs, who loaded them,
// and the decision (Same or Different, with a reason) right beside them.

const STATUS: Record<LinkView["status"], [Parameters<typeof Chip>[0]["tone"], string]> = {
  SINGLE: ["mute", "One record"],
  PENDING: ["info", "Waiting"],
  CONFIRMED: ["ok", "Same"],
  REJECTED: ["mute", "Different"]
};

export function Links({ id }: { id?: string }) {
  const links = useLinks();
  const registry = useRegistry().data;
  const nameOf = (s: string) => registry?.overseers.find((o) => o.subject === s)?.name ?? s;
  const all = links.data ?? [];
  const { toReview, decided, single } = sortLinks(all);
  const selected = (id ? all.find((l) => l.clusterId === id) : undefined) ?? toReview[0];

  return (
    <div className="cl-page">
      <Head
        title="Links"
        sub="A link says records from different sources are the same person or thing. Records that share a match key are only a possibility until a reviewer decides."
      />
      {links.error ? (
        <div className="hk">
          <ErrorLine error={links.error} />
        </div>
      ) : null}
      {links.data === undefined && !links.error ? (
        <Loading what="the links" />
      ) : (
        <div className="cl-listgrid">
          <nav className="cl-rail" aria-label="Links">
            <div className="cl-rail__lbl">To review · {toReview.length}</div>
            {toReview.length === 0 ? <p className="cl-rail__none">Nothing waits for a review.</p> : null}
            {toReview.map((l) => (
              <a key={l.clusterId} href={`#/links/${l.clusterId}`} className={l === selected ? "on" : undefined} data-testid="link-item">
                <div className="cl-rail__ti">{linkTitle(l)}</div>
                <div className="cl-rail__mt">
                  <span>
                    {l.left?.source} {l.left?.ref}
                  </span>
                  <span className="cl-faint">and</span>
                  <span>
                    {l.right?.source} {l.right?.ref}
                  </span>
                </div>
              </a>
            ))}
            <div className="cl-rail__lbl cl-rail__lbl--gap">Decided · {decided.length}</div>
            {decided.map((l) => (
              <a key={l.clusterId} href={`#/links/${l.clusterId}`} className={l === selected ? "on" : undefined}>
                <div className="cl-rail__ti cl-rail__ti--plain">
                  {linkTitle(l)} <Chip tone={STATUS[l.status][0]}>{STATUS[l.status][1]}</Chip>
                </div>
                <div className="cl-rail__mt">
                  {l.reviewedBy ? nameOf(l.reviewedBy) : "—"} · {l.left?.source} {l.left?.ref} and {l.right?.source} {l.right?.ref}
                </div>
              </a>
            ))}
            {single ? (
              <p className="cl-rail__none">
                {single} {single === 1 ? "key holds" : "keys hold"} one record so far.
              </p>
            ) : null}
          </nav>
          <div>{selected ? <LinkDetail key={selected.clusterId} link={selected} nameOf={nameOf} refresh={links.refresh} /> : <p className="cl-empty">No link is waiting for a review.</p>}</div>
        </div>
      )}
    </div>
  );
}

function RecordCard({ member, side, rows, nameOf }: { member: Member; side: "left" | "right"; rows: ReturnType<typeof fieldRows>; nameOf: (s: string) => string }) {
  return (
    <div className="cl-rec" data-testid="link-record">
      <div className="cl-rec__src">
        <Icon name="sources" size={14} />
        {member.source}
        <span className="cl-faint">· loaded by {nameOf(member.loadedBy)}</span>
      </div>
      <div className="cl-rec__id cl-mono">{member.ref}</div>
      {rows.map((row) => (
        <div key={row.field} className={`cl-rec__f ${row.same ? "same" : "diff"}`} data-agreement={row.same ? "same" : "different"}>
          <span>{row.field}</span>
          <span>{row[side] ?? <span className="cl-faint">not given</span>}</span>
        </div>
      ))}
    </div>
  );
}

function LinkDetail({ link, nameOf, refresh }: { link: LinkView; nameOf: (s: string) => string; refresh: () => Promise<void> }) {
  const event = useEvent(link.eventId ?? undefined).data;
  const rows = fieldRows(link);
  const differ = rows.filter((r) => !r.same).map((r) => r.field);
  const loaders = loadersOf(link).map(nameOf);
  const [tone, word] = STATUS[link.status];

  return (
    <>
      <div className="cl-card cl-card--gap">
        <div className="cl-split">
          <span className="cl-q">{event?.ask.question ?? "Are these the same?"}</span>
          <span className="cl-split__r">
            <Chip tone={tone}>{word}</Chip>
          </span>
        </div>
        <p className="cl-muted cl-mt6">
          They match on the rule <b className="cl-strong">{link.rule}</b>: {rows.length - differ.length} of {rows.length} fields agree
          {differ.length ? `, ${differ.join(", ")} ${differ.length === 1 ? "differs" : "differ"}` : ""}.{" "}
          {link.status === "CONFIRMED"
            ? `${link.reviewedBy ? nameOf(link.reviewedBy) : "A reviewer"} said they are the same, so they are treated as one.`
            : link.status === "REJECTED"
              ? `${link.reviewedBy ? nameOf(link.reviewedBy) : "A reviewer"} said they are different, so they stay apart.`
              : "They stay apart until a reviewer says they are the same."}
        </p>
      </div>
      {link.left && link.right ? (
        <div className="cl-cmp">
          <RecordCard member={link.left} side="left" rows={rows} nameOf={nameOf} />
          <RecordCard member={link.right} side="right" rows={rows} nameOf={nameOf} />
        </div>
      ) : null}
      <div className="cl-linkdecide">
        <p className="cl-indep">
          <Icon name="shield" size={14} />
          <span>
            {loaders.join(" and ")} loaded these records, so {loaders.length > 1 ? "neither" : "they"} may decide. Link Review ({event?.control.rowId ?? "custom-cl0004"}).
          </span>
        </p>
        {event ? <DecisionPanel event={event} onWrite={() => void refresh()} /> : <Loading what="the review" />}
      </div>
    </>
  );
}
