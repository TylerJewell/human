// The oversight contract, version 2, as TypeScript: the JSON `/oversight/*` and
// `/api/companion/human/*` serve (spec/oversight.schema.json, spec/CONTRACT.md).
//
// Enumerations are typed as the contract names them. A newer service may send a
// value this kit does not know; the parser lets it through and every component
// has a fallback for it, so these unions describe what is understood, not what
// is guaranteed.

export type Instant = string;
export type Duration = string;

export type PatternId =
  | "action-approval"
  | "decision-review"
  | "change-authorization"
  | "exception"
  | "match-adjudication"
  | "outcome-reconciliation"
  | "contest-appeal"
  | "override"
  | "flag-feedback"
  | "triage"
  | "hand-off"
  | "halt-restart"
  | "attestation"
  | "oversight-quality";

export type Action =
  | "APPROVE"
  | "DENY"
  | "EDIT"
  | "REDIRECT"
  | "REPLAN"
  | "ESCALATE"
  | "HALT"
  | "RECUSE"
  | "REQUEST_MORE_INFO"
  | "SEND_BACK"
  | "DECLINE_SYSTEM"
  | "OVERRIDE"
  | "DISREGARD"
  | "REVERSE";

export type Sticky =
  | "APPROVE_ONCE"
  | "APPROVE_FOR_SESSION"
  | "APPROVE_FOR_PATTERN"
  | "DENY_FOR_PATTERN";

export type SubjectKind =
  | "action"
  | "behaviour"
  | "change"
  | "system"
  | "output"
  | "records"
  | "appeal"
  | "exception"
  | "conversation"
  | "attestation";

export type Flavor =
  | "human-domain-decision"
  | "human-system-authorization"
  | "human-monitoring"
  | "human-feedback"
  | "human-halt";

export type Mode = "HITL" | "HOTL" | "HALT";

export type State =
  | "DETECTED"
  | "OPEN"
  | "CLAIMED"
  | "IN_REVIEW"
  | "AWAITING_SECOND"
  | "ESCALATED"
  | "CHANGES_REQUESTED"
  | "DECIDED"
  | "APPLIED"
  | "TIMED_OUT"
  | "ABANDONED"
  | "KILLED"
  | "WITHDRAWN"
  | "CLOSED"
  | "REOPENED";

export type ReviewMode = "SINGLE" | "TWO_PERSON" | "N_OF_M" | "CONSENSUS";
export type TimeoutBehaviour = "RAISE_TIMEOUT" | "RESUME_WITH_DEFAULT" | "ESCALATE" | "ABANDON";
export type SlaState = "running" | "at-risk" | "overdue" | "paused" | "met" | "missed" | "none";
export type SlaOutcome = "COMPLETED" | "TIMED_OUT" | "ESCALATED" | "ABANDONED" | "KILLED";
export type Conclusion = "upheld" | "breached" | "inconclusive" | "error";
export type VerdictSource = "runtime" | "simulated" | "rehearsal";
export type Strength = "prevent" | "warn" | "record";
export type CheckSection = "trigger" | "subject" | "evidence" | "blast" | "judge";

export interface Reviewer {
  subject: string;
  name?: string;
  roles: string[];
  identityProvider?: string;
  rehearsal?: boolean;
}

export interface ControlRef {
  rowId: string;
  name: string;
  corpusRef?: string | null;
  revision?: number | null;
}

// ---------------------------------------------------------------- subject details

export interface ActionDetail {
  tool: string;
  arguments: Record<string, unknown>;
  authority?: { missing?: string[]; required?: Record<string, unknown> };
  recentSteps?: Record<string, unknown>[];
  sentAt?: Instant;
  requestedBy?: string;
  lastKnown?: string;
}

export interface BehaviourDetail {
  metric: string;
  unit?: string;
  window?: Duration;
  comparison?: "<" | "<=" | ">" | ">=" | "==" | "!=";
  threshold?: number;
  baseline?: number;
  value: number;
  series?: { at: Instant; value: number }[];
  contributors?: { kind: string; ref: string; weight?: number }[];
}

