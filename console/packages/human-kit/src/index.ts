// @akka-human/kit: the oversight contract's types, a tolerant parser, two
// clients, headless hooks, and the components every console draws an
// oversight event with. Styles ship separately as `@akka-human/kit/kit.css`.

export type * from "./types";

export {
  ContractError,
  isPendingState,
  isSubjectKind,
  parseEvent,
  parseRow,
  readSubject,
  rowOf,
  SUBJECT_KINDS,
  votesOf,
  type Parsed,
  type SubjectReading
} from "./parse";

export {
  createCompanionClient,
  createServiceClient,
  OversightError,
  type CompanionClient,
  type EventsList,
  type EventsQuery,
  type EventWrite,
  type OversightClient,
  type ServiceClient
} from "./client";

export {
  OversightProvider,
  QueryCache,
  useControls,
  useEvent,
  useEvents,
  useEventWrite,
  useHalts,
  useHaltWrite,
  useNow,
  useOversight,
  useQuality,
  useRead,
  useRegistry,
  useReviewer,
  useRevokeStanding,
  useSetReviewer,
  useStanding,
  useSummary,
  useOnSettled,
  type Read,
  type Write
} from "./hooks";

export {
  ACTION_LABEL,
  actionLabel,
  byClosed,
  byUrgency,
  formatDuration,
  formatMoment,
  formatSpan,
  parseDuration,
  PATTERN_NAME,
  patternName,
  PATTERNS,
  readable,
  readClock,
  serviceNow,
  stateLabel,
  subjectKindLabel,
  timeoutSentence,
  type ClockReading,
  type ClockTone
} from "./words";

export {
  afterDeadline,
  barredWord,
  deciders,
  decisionClock,
  formatCountdown,
  initialsOf,
  settleStage,
  type Decider,
  type DeciderWords,
  type DecisionClockReading
} from "./clock";

export { DecisionClock, type DecisionClockProps } from "./components/DecisionClock";
export { DecisionPanel, patchOf, rulesOf, votesText, type DecisionPanelProps, type Rules } from "./components/DecisionPanel";
export { EventPage, TriggerFacts, UnreadableEvent, type EventPageProps } from "./components/EventPage";
export { History, journeyOf, JourneyLine, JourneyView, type Journey, type JourneyStep } from "./components/History";
export { matches, ordered, Queue, type QueueFilters, type QueueProps } from "./components/Queue";
export { ReviewerPicker } from "./components/ReviewerPicker";
export {
  HaltsPanel,
  OverseersList,
  QualityView,
  ReviewHistogram,
  standingOrder,
  StandingList,
  SummaryTiles
} from "./components/governance";
export { ConclusionChip, ConditionList, DeadlineRing, OutcomeChip, outcomeOf, StateChip } from "./components/status";
export {
  Button,
  Chip,
  Confirm,
  errorSentence,
  ErrorLine,
  Facts,
  Field,
  Icon,
  Meter,
  Prose,
  RawJson,
  Section,
  Tag,
  type Fact,
  type Tone
} from "./components/ui";

export { SubjectFallback, SubjectView, subjectTitle } from "./subjects/SubjectView";
export { ActionView, BehaviourView, breaches, OutputView, SafeStateChecks, Sparkline, SystemView } from "./subjects/operations";
export { ChangeView, diffWords, RecordsView, SettingsDiff } from "./subjects/comparisons";
export { AppealView, AttestationView, ConversationView, ExceptionView } from "./subjects/cases";
