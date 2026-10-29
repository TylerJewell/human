import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { ContractError } from "../parse";
import { OversightError } from "../client";
import { MISSING, sentence } from "../words";

// The pieces every view is drawn with, in the Akka console's vocabulary: a
// section is a heading outside a white panel; facts are a ruled list of muted
// labels and values; a status is one word in its tone's tint; every write is
// confirmed in place, with what will happen and why it failed.

export const cx = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(" ");

// ---------------------------------------------------------------- icons

const PATHS: Record<string, ReactNode> = {
  tool: <path d="M10.5 2.5a3.5 3.5 0 0 0-3.3 4.6L2.6 11.7a1.4 1.4 0 0 0 2 2l4.6-4.6a3.5 3.5 0 0 0 4.4-4.4l-2 2-1.8-.4-.4-1.8 2-2a3.5 3.5 0 0 0-.9-.1Z" />,
  chart: <path d="M2 13.5h12M3.5 11l3-3.5 2.5 2 4-5" />,
  change: <path d="M3 5h8.5M9 2.5 11.5 5 9 7.5M13 11H4.5M7 8.5 4.5 11 7 13.5" />,
  system: <path d="M6 3.5v9M10 3.5v9" />,
  output: <path d="M3 3.5h10v7H8l-3 2.5v-2.5H3z" />,
  records: <path d="M2.5 3h4.5v10H2.5zM9 3h4.5v10H9zM4 6h1.5M10.5 6H12M4 9h1.5M10.5 9H12" />,
  appeal: <path d="M8 2.5v11M4 13.5h8M3 5.5h10M5 5.5 3 9.5h4zM11 5.5l-2 4h4z" />,
  exception: <path d="M8 2 13 4v4c0 3-2.2 5-5 6-2.8-1-5-3-5-6V4zM6 6.5l4 4M10 6.5l-4 4" />,
  conversation: <path d="M2.5 3.5h8v5.5H6l-2.5 2v-2h-1zM10.5 6h3v5.5h-1v2l-2.5-2H7" />,
  attestation: <path d="M8 1.8 9.6 3l2-.1.6 1.9 1.6 1.2-.6 1.9.6 1.9-1.6 1.2-.6 1.9-2-.1L8 14l-1.6-1.2-2 .1-.6-1.9-1.6-1.2.6-1.9-.6-1.9 1.6-1.2.6-1.9 2 .1zM5.8 8l1.5 1.5 3-3" />,
  unknown: <path d="M6 6a2 2 0 1 1 2.8 1.8c-.5.3-.8.7-.8 1.2v.5M8 11.5v.5" />,
  check: <path d="m3.5 8.5 3 3 6-7" />,
  cross: <path d="m4 4 8 8M12 4l-8 8" />,
  alert: <path d="M8 2.5 14 13H2zM8 6.5v3M8 11v.5" />,
  info: <path d="M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12ZM8 7.5V11M8 5v.5" />,
  clock: <path d="M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12ZM8 4.5V8l2.5 1.5" />,
  arrow: <path d="M3 8h10M9.5 4.5 13 8l-3.5 3.5" />,
  person: <path d="M8 7.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM3 13.5c.6-2.3 2.6-3.8 5-3.8s4.4 1.5 5 3.8" />,
  pause: <path d="M6 3.5v9M10 3.5v9" />,
  play: <path d="M5 3.5v9l7-4.5z" />
};

export type IconName = keyof typeof PATHS;

/** A 16-unit stroke icon, decorative unless given a label. */
export function Icon({ name, label, className }: { name: string; label?: string; className?: string }) {
  return (
    <svg
      viewBox="0 0 16 16"
      className={cx("hk-icon", className)}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      {PATHS[name] ?? PATHS.unknown}
    </svg>
  );
}

// ---------------------------------------------------------------- status

export type Tone = "default" | "accent" | "success" | "warning" | "danger" | "brand" | "quiet";

const TONE_ICON: Partial<Record<Tone, IconName>> = {
  danger: "alert",
  warning: "clock",
  success: "check",
  accent: "info"
};

