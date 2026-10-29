package io.akka.human.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a service raises. The library fills in the rest from the control's settings: routing,
 * deadline, allowed decisions and their labels.
 *
 * @param pattern the pattern the caller expects the control to run; null accepts the control's own.
 *     A mismatch is refused, so a call site cannot drift from the bundle unnoticed.
 * @param dedupeKey raising again with the same key adds an occurrence to the pending event
 * @param matchKey what a standing decision (APPROVE_FOR_PATTERN, DENY_FOR_PATTERN) is matched on
 * @param firstReviewer contest-appeal: who made the decision under appeal, barred from deciding
 */
public record RaiseRequest(
    Pattern pattern,
    String control,
    Ask ask,
    Subject subject,
    Trigger trigger,
    String proposedBy,
    String builtBy,
    String firstReviewer,
    String dedupeKey,
    String matchKey,
    Map<String, Object> aiRecommendation,
    Map<Action, String> effects,
    List<Map<String, String>> evidence,
    List<Map<String, String>> related,
    Resume resumes,
    boolean simulated) {

  public RaiseRequest {
    if (control == null || control.isBlank()) throw new IllegalArgumentException("an event names its control");
    if (ask == null) throw new IllegalArgumentException("an event needs a question");
    if (subject == null) throw new IllegalArgumentException("an event needs a subject");
    effects = effects == null ? Map.of() : Map.copyOf(effects);
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    related = related == null ? List.of() : List.copyOf(related);
    resumes = resumes == null ? Resume.none() : resumes;
  }

  public static Builder of(Pattern pattern, String question) {
    return new Builder(pattern, question);
  }

  public static final class Builder {
    private final Pattern pattern;
    private final String question;
    private final List<Ask.Check> checks = new ArrayList<>();
    private String control;
    private Subject subject;
    private Trigger trigger;
    private String proposedBy;
    private String builtBy;
    private String firstReviewer;
    private String dedupeKey;
    private String matchKey;
    private Map<String, Object> aiRecommendation;
    private final Map<Action, String> effects = new LinkedHashMap<>();
    private final List<Map<String, String>> evidence = new ArrayList<>();
    private final List<Map<String, String>> related = new ArrayList<>();
    private Resume resumes = Resume.none();
    private boolean simulated;

    private Builder(Pattern pattern, String question) {
      this.pattern = pattern;
      this.question = question;
    }

    public Builder control(String controlId) {
      this.control = controlId;
      return this;
    }

    public Builder subject(Subject subject) {
      this.subject = subject;
      return this;
    }

    public Builder check(String text, String section) {
      checks.add(new Ask.Check(text, section));
      return this;
    }

    public Builder trigger(Trigger trigger) {
      this.trigger = trigger;
      return this;
    }

    public Builder proposedBy(String subject) {
      this.proposedBy = subject;
      return this;
    }

    public Builder builtBy(String subject) {
      this.builtBy = subject;
      return this;
    }

    public Builder firstReviewer(String subject) {
      this.firstReviewer = subject;
      return this;
    }

    public Builder dedupeKey(String key) {
      this.dedupeKey = key;
      return this;
    }

    public Builder matchKey(String key) {
      this.matchKey = key;
      return this;
    }

    public Builder aiRecommendation(String agent, Action action, double score, String rationale) {
      this.aiRecommendation = Subject.fields("agent", agent, "action", action.name(), "score", score,
          "rationale", rationale);
      return this;
    }

    /** What happens next if the person takes this action, shown beside the button. */
    public Builder effect(Action action, String text) {
      effects.put(action, text);
      return this;
    }

    public Builder evidence(String kind, String ref) {
      evidence.add(Map.of("kind", kind, "ref", ref));
      return this;
    }

    public Builder related(String eventId, String relation) {
      related.add(Map.of("eventId", eventId, "relation", relation));
      return this;
    }

    public Builder resumes(Resume resume) {
      this.resumes = resume;
      return this;
    }

    public Builder simulated(boolean simulated) {
      this.simulated = simulated;
      return this;
    }

    public RaiseRequest build() {
      return new RaiseRequest(pattern, control, new Ask(question, checks), subject, trigger, proposedBy, builtBy,
          firstReviewer, dedupeKey, matchKey, aiRecommendation, effects, evidence, related, resumes, simulated);
    }
  }
}
