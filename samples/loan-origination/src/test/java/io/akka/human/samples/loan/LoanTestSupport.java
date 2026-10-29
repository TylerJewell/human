package io.akka.human.samples.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.samples.loan.application.ApplicationEntity;
import io.akka.human.samples.loan.application.UnderwritingWorkflow;
import io.akka.human.samples.loan.domain.LoanApplication;
import io.akka.human.testkit.HumanTestKit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;

/** The service under test, the people of its overseer registry, and helpers to drive applications. */
abstract class LoanTestSupport extends TestKitSupport {

  static final ObjectMapper MAPPER = new ObjectMapper();
  static final Duration WAIT = Duration.ofSeconds(20);

  static final Reviewer TYLER = HumanTestKit.reviewer("tyler.jewell@akka.io", "ai-system-owner", "loan-system-owner",
      "head-of-credit");
  static final Reviewer DEVOS = HumanTestKit.reviewer("a.devos@example.com", "model-risk-owner", "risk-officer");
  static final Reviewer CHEN = HumanTestKit.reviewer("r.chen@example.com", "compliance-lead", "oversight-officer",
      "adverse-action-reviewer", "underwriting-counsel");
  static final Reviewer PATEL = HumanTestKit.reviewer("s.patel@example.com", "oversight-officer", "safety-officer");
  static final Reviewer LIND = HumanTestKit.reviewer("j.lind@example.com", "head-of-credit", "loan-system-owner",
      "senior-underwriter", "underwriter");
  static final Reviewer OKAFOR = HumanTestKit.reviewer("m.okafor@example.com", "underwriting-counsel",
      "adverse-action-reviewer", "senior-underwriter", "underwriter");
  static final Reviewer MOREAU = HumanTestKit.reviewer("l.moreau@example.com", "safety-officer", "underwriter");
  static final Reviewer OSEI = HumanTestKit.reviewer("k.osei@example.com", "risk-officer", "underwriter");

  /** Approved by the rule alone: debt-to-income about 10%, no defaults. */
  static final Map<String, Object> CLEAN = Map.of("applicant", "Anna", "nationalId", "NAT-1", "amount", 10_000,
      "termMonths", 60, "monthlyIncome", 4_000, "monthlyDebts", 200);
  /** Referred: debt-to-income about 36%, above what the rule approves alone. */
  static final Map<String, Object> BORDERLINE = Map.of("applicant", "Ben", "nationalId", "NAT-2", "amount", 20_000,
      "termMonths", 48, "monthlyIncome", 3_000, "monthlyDebts", 600);
  /** The assessor recommends declining: debt-to-income about 60%. */
  static final Map<String, Object> STRETCHED = Map.of("applicant", "Cleo", "nationalId", "NAT-3", "amount", 30_000,
      "termMonths", 36, "monthlyIncome", 2_500, "monthlyDebts", 600);

  HumanTestKit kit;

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig("""
        human.register = false
        loan-origination.monitors.interval = 0s
        loan-origination.monitors.referral-minimum = 5
        loan-origination.halt.max-referral-backlog = 0
        """);
  }

  @BeforeEach
  public void kit() {
    kit = new HumanTestKit(componentClient, getDependency(HumanPlugins.class));
  }

  // ---- applications

  String submit(Map<String, Object> application) {
    var reply = post("/applications", application);
    assertEquals(200, reply.status(), reply.body()::toString);
    return reply.body().get("id").asText();
  }

  /** Submits the application and attaches both documents, which starts underwriting. */
  String apply(Map<String, Object> application) {
    var id = submit(application);
    post("/applications/" + id + "/documents", Map.of("type", "PAYSLIP", "reference", "dms://payslip"));
    post("/applications/" + id + "/documents", Map.of("type", "BANK_STATEMENT", "reference", "dms://statement"));
    return id;
  }

  /** The application's underwriting review, once it waits for an underwriter. */
  String awaitReview(String id) {
    return Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forWorkflow(id).method(UnderwritingWorkflow::get).invoke(),
            u -> u.status().equals("AWAITING_REVIEW")).eventId();
  }

  LoanApplication awaitApplication(String id, Predicate<LoanApplication> condition) {
    return Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forKeyValueEntity(id).method(ApplicationEntity::get).invoke(), condition::test);
  }

  // ---- http

  record Reply(int status, JsonNode body) {}

  static Map<String, Object> decision(Reviewer reviewer, String action, String rationale) {
    return Map.of("reviewer", Map.of("subject", reviewer.subject(), "roles", reviewer.roles()), "action", action,
        "rationale", rationale);
  }

  Reply get(String path) {
    var response = httpClient.GET(path).invoke();
    return new Reply(response.status().intValue(), parse(response.body().toArray()));
  }

  Reply post(String path, Object body) {
    try {
      var response = httpClient.POST(path).withRequestBody(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsBytes(body))
          .invoke();
      return new Reply(response.status().intValue(), parse(response.body().toArray()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode parse(byte[] bytes) {
    try {
      return bytes.length == 0 || (bytes[0] != '{' && bytes[0] != '[') ? MAPPER.nullNode() : MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