/** A status in one word, in its tone's tint, with the tone's icon before it. */
export function Chip({
  tone = "default",
  children,
  title,
  icon = true,
  testId
}: {
  tone?: Tone;
  children: ReactNode;
  title?: string;
  icon?: boolean;
  testId?: string;
}) {
  const name = icon ? TONE_ICON[tone] : undefined;
  return (
    <span className={`hk-chip hk-chip--${tone}`} title={title} data-testid={testId} data-tone={tone}>
      {name ? <Icon name={name} className="hk-chip__icon" /> : null}
      {children}
    </span>
  );
}

/** A plain label for what something is, not how it stands: a subject kind, a pattern. */
export function Tag({ children, title, testId }: { children: ReactNode; title?: string; testId?: string }) {
  return (
    <span className="hk-tag" title={title} data-testid={testId}>
      {children}
    </span>
  );
}

// ---------------------------------------------------------------- layout

/** A block of a view: its heading outside a white panel, with its status and controls on the heading's row. */
export function Section({
  title,
  status,
  controls,
  testId,
  empty,
  flush = false,
  children
}: {
  title: ReactNode;
  status?: ReactNode;
  controls?: ReactNode;
  testId?: string;
  /** The one line shown in place of the content when there is nothing to show. */
  empty?: string;
  /** No padding inside the panel, for a table that runs to its edges. */
  flush?: boolean;
  children?: ReactNode;
}) {
  const id = useId();
  return (
    <section className="hk-section" aria-labelledby={id} data-testid={testId}>
      <div className="hk-section__head">
        <h2 id={id} className="hk-section__title">
          {title}
        </h2>
        {status}
        {controls ? <div className="hk-section__controls">{controls}</div> : null}
      </div>
      <div className={cx("hk-panel", flush && "hk-panel--flush")}>
        {empty !== undefined ? <p className="hk-muted">{empty}</p> : children}
      </div>
    </section>
  );
}

export interface Fact {
  label: ReactNode;
  value: ReactNode;
  testId?: string;
}

const shown = (value: ReactNode) => (value === null || value === undefined || value === "" ? MISSING : value);

