package io.akka.human.akka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.akka.human.api.Action;
import io.akka.human.api.Ask;
import io.akka.human.api.Pattern;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Resume;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Sticky;
import io.akka.human.api.Subject;
import io.akka.human.api.SubjectKind;
import io.akka.human.api.Trigger;
import io.akka.human.engine.Rules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract's request bodies, read into the API's types. A body that does not fit throws
 * {@link IllegalArgumentException}, which the endpoint answers with 422.
 */
final class Wire {

  /** Writes every field, nulls included, as the contract's examples show them. */
  static final ObjectMapper MAPPER = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.ALWAYS)
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

  private Wire() {}

  static JsonNode parse(byte[] body) {
    if (body == null || body.length == 0) return NullNode.getInstance();
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("the body is not JSON: " + e.getMessage());
    }
  }

  static byte[] write(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Reviewer reviewer(JsonNode body) {
    var r = body.path("reviewer");
    if (!r.isObject() || text(r, "subject") == null) throw new IllegalArgumentException("reviewer.subject is required");
    return new Reviewer(text(r, "subject"), text(r, "name"), strings(r.path("roles")), text(r, "identityProvider"),
        r.path("rehearsal").asBoolean(false));
  }

  static Rules.DecisionRequest decision(JsonNode body, Reviewer reviewer) {
    var action = action(text(body, "action"));
    if (action == null) throw new IllegalArgumentException("action is required");
    var presented = text(body, "presentedAt");
    return new Rules.DecisionRequest(reviewer, action, text(body, "rationale"), map(body.path("edits")),
        sticky(text(body, "sticky")), presented == null ? null : instant(presented, "presentedAt"),
        strings(body.path("checks")), text(body, "replacement"), maps(body.path("conditions")));
  }

  private static List<Map<String, Object>> maps(JsonNode array) {
    var out = new ArrayList<Map<String, Object>>();
    for (var item : array) {
      if (!item.isObject()) throw new IllegalArgumentException("each condition is an object with its text");
      out.add(map(item));
    }
    return out;
  }

  static RaiseRequest raise(JsonNode body) {
    var control = text(body, "control");
    if (control == null) throw new IllegalArgumentException("control is required");
    var askNode = body.path("ask");
    if (text(askNode, "question") == null) throw new IllegalArgumentException("ask.question is required");
    var checks = new ArrayList<Ask.Check>();
    for (var c : askNode.path("checks")) checks.add(new Ask.Check(text(c, "text"), text(c, "section")));
    var s = body.path("subject");
    if (!s.isObject()) throw new IllegalArgumentException("subject is required");
    var kind = SubjectKind.of(text(s, "kind"));
    if (text(s, "summary") == null) throw new IllegalArgumentException("subject.summary is required");
    var subject = new Subject(kind, text(s, "summary"), text(s, "service"), text(s, "agent"), text(s, "component"),
        text(s, "sessionId"), text(s, "flowId"), text(s, "traceId"), text(s, "workflowId"), text(s, "ref"),
        map(s.path("detail")));
    var effects = new LinkedHashMap<Action, String>();
    body.path("effects").fields().forEachRemaining(f -> effects.put(action(f.getKey()), f.getValue().asText()));
    var resumes = body.path("resumes");
    return new RaiseRequest(
        text(body, "pattern") == null ? null : Pattern.of(text(body, "pattern")),
        control,
        new Ask(text(askNode, "question"), checks),
        subject,
        trigger(body.path("trigger")),
        text(body, "proposedBy"),
        text(body, "builtBy"),
        text(body, "firstReviewer"),
        text(body, "dedupeKey"),
        text(body, "matchKey"),
        map(body.path("aiRecommendation")),
        effects,
        stringMaps(body.path("evidence")),
        stringMaps(body.path("related")),
        resumes.isObject() ? new Resume(text(resumes, "kind"), text(resumes, "target"), text(resumes, "id")) : null,
        body.path("simulated").asBoolean(false));
  }

  static Trigger trigger(JsonNode t) {
    if (!t.isObject()) return null;
    var values = new ArrayList<Trigger.Value>();
    for (var v : t.path("values")) {
      values.add(new Trigger.Value(text(v, "name"), MAPPER.convertValue(v.path("value"), Object.class),
          MAPPER.convertValue(v.path("limit"), Object.class)));
    }
    var conditions = new ArrayList<Trigger.Condition>();
    for (var c : t.path("conditions")) conditions.add(new Trigger.Condition(text(c, "name"), c.path("met").asBoolean()));
    return new Trigger(text(t, "detector"), text(t, "rule"), text(t, "reason"), values, conditions);
  }

  static String text(JsonNode node, String field) {
    var value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  static Action action(String name) {
    if (name == null) return null;
    try {
      return Action.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("not a decision: " + name);
    }
  }

  static Instant instant(String text, String field) {
    try {
      return Instant.parse(text);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(field + " must be an ISO 8601 instant");
    }
  }

  private static Sticky sticky(String name) {
    if (name == null) return null;
    try {
      return Sticky.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("not a sticky mode: " + name);
    }
  }

  private static List<String> strings(JsonNode array) {
    var out = new ArrayList<String>();
    for (var item : array) out.add(item.asText());
    return out;
  }

  private static Map<String, Object> map(JsonNode node) {
    return node.isObject() ? MAPPER.convertValue(node, MAP) : null;
  }

  private static List<Map<String, String>> stringMaps(JsonNode array) {
    var out = new ArrayList<Map<String, String>>();
    for (var item : array) {
      var m = new LinkedHashMap<String, String>();
      item.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText()));
      out.add(m);
    }
    return out;
  }
}