export interface ChangeDetail {
  system: string;
  change: string;
  ref: string;
  proposedBy?: string;
  builtBy?: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
  labels?: Record<string, string>;
  evidence?: { kind: string; ref: string; summary?: string }[];
  blastRadius?: { useCases?: string[]; trafficShare?: number };
  stage?: string;
}

export interface Check {
  name: string;
  passed: boolean | null;
}

export interface SystemDetail {
  scope: "system" | "service" | "capability";
  target: string;
  affectedCalls: string;
  callsPerMinute?: number;
  safeStateChecks: Check[];
  halted: boolean;
  lift?: boolean;
}

export interface OutputDetail {
  output: string;
  replacement?: string | null;
  flag?: { by?: string; note?: string } | null;
  flowId?: string | null;
  decision?: { ref?: string; summary?: string; affects?: string } | null;
}

export interface RecordSide {
  source: string;
  ref: string;
  loadedBy?: string;
  fields: Record<string, unknown>;
}

export interface RecordsDetail {
  entityType: string;
  left: RecordSide;
  right: RecordSide;
  labels?: Record<string, string>;
  agree?: string[];
  differ?: string[];
  score?: number;
  method?: string;
}

export interface AppealDetail {
  original: { ref: string; decision: string; summary: string; decidedBy?: string; decidedAt?: Instant };
  appellant?: { ref?: string; name?: string };
  grounds: string;
  right?: string;
  filedAt?: Instant;
  excluded?: string[];
  attachments?: { kind: string; ref: string }[];
}

export interface ExceptionDetail {
  rule: { id: string; text: string };
  scope: string;
  requestedBy?: string;
  justification?: string;
  from?: Instant;
  until: Instant;
  compensating?: string[];
  grantor?: string;
}

export interface TranscriptTurn {
  at?: Instant;
  role: "user" | "agent" | "person" | "system";
  text: string;
}

export interface ConversationDetail {
  channel: string;
  reason: "user-asked" | "crisis" | "step-failed" | "policy";
  transcript: TranscriptTurn[];
  run?: { workflowId?: string; step?: string; error?: string } | null;
  takenOverBy?: string | null;
}

export interface AttestationEvidence {
  kind: string;
  ref: string;
  summary?: string;
  asOf?: Instant;
  stale?: boolean;
}

export interface AttestationDetail {
  attests: "control" | "model" | "framework";
  target: { ref: string; name: string };
  cadence?: Duration;
  periodEnd?: Instant;
  lastAttestedAt?: Instant | null;
  evidence: AttestationEvidence[];
}

/** Each subject kind's detail, by kind. */
export interface SubjectDetails {
  action: ActionDetail;
  behaviour: BehaviourDetail;
  change: ChangeDetail;
  system: SystemDetail;
  output: OutputDetail;
  records: RecordsDetail;
  appeal: AppealDetail;
  exception: ExceptionDetail;
  conversation: ConversationDetail;
  attestation: AttestationDetail;
}

export interface Subject {
  kind: SubjectKind;
  summary: string;
  service?: string | null;
  agent?: string | null;
  component?: string | null;
  sessionId?: string | null;
  flowId?: string | null;
  traceId?: string | null;
  workflowId?: string | null;
  ref?: string | null;
  /** Shaped by `kind` (SubjectDetails); read it through `readSubject`. */
  detail: Record<string, unknown>;
}

// ---------------------------------------------------------------- the event

export interface Trigger {
  detector: string;
  rule?: string | null;
  reason?: string | null;
  values?: { name: string; value?: unknown; limit?: unknown }[];
  conditions?: { name: string; met: boolean }[];
  detectedAt: Instant;
  occurrences?: number;
}

export interface AskCheck {
  text: string;
  section?: CheckSection;
}

export interface Ask {
  question: string;
  checks: AskCheck[];
}

export interface Routing {
  queue: string;
  roles: string[];
  groups?: string[];
  attestations?: string[];
  reviewMode: ReviewMode;
  required: number;
  of?: number | null;
  splitVote?: "DENY" | "ESCALATE";
  independence?: string[];
  independentOf?: string[];
}

