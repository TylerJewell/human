package io.akka.human.api;

/** What the person judges. Consoles choose a subject view by it. */
public enum SubjectKind {
  ACTION("action"),
  BEHAVIOUR("behaviour"),
  CHANGE("change"),
  SYSTEM("system"),
  OUTPUT("output"),
  RECORDS("records"),
  APPEAL("appeal"),
  EXCEPTION("exception"),
  CONVERSATION("conversation"),
  ATTESTATION("attestation");

  private final String id;

  SubjectKind(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static SubjectKind of(String id) {
    for (var kind : values()) {
      if (kind.id.equals(id)) return kind;
    }
    throw new IllegalArgumentException("not a subject kind: " + id);
  }
}
