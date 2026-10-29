package io.akka.human.samples.loan.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stands in for the notice writer agent and its protected-characteristics guardrail. The writer
 * explains the recorded decline reasons in plain words and repeats the underwriter's rationale; the
 * guardrail blocks any sentence that names a protected characteristic. A blocked sentence is never
 * kept: the draft shows only the category and language of the term.
 */
public final class NoticeWriter {

  public static final String AGENT = "notice-writer";
  public static final String FIRST_MODEL = "notice-writer-1";
  public static final String DEFAULT_TEMPLATE = "We are sorry: we cannot offer you the loan you applied for.";

  static final Map<String, String> EXPLANATIONS = Map.of(
      "DEBT_TO_INCOME_TOO_HIGH", "Your monthly debts, with the new loan's repayment, would take more than 40% of your income.",
      "ADVERSE_CREDIT_HISTORY", "Your credit file records a missed repayment on an earlier loan.",
      "OTHER", "Your application did not meet our lending policy.");
  static final String CONTEST = "You may contest this decision within 30 days; a person who did not make it will review it.";

  /** A sentence the guardrail blocked: what kind of term it named and in which language. */
  public record Block(String category, String language) {}

  /** @param text the draft as a reviewer may read it, blocked sentences withheld */
  public record Draft(String model, String text, List<Block> blocks) {
    public boolean blocked() {
      return !blocks.isEmpty();
    }
  }

  private static final Map<String, Pattern> PROTECTED = Map.of(
      "health", Pattern.compile("\\b(health|ill|illness|sick\\w*|disab\\w*|pregnan\\w*|diagnos\\w*|medical)\\b",
          Pattern.CASE_INSENSITIVE),
      "age", Pattern.compile("\\b(age|aged|elderly|too old|too young|retire\\w*)\\b", Pattern.CASE_INSENSITIVE),
      "marital status", Pattern.compile("\\b(married|divorced|marital|widow\\w*)\\b", Pattern.CASE_INSENSITIVE),
      "religion", Pattern.compile("\\b(religio\\w*|church|mosque|synagogue|temple)\\b", Pattern.CASE_INSENSITIVE),
      "ethnicity", Pattern.compile("\\b(race|racial|ethnic\\w*)\\b", Pattern.CASE_INSENSITIVE));

  private NoticeWriter() {}

  public static Draft draft(String model, String template, List<String> reasons, String rationale) {
    var sentences = new ArrayList<String>();
    sentences.add(template);
    reasons.forEach(r -> sentences.add(EXPLANATIONS.getOrDefault(r, EXPLANATIONS.get("OTHER"))));
    if (rationale != null && !rationale.isBlank()) sentences.add("Your underwriter noted: " + rationale.trim());
    sentences.add(CONTEST);
    var shown = new ArrayList<String>();
    var blocks = new ArrayList<Block>();
    for (var sentence : sentences) {
      var block = guardrail(sentence);
      block.ifPresent(blocks::add);
      shown.add(block.map(b -> "[withheld: a " + b.category() + " term, " + b.language() + "]").orElse(sentence));
    }
    return new Draft(model, String.join(" ", shown), List.copyOf(blocks));
  }

  /** The letter issued when no reviewed draft is: the template, the reasons and the right to contest. */
  public static String standardLetter(String template, List<String> reasons) {
    var sentences = new ArrayList<String>();
    sentences.add(template);
    reasons.forEach(r -> sentences.add(EXPLANATIONS.getOrDefault(r, EXPLANATIONS.get("OTHER"))));
    sentences.add(CONTEST);
    return String.join(" ", sentences);
  }

  /** The notice sent when a contest upholds the decline. */
  public static String contestNotice(String rationale) {
    return "We have reviewed your contest. The decision to decline your application stands. " + rationale.trim();
  }

  static Optional<Block> guardrail(String sentence) {
    return PROTECTED.entrySet().stream().filter(e -> e.getValue().matcher(sentence).find()).findFirst()
        .map(e -> new Block(e.getKey(), "en"));
  }
}