export interface Holder {
  subject: string;
  roles?: string[];
  since: Instant;
  expiresAt: Instant;
}

export interface Sla {
  target?: Duration | null;
  startedAt?: Instant;
  dueAt?: Instant | null;
  atRiskAt?: number;
  state: SlaState;
  timeoutBehaviour?: TimeoutBehaviour;
  default?: Action | null;
  lateDecision?: "ACCEPT_BREACHED" | "REFUSE";
  clockWhileSentBack?: "RUNS" | "PAUSED";
}

export interface Edits {
  summary: string;
  patch: { op: string; path: string; value?: unknown }[];
}

export interface Decision {
  reviewer: Reviewer;
  action: Action;
  rationale?: string;
  qualified?: boolean;
  independent?: boolean;
  edits?: Edits | null;
  sticky?: Sticky | null;
  presentedAt?: Instant;
  submittedAt: Instant;
  reviewSeconds?: number;
  belowMinimum?: boolean;
  late?: boolean;
  checks?: string[];
}

/** A condition an approval carries: what must hold, by when, and whether it was met. */
export interface Condition {
  text: string;
  dueAt?: Instant | null;
  met?: boolean | null;
}

export interface DecisionRequest {
  reviewer: Reviewer;
  action: Action;
  rationale?: string;
  edits?: Edits | null;
  sticky?: Sticky | null;
  presentedAt?: Instant;
  checks?: string[];
  /** OVERRIDE: the output that replaces the system's. */
  replacement?: string | null;
  /** APPROVE or EDIT: conditions the approval carries; they appear in the outcome. */
  conditions?: Condition[];
}

/** A decision as a console sends it; the reviewer is stamped by the client or the companion. */
export type DecisionInput = Omit<DecisionRequest, "reviewer">;

export interface Outcome {
  action: Action | null;
  sticky?: Sticky | null;
  agreedWithAi?: boolean | null;
  edits?: Edits | null;
  conditions?: Condition[];
  standingDecisionId?: string | null;
  slaOutcome: SlaOutcome;
  appliedAt?: Instant | null;
  applied?: string | null;
  applyError?: string | null;
}

export interface Verdict {
  id: string;
  eventId: string;
  control?: ControlRef;
  pattern?: PatternId;
  subjectKind?: SubjectKind;
  mechanism: "human";
  boundary: { kind: "work-item"; ref: string };
  conclusion: Conclusion;
  reasons?: string[];
  source: { kind: VerdictSource; service?: string };
  action?: Action | null;
  deciders?: string[];
  appliedAt?: Instant | null;
  at: Instant;
}

export interface HistoryStep {
  at: Instant;
  state: State;
  actor: string;
  note: string;
}

/** A judge agent's recommendation, shown beside the decision panel. */
export interface AiRecommendation {
  agent?: string;
  action: Action;
  score?: number | null;
  rationale?: string;
  at?: Instant;
}

export interface SlaExtension {
  by: string;
  at: Instant;
  extra: Duration;
  reason: string;
}

/** What the control and the project's specs say about the system: its intended use and limitations. */
export interface EventContext {
  intendedUse?: string;
  limitations?: string[];
}

/**
 * The service's clock as an event or row reports it. `now` is the service's time
 * when it answered; `clockOffset` is how far that clock is ahead of this
 * browser's, in milliseconds, set by the parser when the answer is read.
 */
export interface ServiceClock {
  now?: Instant;
  clockOffset?: number;
}

