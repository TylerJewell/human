import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { extract } from "./extract";
import { highlight } from "./highlight";
import { SOURCES } from "./sources";

const ROOT = resolve(__dirname, "../../../../..");

const JAVA = `class A {
  int other() { return 1; }

  /**
   * Pays.
   */
  @Override
  private StepEffect pay() {
    var s = "a { brace";
    if (x) {
      return y; // }
    }
    return z;
  }

  void after() {}
}`;

describe("extract", () => {
  it("cuts a method with its Javadoc and annotation, ignoring braces in strings and comments", () => {
    const s = extract(JAVA, { file: "A.java", lang: "java", method: "private StepEffect pay()" });
    expect(s.line).toBe(4);
    expect(s.code.split("\n")[0]).toBe("/**");
    expect(s.code.split("\n").at(-1)).toBe("}");
    expect(s.code).toContain("return z;");
    expect(s.code).not.toContain("after");
  });

  it("cuts a builder call until its parentheses close", () => {
    const text = `x()\n    .handler("match", record(c, "match", d -> switch (d) {\n      case A -> "same";\n    }))\n    .handler("next", y)`;
    const s = extract(text, { file: "S.java", lang: "java", call: '.handler("match"' });
    expect(s.code.split("\n")).toHaveLength(3);
    expect(s.code).not.toContain("next");
  });

  it("cuts one control from the YAML catalog", () => {
    const text = "controls:\n  - id: a\n    name: A\n    x: { y: 1 }\n\n  - id: b\n    name: B\n";
    const s = extract(text, { file: "c.yaml", lang: "yaml", yamlItem: "a" });
    expect(s.code).toBe("- id: a\n  name: A\n  x: { y: 1 }");
    expect(s.line).toBe(2);
  });

  it("finds every source the tour shows in the repository", () => {
    for (const [key, ref] of Object.entries(SOURCES)) {
      const text = readFileSync(resolve(ROOT, ref.file), "utf8");
      const s = extract(text, ref);
      expect(s.code.length, key).toBeGreaterThan(20);
    }
  });
});

describe("highlight", () => {
  it("colours Java keywords, calls, strings and annotations", () => {
    const [line] = highlight('@Post("/x") public Raised waiver(String s) {', "java");
    const kinds = Object.fromEntries(line.map((t) => [t.text, t.kind]));
    expect(kinds["@Post"]).toBe("ann");
    expect(kinds['"/x"']).toBe("str");
    expect(kinds.public).toBe("kw");
    expect(kinds.waiver).toBe("fn");
    expect(kinds.Raised).toBe("type");
  });

  it("carries a block comment across lines", () => {
    const lines = highlight("/**\n * text { here\n */\nint x;", "java");
    expect(lines[1].every((t) => t.kind === "com")).toBe(true);
    expect(lines[3].some((t) => t.kind === "kw")).toBe(true);
  });

  it("colours YAML keys and inline values", () => {
    const [line] = highlight('  trigger: { detector: tool-policy, condition: "pay.amount > 1000" }', "yaml");
    expect(line.find((t) => t.text === "trigger")?.kind).toBe("key");
    expect(line.find((t) => t.text === "detector")?.kind).toBe("key");
    expect(line.find((t) => t.text.startsWith('"pay'))?.kind).toBe("str");
  });

  it("keeps every character of the code", () => {
    const code = readFileSync(resolve(ROOT, "spec/fixtures/controls-patterns.yaml"), "utf8");
    const back = highlight(code, "yaml")
      .map((l) => l.map((t) => t.text).join(""))
      .join("\n");
    expect(back).toBe(code.replace(/\r\n/g, "\n"));
  });
});
