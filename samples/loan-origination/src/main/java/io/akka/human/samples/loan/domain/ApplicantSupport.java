package io.akka.human.samples.loan.domain;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Stands in for the applicant support assistant and the deadline extension judge. The assistant
 * answers an applicant's questions about their application from its records, and calls its tool
 * {@code extendDocumentDeadline} when they ask for more days; up to {@link #DAYS_ALONE} days it may
 * grant alone. The judge recommends a decision on a longer extension.
 */
public final class ApplicantSupport {

  public static final String AGENT = "applicant-support";
  public static final String JUDGE = "deadline-extension-judge";
  public static final String TOOL = "extendDocumentDeadline";
  public static final int DAYS_ALONE = 14;

  private static final Pattern DAYS = Pattern.compile("(\\d{1,3})\\s*(more\\s+|extra\\s+)?days?", Pattern.CASE_INSENSITIVE);
  private static final Pattern GOOD_REASON = Pattern.compile("\\b(abroad|travel\\w*|hospital|ill|sick|away|waiting for|employer|bank)\\b",
      Pattern.CASE_INSENSITIVE);

  /** What the assistant does with a message: answer it, or call its extension tool first. */
  public sealed interface Turn {
    record Reply(String text) implements Turn {}

    record ExtendDeadline(int days, String reason) implements Turn {}
  }

  /** @param action APPROVE or DENY */
  public record Recommendation(String action, double score, String rationale) {}

  private ApplicantSupport() {}

  public static Turn answer(String message, LoanApplication app) {
    var days = DAYS.matcher(message);
    if (days.find() && app.status().equals(LoanApplication.DOCUMENTS_REQUESTED)) {
      return new Turn.ExtendDeadline(Integer.parseInt(days.group(1)), message.trim());
    }
    var lower = message.toLowerCase();
    if ((lower.contains("extension") || lower.contains("request")) && !app.extensions().isEmpty()) {
      var last = app.extensions().getLast();
      return new Turn.Reply(switch (last.status()) {
        case "APPLIED" -> "Your documents deadline was moved by " + last.daysApplied() + " days, to " + day(app) + "."
            + note(last);
        case "AWAITING_APPROVAL" -> "Your request for " + last.days() + " more days is still with the loan system owner.";
        default -> "Your request for " + last.days() + " more days was not granted." + note(last)
            + " Your deadline stays " + day(app) + ".";
      });
    }
    if (lower.contains("document")) {
      var missing = app.missingDocuments();
      return new Turn.Reply(missing.isEmpty() ? "We have every document we need."
          : "We still need: " + String.join(", ", missing).toLowerCase().replace('_', ' ') + ", by " + day(app) + ".");
    }
    return new Turn.Reply("Your application is " + app.status().toLowerCase().replace('_', ' ') + ".");
  }

  public static String extended(int days, LoanApplication app) {
    return "I have moved your documents deadline by " + days + " days, to " + day(app) + ".";
  }

  public static String waiting(int days, String requestId) {
    return "An extension of " + days + " days needs the loan system owner's decision. I have asked for it (request "
        + requestId + "); ask me about your extension later to hear the outcome.";
  }

  /** The judge: a stated reason that explains the wait supports up to 30 days. */
  public static Recommendation judge(int days, String reason) {
    boolean explained = reason != null && GOOD_REASON.matcher(reason).find();
    if (explained && days <= 30) {
      return new Recommendation("APPROVE", 0.8, "The applicant gives a reason that explains " + days + " days.");
    }
    return new Recommendation("DENY", 0.7, explained ? days + " days is longer than the reason explains."
        : "The applicant gives no reason for " + days + " more days.");
  }

  private static String day(LoanApplication app) {
    return LocalDate.ofInstant(app.documentsDue(), ZoneOffset.UTC).toString();
  }

  private static String note(LoanApplication.Extension e) {
    return e.note() == null || e.note().isBlank() ? "" : " The loan system owner said: " + e.note();
  }
}
