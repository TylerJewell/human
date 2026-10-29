import { readSubject } from "../parse";
import type { HumanEvent, Reviewer } from "../types";
import { Prose, RawJson } from "../components/ui";
import { actionLabel } from "../words";
import { AppealView, AttestationView, ConversationView, ExceptionView } from "./cases";
import { ChangeView, RecordsView } from "./comparisons";
import { ActionView, BehaviourView, OutputView, SystemView } from "./operations";

/** The heading of the subject's section, by kind: what happened, or what would change. */
export function subjectTitle(event: Pick<HumanEvent, "subject" | "pending">): string {
  switch (event.subject.kind) {
    case "change":
      return event.pending ? "What would change" : "The change";
    case "records":
      return "The two records";
    case "appeal":
      return "The appeal";
    case "exception":
      return "The exception asked for";
    case "conversation":
      return "The conversation";
    case "attestation":
      return "What is attested";
    case "system":
      return "The scope";
    case "output":
      return "The output";
    case "behaviour":
      return "What was seen";
    default:
      return "What happened";
  }
}

/** The summary and the raw detail, for a subject this kit cannot draw by its kind. */
export function SubjectFallback({ summary, reason, detail }: { summary: string; reason: string; detail: unknown }) {
  return (
    <div className="hk-stack" data-testid="subject-fallback">
      <Prose heading="Summary">{summary || "No summary was given."}</Prose>
      <p className="hk-note" data-testid="subject-fallback-reason">
        {reason}
      </p>
      <details className="hk-fold" open>
        <summary>Raw detail</summary>
        <RawJson value={detail} label="Raw detail" testId="subject-raw" />
      </details>
    </div>
  );
}

/**
 * The subject of `event`, drawn by its kind. A kind this kit does not know, or
 * a detail that does not match its kind, shows the summary and the raw detail.
 * `onAction` lets a view start a decision, as a conversation's take-over does.
 */
export function SubjectView({
  event,
  now = Date.now(),
  reviewer,
  onAction
}: {
  event: HumanEvent;
  now?: number;
  reviewer?: Reviewer | null;
  onAction?: (action: string) => void;
}) {
  const reading = readSubject(event.subject);
  switch (reading.kind) {
    case "action":
      return <ActionView subject={event.subject} detail={reading.detail} />;
    case "behaviour":
      return <BehaviourView detail={reading.detail} />;
    case "change":
      return <ChangeView detail={reading.detail} pending={event.pending} />;
    case "system":
      return <SystemView detail={reading.detail} />;
    case "output":
      return <OutputView detail={reading.detail} />;
    case "records":
      return <RecordsView detail={reading.detail} />;
    case "appeal":
      return <AppealView detail={reading.detail} reviewer={reviewer} />;
    case "exception":
      return <ExceptionView detail={reading.detail} now={now} />;
    case "conversation":
      return (
        <ConversationView
          detail={reading.detail}
          canTakeOver={event.pending && event.allowedActions.includes("DECLINE_SYSTEM")}
          takeOverLabel={actionLabel("DECLINE_SYSTEM", event.actionLabels)}
          onTakeOver={onAction ? () => onAction("DECLINE_SYSTEM") : undefined}
        />
      );
    case "attestation":
      return <AttestationView detail={reading.detail} now={now} />;
    default:
      return <SubjectFallback summary={event.subject.summary} reason={reading.reason} detail={reading.detail} />;
  }
}
