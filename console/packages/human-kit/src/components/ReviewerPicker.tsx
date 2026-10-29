import { useEffect, useId, useState } from "react";
import { useRegistry, useReviewer, useSetReviewer } from "../hooks";
import type { Reviewer } from "../types";
import { readable } from "../words";
import { cx, ErrorLine } from "./ui";

// Who writes are made as: a person from the overseer registry and the roles
// they act in. Through the companion the choice is its local reviewer, which
// it stamps on every write; through a service it is the client's own.

export function ReviewerPicker({ onChosen, className }: { onChosen?: (reviewer: Reviewer) => void; className?: string }) {
  const registry = useRegistry();
  const current = useReviewer();
  const set = useSetReviewer();
  const [subject, setSubject] = useState("");
  const [roles, setRoles] = useState<string[]>([]);
  const id = useId();
  useEffect(() => {
    if (current.data) {
      setSubject(current.data.subject);
      setRoles(current.data.roles);
    }
  }, [current.data]);
  const people = registry.data?.overseers ?? [];
  const person = people.find((p) => p.subject === subject);
  const choose = (s: string) => {
    setSubject(s);
    setRoles(people.find((p) => p.subject === s)?.roles ?? []);
  };
  const act = () => {
    if (!person) return;
    const reviewer: Reviewer = { subject: person.subject, name: person.name, roles };
    set.run(reviewer).then(
      () => onChosen?.(reviewer),
      () => undefined
    );
  };
  const changed = current.data?.subject !== subject || JSON.stringify(current.data?.roles ?? []) !== JSON.stringify(roles);
  return (
    <div className={cx("hk hk-reviewer", className)} data-testid="reviewer-picker">
      <label className="hk-reviewer__who" htmlFor={id}>
        <span className="hk-band">Acting as</span>
        <select id={id} className="hk-input hk-input--sm" value={subject} onChange={(e) => choose(e.target.value)} data-testid="reviewer-person">
          <option value="">{registry.data ? "Choose a person" : "Reading the registry…"}</option>
          {people.map((p) => (
            <option key={p.subject} value={p.subject}>
              {p.name ?? p.subject}
              {p.qualified ? "" : " (not qualified)"}
            </option>
          ))}
        </select>
      </label>
      {person ? (
        <fieldset className="hk-reviewer__roles">
          <legend className="hk-sr-only">Roles</legend>
          {person.roles.map((r) => (
            <label key={r} className="hk-check">
              <input
                type="checkbox"
                checked={roles.includes(r)}
                onChange={() => setRoles(roles.includes(r) ? roles.filter((x) => x !== r) : [...roles, r])}
              />
              {readable(r)}
            </label>
          ))}
        </fieldset>
      ) : null}
      <button
        type="button"
        className="hk-btn hk-btn--primary hk-btn--sm"
        disabled={!person || !changed || set.pending}
        onClick={act}
        data-testid="reviewer-act"
      >
        {current.data ? "Switch" : "Act as"}
      </button>
      <ErrorLine error={set.error ?? registry.error} />
    </div>
  );
}