/** Labelled facts as one ruled list: muted labels in a fixed column, values beside them. */
export function Facts({ facts, testId }: { facts: readonly Fact[]; testId?: string }) {
  return (
    <dl className="hk-facts" data-testid={testId}>
      {facts.map((fact, i) => (
        <div className="hk-facts__row" key={typeof fact.label === "string" ? `${fact.label}-${i}` : i} data-testid={fact.testId}>
          <dt className="hk-facts__label">{fact.label}</dt>
          <dd className="hk-facts__value">{shown(fact.value)}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Authored text in the prose colour, under its own sub-heading when it has one. */
export function Prose({ heading, children, testId }: { heading?: string; children: ReactNode; testId?: string }) {
  return (
    <div className="hk-prose" data-testid={testId}>
      {heading ? <h3 className="hk-prose__heading">{heading}</h3> : null}
      <div className="hk-prose__text">{children}</div>
    </div>
  );
}

/** A part of a whole as a bar: a share of 1, in a tone. */
export function Meter({
  value,
  tone = "default",
  label,
  testId
}: {
  value: number;
  tone?: Tone;
  label: string;
  testId?: string;
}) {
  const share = Math.max(0, Math.min(1, value));
  return (
    <span
      className={`hk-meter hk-meter--${tone}`}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(share * 100)}
      data-testid={testId}
    >
      <span className="hk-meter__fill" style={{ inlineSize: `${share * 100}%` }} />
    </span>
  );
}

/** A value laid out as JSON, for raw detail and arguments that are not plain text. */
export function RawJson({ value, label, testId }: { value: unknown; label?: string; testId?: string }) {
  return (
    <pre className="hk-raw" aria-label={label} tabIndex={0} data-testid={testId}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

// ---------------------------------------------------------------- controls

export function Button({
  variant = "secondary",
  size = "md",
  children,
  disabled,
  onClick,
  type = "button",
  testId,
  ...rest
}: {
  variant?: "primary" | "secondary" | "danger" | "ghost" | "link";
  size?: "sm" | "md";
  children: ReactNode;
  disabled?: boolean;
  onClick?: () => void;
  type?: "button" | "submit";
  testId?: string;
  "aria-describedby"?: string;
  "aria-label"?: string;
  title?: string;
}) {
  return (
    <button
      type={type}
      className={cx("hk-btn", `hk-btn--${variant}`, size === "sm" && "hk-btn--sm")}
      disabled={disabled}
      onClick={onClick}
      data-testid={testId}
      {...rest}
    >
      {children}
    </button>
  );
}

/** A labelled field's frame: its label above it and a hint under it. */
export function Field({
  label,
  hint,
  required,
  children
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: (id: string, describedBy?: string) => ReactNode;
}) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  return (
    <div className="hk-field">
      <label className="hk-field__label" htmlFor={id}>
        {label}
        {required ? <span className="hk-field__required"> (required)</span> : null}
      </label>
      {children(id, hintId)}
      {hint ? (
        <span id={hintId} className="hk-field__hint">
          {hint}
        </span>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- writes

/**
 * A refused or failed write in one sentence: the reviewer may not act (403),
 * the event has moved on (409), or what was wrong with the request (422).
 */
export function errorSentence(error: Error): string {
  if (error instanceof OversightError) {
    if (error.status === 403) return `You cannot do this: ${sentence(error.message)}`;
    if (error.status === 409) {
      return `The event has moved on: ${sentence(error.message)}${error.state ? ` It is ${error.state.toLowerCase().replaceAll("_", " ")} now.` : ""}`;
    }
    if (error.status === 422) {
      return `The request was refused: ${sentence(error.errors.length ? error.errors.join("; ") : error.message)}`;
    }
    if (error.status === 404) return `Not found: ${sentence(error.message)}`;
  }
  if (error instanceof ContractError) return sentence(error.message);
  return sentence(error.message || "Something went wrong");
}

/** A failed write, said once, where it was asked for. */
export function ErrorLine({ error, testId = "hk-error" }: { error: Error | undefined; testId?: string }) {
  if (!error) return null;
  return (
    <p className="hk-error" role="alert" data-testid={testId} data-status={error instanceof OversightError ? error.status : undefined}>
      <Icon name="alert" className="hk-error__icon" />
      {errorSentence(error)}
    </p>
  );
}

/**
 * Where a write is confirmed: what will happen, the fields it takes, and,
 * when it fails, why. `problem` is what is still missing, said once the
 * person tries to confirm. Escape cancels; focus starts inside and returns to
 * where it came from when the box closes.
 */
export function Confirm({
  title,
  changes,
  verb,
  danger = false,
  pending,
  problem,
  error,
  onConfirm,
  onCancel,
  children
}: {
  title: string;
  changes: ReactNode;
  verb: string;
  danger?: boolean;
  pending: boolean;
  problem?: string;
  error?: Error;
  onConfirm: () => void;
  onCancel: () => void;
  children?: ReactNode;
}) {
  const id = useId();
  const box = useRef<HTMLElement>(null);
  const [tried, setTried] = useState(false);
  useEffect(() => {
    const from = box.current?.ownerDocument.activeElement as HTMLElement | null;
    const first = box.current?.querySelector<HTMLElement>("textarea, input, select");
    (first ?? box.current)?.focus();
    return () => {
      if (from && from.isConnected) from.focus();
    };
  }, []);
  return (
    <section
      ref={box}
      className={cx("hk-confirm", danger && "hk-confirm--danger")}
      role="group"
      aria-labelledby={id}
      tabIndex={-1}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.stopPropagation();
          onCancel();
        }
      }}
      data-testid="hk-confirm"
    >
      <h4 id={id} className="hk-confirm__title">
        {title}
      </h4>
      <div className="hk-confirm__changes" data-testid="hk-confirm-changes">
        {changes}
      </div>
      {children}
      {tried && problem ? (
        <p className="hk-problem" role="alert" data-testid="hk-confirm-problem">
          {problem}
        </p>
      ) : null}
      <ErrorLine error={error} testId="hk-confirm-error" />
      <div className="hk-confirm__buttons">
        <Button variant="ghost" onClick={onCancel} testId="hk-confirm-cancel">
          Cancel
        </Button>
        <Button
          variant={danger ? "danger" : "primary"}
          disabled={pending}
          onClick={() => {
            setTried(true);
            if (!problem) onConfirm();
          }}
          testId="hk-confirm-run"
        >
          {pending ? "Sending…" : verb}
        </Button>
      </div>
    </section>
  );
}
