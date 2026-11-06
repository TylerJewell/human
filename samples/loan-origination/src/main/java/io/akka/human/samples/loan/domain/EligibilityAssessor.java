package io.akka.human.samples.loan.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the eligibility assessor agent: a recommendation, a risk score and the reasons, from
 * the financial facts and the credit file only, never from who the applicant is. The automatic
 * approval is not the assessor's to make: {@link #approvesAlone} is the deterministic rule that
 * decides whether the application needs an underwriter.
 */
public final class EligibilityAssessor {

  public static final String AGENT = "eligibility-assessor";
  /** The capability the halt of custom-d04d2229e3f9 stops. */
  public static final String CAPABILITY = "eligibility-assessment";

  static final double ANNUAL_RATE = 0.06;
  static final double MAX_DEBT_TO_INCOME = 0.40;
  static final double CLEAN_DEBT_TO_INCOME = 0.35;
  static final double DECLINE_DEBT_TO_INCOME = 0.50;
  static final double AUTOMATIC_RISK_LIMIT = 0.30;
  static final int AUTOMATIC_AMOUNT_LIMIT = 25_000;

  /** @param creditDefaults defaults in the credit file */
  public record Input(int amount, int termMonths, int monthlyIncome, int monthlyDebts, int creditDefaults) {}

  /** @param recommendation APPROVE, REFER or DECLINE */
  public record Recommendation(String recommendation, double riskScore, double debtToIncome, List<String> reasons,
      String rationale) {}

  private EligibilityAssessor() {}

  public static Recommendation assess(Input in) {
    double dti = debtToIncome(in);
    var reasons = new ArrayList<String>();
    if (dti > MAX_DEBT_TO_INCOME) reasons.add("DEBT_TO_INCOME_TOO_HIGH");
    if (in.creditDefaults() > 0) reasons.add("ADVERSE_CREDIT_HISTORY");
    var recommendation = dti > DECLINE_DEBT_TO_INCOME || in.creditDefaults() > 1 ? "DECLINE"
        : reasons.isEmpty() && dti <= CLEAN_DEBT_TO_INCOME ? "APPROVE" : "REFER";
    double risk = round(Math.min(1.0, dti + 0.3 * in.creditDefaults()));
    var rationale = "Debt-to-income " + Math.round(dti * 100) + "% after the new instalment of "
        + instalment(in.amount(), in.termMonths()) + "; " + (in.creditDefaults() == 0 ? "no recorded defaults"
        : in.creditDefaults() + " recorded default" + (in.creditDefaults() == 1 ? "" : "s")) + ".";
    return new Recommendation(recommendation, risk, round(dti), List.copyOf(reasons), rationale);
  }

  /** Only a low-risk approval within the amount the rule may grant goes ahead without a person. */
  public static boolean approvesAlone(Recommendation r, int amount) {
    return r.recommendation().equals("APPROVE") && r.riskScore() < AUTOMATIC_RISK_LIMIT && amount <= AUTOMATIC_AMOUNT_LIMIT;
  }

  public static double debtToIncome(Input in) {
    if (in.monthlyIncome() <= 0) return 1.0;
    return (in.monthlyDebts() + instalment(in.amount(), in.termMonths())) / (double) in.monthlyIncome();
  }

  /** The monthly repayment of an annuity loan at the policy rate. */
  public static int instalment(int amount, int termMonths) {
    double r = ANNUAL_RATE / 12;
    return (int) Math.round(amount * r / (1 - Math.pow(1 + r, -Math.max(1, termMonths))));
  }

  /** Stands in for the credit bureau: a national id starting with DEF has one recorded default. */
  public static int creditDefaults(String nationalId) {
    return nationalId != null && nationalId.startsWith("DEF") ? 1 : 0;
  }

  private static double round(double value) {
    return Math.round(value * 1000) / 1000.0;
  }
}