export interface HumanEvent extends ServiceClock {
  id: string;
  state: State;
  pending: boolean;
  shadow?: boolean;
  strength?: Strength;
  pattern: PatternId;
  patternName: string;
  control: ControlRef;
  flavor: Flavor;
  mode: Mode;
  blocking: boolean;
  subject: Subject;
  trigger: Trigger;
  ask: Ask;
  environment?: string;
  owner: { kind: "service" | "optimize" | "runtime" | "ledger"; service: string };
  proposedBy?: string | null;
  builtBy?: string | null;
  routing: Routing;
  accountableOwner?: string | null;
  holder?: Holder | null;
  claimTtl?: Duration;
  sla: Sla;
  escalation?: { level: number; chain: string[] };
  allowedActions: Action[];
  actionLabels: Record<string, string>;
  effects?: Record<string, string>;
  sticky?: Sticky[];
  rationaleRequired?: boolean;
  minimumReviewSeconds?: number;
  minimumReview?: "WARN" | "ENFORCE";
  slaExtensions?: SlaExtension[];
  aiRecommendation?: AiRecommendation | null;
  context?: EventContext | null;
  presentedAt?: Instant | null;
  claimedAt?: Instant | null;
  detectedAt?: Instant;
  closedAt?: Instant | null;
  decisions: Decision[];
  infoRequests?: { by: string; at: Instant; question: string }[];
  recused?: string[];
  outcome?: Outcome | null;
  verdictId?: string | null;
  conclusion?: Conclusion | null;
  verdict?: Verdict | null;
  related?: { eventId: string; relation: Relation }[];
  history: HistoryStep[];
  evidence?: { kind: string; ref: string; [key: string]: unknown }[];
  resumes?: { kind?: "workflow" | "task" | "handler" | "none"; target?: string; id?: string } | null;
  simulated?: boolean;
}

export interface HumanEventRow extends ServiceClock {
  id: string;
  state: State;
  pending: boolean;
  pattern: PatternId;
  patternName?: string;
  control: ControlRef;
  flavor: Flavor;
  mode: Mode;
  blocking: boolean;
  /** `ref` is the record the subject is about, in the service's terms, so a service can match a row to its own record. */
  subject: { kind: SubjectKind; summary: string; service?: string | null; agent?: string | null; ref?: string | null };
  ask: { question: string };
  queue: string;
  reviewers: { required: number; voted: number };
  holder?: Holder | null;
  sla: { state: SlaState; dueAt?: string | null; target?: string | null; startedAt?: string; atRiskAt?: number };
  aiRecommendation?: AiRecommendation | null;
  shadow?: boolean;
  detectedAt: Instant;
  closedAt?: string | null;
  outcome?: Partial<Outcome> | null;
  decidedBy?: string[];
  conclusion?: Conclusion | null;
  simulated?: boolean;
  owner?: { service?: string };
}

export interface EventsPage {
  events: HumanEvent[];
  total: number;
}

export interface CompanionEventsPage {
  rows: HumanEventRow[];
  total: number;
  next?: string | null;
}

// ---------------------------------------------------------------- the rest of the contract

export interface LoopFigures {
  pending: number;
  overdue: number;
  raised7d?: number[];
}

export interface Halt {
  scope: "system" | "service" | "capability";
  target: string;
  control: ControlRef;
  affectedCalls?: string;
  halted: boolean;
  since?: Instant | null;
  by?: string | null;
  eventId?: string | null;
  liftEventId?: string | null;
  callsPerMinute?: number | null;
  safeStateChecks: Check[];
  service?: string;
}

export interface Summary {
  loop: { in: LoopFigures; on: LoopFigures; over: LoopFigures };
  states: Record<string, number>;
  patterns: Record<string, number>;
  atRisk: number;
  overdue: number;
  halts: Halt[];
  raised30d?: number[];
  standing: { active: number; expiring: number };
  unownedControls?: number;
}

export interface QualityReviewTimes {
  control: string;
  name?: string;
  minimumSeconds?: number;
  buckets: { upTo: number | null; count: number }[];
  belowMinimumShare?: number;
}

export interface QualityReviewer {
  subject: string;
  name?: string;
  decisions: number;
  approvalRate?: number;
  overrideRate?: number;
  agreementWithAi?: number | null;
  medianReviewSeconds?: number | null;
  open?: number;
  claimed?: number;
  oldestClaimSeconds?: number | null;
  share?: number;
}

export interface QualitySignal {
  control: string;
  signal: string;
  value: number;
  limit?: number;
  eventId?: string | null;
}

