package io.akka.human.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the person judges. {@code detail} carries the fields of the kind's subject view, as the
 * contract names them (spec/CONTRACT.md, "Subject detail by kind"); the factories below fill the
 * required ones.
 */
public record Subject(
    SubjectKind kind,
    String summary,
    String service,
    String agent,
    String component,
    String sessionId,
    String flowId,
    String traceId,
    String workflowId,
    String ref,
    Map<String, Object> detail) {

  public Subject {
    if (kind == null) throw new IllegalArgumentException("a subject needs a kind");
    if (summary == null || summary.isBlank()) throw new IllegalArgumentException("a subject needs a summary");
    detail = detail == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail));
  }

  public static Subject of(SubjectKind kind, String summary, Map<String, Object> detail) {
    return new Subject(kind, summary, null, null, null, null, null, null, null, null, detail);
  }

  public static Subject action(String summary, String tool, Map<String, Object> arguments) {
    return of(SubjectKind.ACTION, summary, fields("tool", tool, "arguments", arguments));
  }

  public static Subject behaviour(String summary, String metric, double value, double threshold, String comparison) {
    return of(SubjectKind.BEHAVIOUR, summary,
        fields("metric", metric, "value", value, "threshold", threshold, "comparison", comparison));
  }

  public static Subject change(String summary, String system, String change, String ref,
      Map<String, Object> before, Map<String, Object> after) {
    return of(SubjectKind.CHANGE, summary,
        fields("system", system, "change", change, "ref", ref, "before", before, "after", after));
  }

  /** A halt or a restart of a scope; the halt registry fills the safe-state checks. */
  public static Subject system(String summary, String scope, String target, String affectedCalls, boolean lift) {
    return of(SubjectKind.SYSTEM, summary, fields("scope", scope, "target", target, "affectedCalls", affectedCalls,
        "safeStateChecks", List.of(), "halted", lift, "lift", lift));
  }

  public static Subject output(String summary, String output) {
    return of(SubjectKind.OUTPUT, summary, fields("output", output));
  }

  public static Subject records(String summary, String entityType, Map<String, Object> left, Map<String, Object> right) {
    return of(SubjectKind.RECORDS, summary, fields("entityType", entityType, "left", left, "right", right));
  }

  public static Subject appeal(String summary, Map<String, Object> original, String grounds) {
    return of(SubjectKind.APPEAL, summary, fields("original", original, "grounds", grounds));
  }

  public static Subject exception(String summary, String ruleId, String ruleText, String scope, Instant until) {
    return of(SubjectKind.EXCEPTION, summary,
        fields("rule", fields("id", ruleId, "text", ruleText), "scope", scope, "until", until.toString()));
  }

  public static Subject conversation(String summary, String channel, String reason, List<Map<String, Object>> transcript) {
    return of(SubjectKind.CONVERSATION, summary, fields("channel", channel, "reason", reason, "transcript", transcript));
  }

  public static Subject attestation(String summary, String attests, String targetRef, String targetName,
      List<Map<String, Object>> evidence) {
    return of(SubjectKind.ATTESTATION, summary, fields("attests", attests,
        "target", fields("ref", targetRef, "name", targetName), "evidence", evidence));
  }

  public Subject withService(String value) {
    return new Subject(kind, summary, value, agent, component, sessionId, flowId, traceId, workflowId, ref, detail);
  }

  public Subject withAgent(String value) {
    return new Subject(kind, summary, service, value, component, sessionId, flowId, traceId, workflowId, ref, detail);
  }

  public Subject withComponent(String value) {
    return new Subject(kind, summary, service, agent, value, sessionId, flowId, traceId, workflowId, ref, detail);
  }

  public Subject withSession(String value) {
    return new Subject(kind, summary, service, agent, component, value, flowId, traceId, workflowId, ref, detail);
  }

  public Subject withFlow(String value) {
    return new Subject(kind, summary, service, agent, component, sessionId, value, traceId, workflowId, ref, detail);
  }

  public Subject withWorkflow(String value) {
    return new Subject(kind, summary, service, agent, component, sessionId, flowId, traceId, value, ref, detail);
  }

  public Subject withRef(String value) {
    return new Subject(kind, summary, service, agent, component, sessionId, flowId, traceId, workflowId, value, detail);
  }

  /** A copy with one more detail field, or one replaced. */
  public Subject with(String field, Object value) {
    var next = new LinkedHashMap<>(detail);
    next.put(field, value);
    return new Subject(kind, summary, service, agent, component, sessionId, flowId, traceId, workflowId, ref, next);
  }

  /** An ordered map from alternating names and values; a null value leaves the name out. */
  public static Map<String, Object> fields(Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) throw new IllegalArgumentException("names and values must pair");
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      if (namesAndValues[i + 1] != null) map.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return map;
  }
}
