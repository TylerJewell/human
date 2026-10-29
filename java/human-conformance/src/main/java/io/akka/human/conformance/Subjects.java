package io.akka.human.conformance;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** A minimal valid subject of each kind, for raises through the dev route. */
final class Subjects {
  private Subjects() {}

  static ObjectNode sample(String kind) {
    ObjectNode subject = Json.object();
    subject.put("kind", kind);
    subject.put("ref", "conformance-" + UUID.randomUUID().toString().substring(0, 8));
    ObjectNode d = subject.putObject("detail");
    switch (kind) {
      case "action" -> {
        subject.put("summary", "Pay supplier S-77 4,200 EUR");
        d.put("tool", "pay");
        d.putObject("arguments").put("supplier", "S-77").put("amount", 4200).put("currency", "EUR");
      }
      case "behaviour" -> {
        subject.put("summary", "Referral share fell to 18%");
        d.put("metric", "referral-share").put("unit", "%").put("window", "PT24H").put("comparison", "<")
            .put("threshold", 20).put("baseline", 35).put("value", 18);
      }
      case "change" -> {
        subject.put("summary", "Roll out eligibility model m-5");
        d.put("system", "model-registry").put("change", "rollout").put("ref", "rollout-12");
        d.putObject("before").put("model", "m-4");
        d.putObject("after").put("model", "m-5");
      }
      case "system" -> {
        subject.put("summary", "Halt eligibility assessment");
        d.put("scope", "capability").put("target", "eligibility-assessment")
            .put("affectedCalls", "all-runtime-operations").put("halted", false).put("lift", false);
        d.putArray("safeStateChecks");
      }
      case "output" -> {
        subject.put("summary", "Adverse notice to applicant A-77");
        d.put("output", "Your application is declined.");
      }
      case "records" -> {
        subject.put("summary", "Two customer records may be one person");
        d.put("entityType", "customer");
        side(d.putObject("left"), "crm", "c-1", "1990-02-01");
        side(d.putObject("right"), "claims", "k-9", "1990-01-02");
      }
      case "appeal" -> {
        subject.put("summary", "Applicant A-77 appeals a declined loan");
        d.putObject("original").put("ref", "app-77").put("decision", "DENY").put("summary", "Loan declined");
        d.put("grounds", "My income was misread.");
      }
      case "exception" -> {
        subject.put("summary", "Waive rule R-12 for one release");
        d.putObject("rule").put("id", "R-12").put("text", "No production deploys on Fridays");
        d.put("scope", "service billing").put("until", "2099-01-01T00:00:00Z");
      }
      case "conversation" -> {
        subject.put("summary", "A user asked for a person");
        d.put("channel", "chat").put("reason", "user-asked");
        ArrayNode transcript = d.putArray("transcript");
        transcript.addObject().put("role", "user").put("text", "I want to talk to a person.");
      }
      case "attestation" -> {
        subject.put("summary", "Quarterly attestation of the eligibility model");
        d.put("attests", "model");
        d.putObject("target").put("ref", "m-5").put("name", "Eligibility model");
        d.putArray("evidence");
      }
      default -> subject.put("summary", "Raised by the conformance suite");
    }
    return subject;
  }

  private static void side(ObjectNode side, String source, String ref, String dob) {
    side.put("source", source).put("ref", ref);
    side.putObject("fields").put("name", "Ana Ruiz").put("dob", dob);
  }
}