export interface Quality {
  window: Duration;
  reviewTimes: QualityReviewTimes[];
  reviewers: QualityReviewer[];
  outcomes: Record<string, number>;
  weekly?: Record<string, unknown>[];
  heatmap?: Record<string, unknown>[];
  coverage?: { triggers?: number; reachedPerson?: number };
  agreement?: { twoPersonEvents?: number; agreed?: number };
  signals?: QualitySignal[];
}

export interface Overseer {
  subject: string;
  name?: string;
  roles: string[];
  groups?: string[];
  reportsTo?: string | null;
  authority?: { controls: string[]; actions?: Action[] }[];
  attestations?: { id: string; completedAt: string; expiresAt?: string | null; expired?: boolean }[];
  qualified: boolean;
  qualifiedReason?: string | null;
}

export interface OverseerRegistry {
  overseers: Overseer[];
  owners?: { control: string; accountable: string }[];
}

/** How a related event stands to this one: a name from the contract, or a service's own that starts with x-. */
export type Relation = "reopens" | "reopened-as" | "reraised-after" | "held-again-after" | "proposed-by" | `x-${string}`;

export interface StandingDecision {
  id: string;
  /** The name of the service that holds the decision, as GET /oversight/info reports it. */
  service: string;
  kind: "pattern-approval" | "pattern-denial" | "session-approval" | "conditional-approval" | "change" | "exception";
  eventId: string;
  control: ControlRef;
  matchKey?: string | null;
  allows: string;
  grantedBy: string;
  grantedAt: Instant;
  expiresAt?: Instant | null;
  state: "ACTIVE" | "EXPIRING" | "EXPIRED" | "REVOKED";
  uses?: number;
  conditions?: Record<string, unknown>[];
  revoked?: { by?: string; at?: Instant; reason?: string } | null;
}

export interface ControlSettings {
  control: ControlRef;
  pattern: PatternId;
  flavor: Flavor;
  subjectKind: SubjectKind;
  blocking?: boolean;
  mode?: Mode;
  allowedActions: Action[];
  actionLabels?: Record<string, string>;
  routing: Routing;
  accountableOwner?: string | null;
  slaTarget?: Duration | null;
  atRiskAt?: number;
  claimTtl?: Duration;
  timeoutBehaviour?: TimeoutBehaviour;
  timeoutDefault?: Action | null;
  escalationChain?: { queue: string; roles?: string[]; after?: Duration | null }[];
  sticky?: Sticky[];
  rationaleRequired?: boolean;
  minimumReviewSeconds?: number;
  minimumReview?: "WARN" | "ENFORCE";
  clockWhileSentBack?: "RUNS" | "PAUSED";
  lateDecision?: "ACCEPT_BREACHED" | "REFUSE";
  aiRecommendation?: string | null;
  proofEvent?: string;
  shadow?: boolean;
  strength?: Record<string, Strength>;
  halt?: Record<string, unknown> | null;
}

export interface ServiceInfo {
  service: string;
  contract: number;
  library?: string;
  patterns: PatternId[];
  controls?: number;
  devRoutes: boolean;
  environment?: string;
}

/** One service the companion knows: `GET /api/companion/human/services`. */
export interface CompanionService {
  service: string;
  url: string;
  up: boolean;
  info?: ServiceInfo | null;
}

export interface RaiseRequest {
  control: string;
  ask: Ask;
  subject: Subject;
  trigger?: Record<string, unknown>;
  proposedBy?: string | null;
  builtBy?: string | null;
  firstReviewer?: string | null;
  dedupeKey?: string | null;
  matchKey?: string | null;
  aiRecommendation?: Record<string, unknown> | null;
  effects?: Record<string, string>;
  evidence?: Record<string, unknown>[];
  related?: Record<string, unknown>[];
  resumes?: Record<string, unknown> | null;
  simulated?: boolean;
}

export interface ProofRecord {
  type: "HumanOversightDecisionRecordedV2" | "HaltExercised" | "HaltLifted";
  recordId: string;
  recordedAt: Instant;
  service?: string;
  payload: Record<string, unknown>;
}

export interface ErrorBody {
  error: string;
  state?: string;
  errors?: string[];
}
