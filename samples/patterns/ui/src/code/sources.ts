import type { SourceRef } from "./extract";

// Every piece of code the tour shows, by key. Paths are from the repository
// root. The build cuts each one out of its file; a key whose file or anchor no
// longer exists fails the build, so the tour cannot show code that has gone.

const SAMPLE = "samples/patterns/src/main/java/io/akka/human/samples/patterns/";
const APP = SAMPLE + "application/";
const API = SAMPLE + "api/";
const SETUP = SAMPLE + "PatternsSetup.java";
const CLIENT = "java/human-akka/src/main/java/io/akka/human/akka/HumanClient.java";
const OUTCOMES = "java/human-akka/src/main/java/io/akka/human/akka/HumanOutcomes.java";
const CONF = "samples/patterns/src/main/resources/application.conf";
const CONTROLS = "spec/fixtures/controls-patterns.yaml";

const java = (file: string, method: string): SourceRef => ({ file, lang: "java", method });
const call = (file: string, needle: string): SourceRef => ({ file, lang: "java", call: needle });
const control = (id: string): SourceRef => ({ file: CONTROLS, lang: "yaml", yamlItem: id });

export const SOURCES = {
  "payment.ask": java(APP + "PaymentWorkflow.java", "private StepEffect askApproval()"),
  "payment.pay": java(APP + "PaymentWorkflow.java", "private StepEffect pay()"),
  "payment.decided": java(APP + "PaymentWorkflow.java", "public Effect<Done> decided(Decision decision)"),
  "payment.resumer": call(SETUP, ".resumer(PaymentWorkflow.RESUMER"),
  "agent.task": java(API + "ScenariosEndpoint.java", "public AgentApproval agentApproval(AgentApprovalRequest request)"),
  "agent.toolCall": java(API + "ScenariosEndpoint.java", "public ToolCallDecision toolCall(ToolCall call)"),
  "agent.settle": java(OUTCOMES, "private String settleTask(String taskId, Decision decision)"),
  "agent.guardrail": { file: CONF, lang: "hocon", block: '"payment approval" {' },
  "notice.ask": java(APP + "NoticeWorkflow.java", "private StepEffect askReview()"),
  "notice.decided": java(APP + "NoticeWorkflow.java", "public Effect<Done> decided(Decision decision)"),
  "notice.resumer": call(SETUP, ".resumer(NoticeWorkflow.RESUMER"),
  "rollout.ask": java(APP + "RolloutWorkflow.java", "private StepEffect askAuthorization()"),
  "rollout.decided": java(APP + "RolloutWorkflow.java", "public Effect<Done> decided(Decision decision)"),
  "handoff.ask": java(APP + "HandOffWorkflow.java", "private StepEffect handOff()"),
  "handoff.decided": java(APP + "HandOffWorkflow.java", "public Effect<Done> decided(Decision decision)"),
  "waiver.raise": java(API + "ScenariosEndpoint.java", "public Raised waiver(WaiverRequest request)"),
  "waiver.handler": call(SETUP, '.handler("waiver"'),
  "match.raise": java(API + "ScenariosEndpoint.java", "public Raised match(MatchRequest request)"),
  "match.handler": call(SETUP, '.handler("match"'),
  "records.handler": java(SETUP, "private static ApplyHandler record("),
  "contest.route": java(API + "ScenariosEndpoint.java", "public HttpResponse contest(String ref, ContestRequest request)"),
  "contest.library": java(CLIENT, "public HumanEventEntity.Answer contest("),
  "contest.handler": call(SETUP, '.handler("contest-appeal"'),
  "override.route": java(API + "ScenariosEndpoint.java", "public HttpResponse override(String ref, OverrideRequest request)"),
  "override.library": java(CLIENT, "public HumanEventEntity.Answer override("),
  "override.handler": call(SETUP, '.handler("override"'),
  "eligibility.assess": java(API + "EligibilityEndpoint.java", "public HttpResponse assess(Assess request)"),
  "flag.route": java(API + "ScenariosEndpoint.java", "public HttpResponse flag(String flowId, FlagRequest request)"),
  "flag.library": java(CLIENT, "public HumanEventEntity.Answer flag("),
  "flag.handler": call(SETUP, '.handler("flag-feedback"'),
  "triage.monitor": java(APP + "ReferralMonitor.java", "public Effect check(Reading reading)"),
  "triage.handler": call(SETUP, '.handler("triage"'),
  "attestation.schedule": java(APP + "AttestationSchedule.java", "public Effect run()"),
  "attestation.handler": call(SETUP, '.handler("attestation"'),
  "quality.monitor": java(APP + "QualityMonitor.java", "public Effect check()"),
  "quality.handler": call(SETUP, '.handler("oversight-quality"'),
  "halt.library": java(CLIENT, "public HumanEventEntity.Answer halt("),
  "halt.guardrails": { file: CONF, lang: "hocon", block: '"eligibility halt (tools)" {' },
  "halt.scope": call(SETUP, ".haltScope(ELIGIBILITY"),
  "control.pt-00001": control("pt-00001"),
  "control.pt-00002": control("pt-00002"),
  "control.pt-00003": control("pt-00003"),
  "control.pt-00004": control("pt-00004"),
  "control.pt-00005": control("pt-00005"),
  "control.pt-00006": control("pt-00006"),
  "control.pt-00007": control("pt-00007"),
  "control.pt-00008": control("pt-00008"),
  "control.pt-00009": control("pt-00009"),
  "control.pt-00010": control("pt-00010"),
  "control.pt-00011": control("pt-00011"),
  "control.pt-00012": control("pt-00012"),
  "control.pt-00013": control("pt-00013"),
  "control.pt-00014": control("pt-00014")
} satisfies Record<string, SourceRef>;

export type SourceKey = keyof typeof SOURCES;
